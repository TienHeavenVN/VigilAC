package ac.vigil.check.movement;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ac.vigil.check.ViolationCallback;
import ac.vigil.data.PlayerData;
import ac.vigil.detection.DetectionContext;

class JesusCheckTest {

    private static final UUID PLAYER = UUID.randomUUID();

    private JesusCheck check;
    private PlayerData data;
    private List<Double> flagged;

    @BeforeEach
    void setUp() {
        check = new JesusCheck(14, 0.5D);
        data = new PlayerData(PLAYER);
        flagged = new ArrayList<>();
    }

    private ViolationCallback sink() {
        return (c, vl, debug) -> flagged.add(vl);
    }

    private DetectionContext surface(long timestamp, boolean inLiquid, boolean frostWalker,
                                     boolean vehicle, boolean gliding, boolean levitation,
                                     boolean slowFalling, boolean teleportGrace, boolean knockbackGrace, double tps) {
        return DetectionContext.builder()
                .player(PLAYER)
                .from(0.0D, 63.0D, 0.0D)
                .to(0.4D, 63.0D, 0.0D)
                .onWaterSurface(true)
                .inLiquid(inLiquid)
                .frostWalker(frostWalker)
                .inVehicle(vehicle)
                .gliding(gliding)
                .levitation(levitation)
                .slowFalling(slowFalling)
                .teleportGrace(teleportGrace)
                .knockbackGrace(knockbackGrace)
                .serverTps(tps)
                .timestamp(timestamp)
                .build();
    }

    private DetectionContext notSurface(long timestamp) {
        return DetectionContext.builder()
                .player(PLAYER)
                .from(0.0D, 64.0D, 0.0D)
                .to(0.4D, 64.0D, 0.0D)
                .onGround(true)
                .serverTps(20.0D)
                .timestamp(timestamp)
                .build();
    }

    @Test
    void sustainedWaterWalkerFlagsAfterMinStreak() {
        long now = 1_000_000L;
        for (int i = 0; i < 30; i++) {
            now += 50L;
            check.evaluate(surface(now, false, false, false, false, false, false, false, false, 20.0D), data, sink());
        }
        assertFalse(flagged.isEmpty(), "30 consecutive dry-feet-above-water ticks must flag");
        // first 13 ticks silent, VL appears only from tick 14 onwards
        assertEquals(17, flagged.size(), "only observations past the streak gate add VL");
    }

    @Test
    void frostWalkerNeverFlags() {
        long now = 1_000_000L;
        for (int i = 0; i < 40; i++) {
            now += 50L;
            check.evaluate(surface(now, false, true, false, false, false, false, false, false, 20.0D), data, sink());
        }
        assertTrue(flagged.isEmpty(), "Frost Walker is vanilla-legal water walking");
    }

    @Test
    void enteringLiquidResetsStreak() {
        long now = 1_000_000L;
        for (int i = 0; i < 13; i++) { // build a 13-tick streak
            now += 50L;
            check.evaluate(surface(now, false, false, false, false, false, false, false, false, 20.0D), data, sink());
        }
        now += 50L;
        check.evaluate(surface(now, true, false, false, false, false, false, false, false, 20.0D), data, sink()); // dip
        for (int i = 0; i < 13; i++) { // fresh 13 ticks — must still be below the gate
            now += 50L;
            check.evaluate(surface(now, false, false, false, false, false, false, false, false, 20.0D), data, sink());
        }
        assertTrue(flagged.isEmpty(), "liquid dip resets the streak; arc re-entry starts from zero");
    }

    @Test
    void leavingSurfaceResetsStreak() {
        long now = 1_000_000L;
        for (int i = 0; i < 13; i++) {
            now += 50L;
            check.evaluate(surface(now, false, false, false, false, false, false, false, false, 20.0D), data, sink());
        }
        now += 50L;
        check.evaluate(notSurface(now), data, sink());
        for (int i = 0; i < 13; i++) {
            now += 50L;
            check.evaluate(surface(now, false, false, false, false, false, false, false, false, 20.0D), data, sink());
        }
        assertTrue(flagged.isEmpty(), "ground contact resets the streak");
    }

    @Test
    void vehicleGlideLevitationSlowFallingAreExempt() {
        long now = 1_000_000L;
        boolean[][] modes = {
                {true, false, false, false},
                {false, true, false, false},
                {false, false, true, false},
                {false, false, false, true},
        };
        for (boolean[] mode : modes) {
            PlayerData fresh = new PlayerData(UUID.randomUUID());
            List<Double> freshFlagged = new ArrayList<>();
            ViolationCallback freshSink = (c, vl, debug) -> freshFlagged.add(vl);
            long t = now;
            for (int i = 0; i < 40; i++) {
                t += 50L;
                freshCheck(fresh).evaluate(
                        surface(t, false, false, mode[0], mode[1], mode[2], mode[3], false, false, 20.0D),
                        fresh, freshSink);
            }
            assertTrue(freshFlagged.isEmpty(), "modifier state must exempt: " + java.util.Arrays.toString(mode));
        }
    }

    private JesusCheck freshCheck(PlayerData ignored) {
        return check; // check instance is stateless; state lives in PlayerData/MovementTracker
    }

    @Test
    void teleportAndKnockbackGraceResetStreak() {
        long now = 1_000_000L;
        for (int i = 0; i < 13; i++) {
            now += 50L;
            check.evaluate(surface(now, false, false, false, false, false, false, false, false, 20.0D), data, sink());
        }
        now += 50L;
        check.evaluate(surface(now, false, false, false, false, false, false, true, false, 20.0D), data, sink());
        now += 50L;
        check.evaluate(surface(now, false, false, false, false, false, false, false, true, 20.0D), data, sink());
        for (int i = 0; i < 11; i++) { // 11 more fresh ticks -> streak 11 < 14
            now += 50L;
            check.evaluate(surface(now, false, false, false, false, false, false, false, false, 20.0D), data, sink());
        }
        assertTrue(flagged.isEmpty(), "grace ticks must reset the streak");
    }

    @Test
    void lowTpsSuppresses() {
        long now = 1_000_000L;
        for (int i = 0; i < 40; i++) {
            now += 50L;
            check.evaluate(surface(now, false, false, false, false, false, false, false, false, 9.5D), data, sink());
        }
        assertTrue(flagged.isEmpty(), "below the TPS floor the check stays silent");
    }

    @Test
    void isolatedJumpArcOverWaterNeverFlags() {
        long now = 1_000_000L;
        // 12-tick arc above the water plane, then a liquid dip — the vanilla pattern
        for (int arc = 0; arc < 5; arc++) {
            for (int i = 0; i < 12; i++) {
                now += 50L;
                check.evaluate(surface(now, false, false, false, false, false, false, false, false, 20.0D), data, sink());
            }
            now += 50L;
            check.evaluate(surface(now, true, false, false, false, false, false, false, false, 20.0D), data, sink());
        }
        assertTrue(flagged.isEmpty(), "repeated 12-tick arcs with a dip between them must not accumulate to the 14-tick gate");
    }
}
