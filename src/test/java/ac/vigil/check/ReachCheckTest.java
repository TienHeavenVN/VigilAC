package ac.vigil.check;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ac.vigil.check.combat.ReachCheck;
import ac.vigil.data.PlayerData;
import ac.vigil.detection.AttackContext;

/**
 * Simulation tests for {@link ReachCheck}. Target boxes are placed so the
 * eye-to-box distance equals exactly {@code distance} (eye height 1.62,
 * box spans 1.0..2.0, eye x/z inside the box footprint).
 */
class ReachCheckTest {

    private static final UUID ATTACKER = UUID.randomUUID();

    private ReachCheck check;
    private PlayerData data;
    private List<Double> flagged;

    @BeforeEach
    void setUp() {
        check = new ReachCheck(3.0D, 0.03D, 4, 1.0D, 6);
        data = new PlayerData(ATTACKER);
        flagged = new ArrayList<>();
    }

    private ViolationCallback sink() {
        return (c, vl, debug) -> flagged.add(vl);
    }

    private AttackContext attackAt(double distance, boolean creative, boolean vehicle) {
        return AttackContext.builder()
                .attacker(ATTACKER)
                .targetType("ZOMBIE")
                .eye(0.0D, 1.62D, 0.0D)
                .box(-0.3D, 1.0D, distance, 0.3D, 2.0D, distance + 0.6D)
                .creativeOrSpectator(creative)
                .attackerInVehicle(vehicle)
                .targetInVehicle(false)
                .pingMillis(50)
                .build();
    }

    private void attack(double distance, int times, boolean creative, boolean vehicle) {
        for (int i = 0; i < times; i++) {
            check.evaluate(attackAt(distance, creative, vehicle), data, sink());
        }
    }

    @Test
    void vanillaReachNeverFlags() {
        attack(2.5D, 20, false, false);
        assertTrue(flagged.isEmpty(), "2.5 block hits must never flag");
    }

    @Test
    void edgeReachWithinToleranceNeverFlags() {
        attack(3.02D, 20, false, false);
        assertTrue(flagged.isEmpty(), "within tolerance (<=3.03) must not flag");
    }

    @Test
    void consecutiveOverReachAttacksFlag() {
        // 3 over-reach attacks: below the multi-observation gate.
        attack(3.5D, 3, false, false);
        assertTrue(flagged.isEmpty(), "fewer than min-attacks-over must not flag");
        // 4th attack: gate opens (ema of {3.5 x4} > 3.03, consecutive >= 4).
        attack(3.5D, 1, false, false);
        assertFalse(flagged.isEmpty(), "sustained over-reach must flag");
        double total = flagged.stream().mapToDouble(Double::doubleValue).sum();
        assertTrue(total >= 1.0D, "expected VL >= 1.0, got " + total);
    }

    @Test
    void legitAttackResetsConsecutiveCounter() {
        attack(3.5D, 3, false, false); // 3 over, gate not open
        attack(2.5D, 1, false, false); // legit hit: counter resets
        attack(3.5D, 2, false, false); // only 2 consecutive again
        assertTrue(flagged.isEmpty(), "a legit attack must reset the streak");
        attack(3.5D, 2, false, false); // now 4 consecutive
        assertFalse(flagged.isEmpty(), "streak of 4 after reset must flag");
    }

    @Test
    void creativeModeNeverFlags() {
        attack(6.0D, 20, true, false);
        assertTrue(flagged.isEmpty(), "creative/spectator must be exempt");
    }

    @Test
    void attackerInVehicleNeverFlags() {
        attack(6.0D, 20, false, true);
        assertTrue(flagged.isEmpty(), "attacking from a vehicle must be exempt");
    }

    @Test
    void extremeReachFlagsWithLargeVl() {
        attack(4.5D, 4, false, false);
        assertFalse(flagged.isEmpty(), "blatant reach must flag");
        for (Double vl : flagged) {
            assertTrue(vl <= 2.0D, "VL per attack is capped at 2.0");
        }
    }
}
