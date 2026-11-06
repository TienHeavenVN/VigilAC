package ac.vigil.check.combat;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ac.vigil.check.ViolationCallback;
import ac.vigil.data.PlayerData;
import ac.vigil.detection.AttackContext;

class AutoClickerCheckTest {

    private static final UUID PLAYER = UUID.randomUUID();

    private AutoClickerCheck check;
    private PlayerData data;
    private List<Double> flagged;

    @BeforeEach
    void setUp() {
        check = new AutoClickerCheck(20.5D, 10, 150.0D, 12.0D, 10, 5, 1.0D);
        data = new PlayerData(PLAYER);
        flagged = new ArrayList<>();
    }

    private ViolationCallback sink() {
        return (c, vl, debug) -> flagged.add(vl);
    }

    private AttackContext hit(long timestamp) {
        return AttackContext.builder()
                .attacker(PLAYER)
                .targetUniqueId("zombie-1")
                .eye(0.0D, 66.0D, 0.0D)
                .box(-0.5D, 64.0D, 2.0D, 0.5D, 66.0D, 3.0D)
                .timestamp(timestamp)
                .build();
    }

    /** Mirrors the AttackListener contract: one recordAttack per attack, then check evaluation. */
    private void attack(long timestamp) {
        data.combatTracker().recordAttack(timestamp);
        check.evaluate(hit(timestamp), data, sink());
    }

    @Test
    void metronomicClickingFlags() {
        long now = 1_000_000L;
        for (int i = 0; i < 30; i++) { // exactly 100 ms between clicks: 10 CPS, stddev 0
            now += 100L;
            attack(now);
        }
        assertFalse(flagged.isEmpty(), "12+ consecutive identical intervals must flag as robotic");
    }

    @Test
    void humanJitterNeverFlags() {
        long now = 1_000_000L;
        long[] pattern = {87L, 142L, 66L, 190L, 121L, 95L, 173L, 74L, 158L, 109L}; // irregular, ~9 CPS
        for (int rep = 0; rep < 4; rep++) {
            for (long interval : pattern) {
                now += interval;
                attack(now);
            }
        }
        assertTrue(flagged.isEmpty(), "irregular human timing must not flag, got " + flagged);
    }

    @Test
    void extremeCpsFlagsRateGate() {
        long now = 1_000_000L;
        for (int i = 0; i < 13; i++) { // 33 ms apart: ~30 CPS, above the 20.5 hardware ceiling
            now += 33L;
            attack(now);
        }
        assertFalse(flagged.isEmpty(), "30 CPS sustained exceeds the rate gate");
    }

    @Test
    void moderateButBurstyNeverFlags() {
        long now = 1_000_000L;
        for (int i = 0; i < 40; i++) { // 8 attacks/s with realistic jitter
            now += (i % 2 == 0) ? 80L : 170L;
            attack(now);
        }
        assertTrue(flagged.isEmpty(), "8 CPS with jitter is unremarkable combat");
    }
}
