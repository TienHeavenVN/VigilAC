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

class SpeedCheckTest {

    private static final UUID PLAYER = UUID.randomUUID();

    private SpeedCheck check;
    private PlayerData data;
    private List<Double> flagged;

    @BeforeEach
    void setUp() {
        check = new SpeedCheck(4.4D, 5.8D, 0.2D, 1.6D, 2.0D, 1_200L); // walkCap, sprintCap, potionBonus, slipperyMultiplier, excessVlPerSecond, minSustainSeconds
        data = new PlayerData(PLAYER);
        flagged = new ArrayList<>();
    }

    private ViolationCallback sink() {
        return (c, vl, debug) -> flagged.add(vl);
    }

    private DetectionContext sample(double horizontalDistance, long timestamp, boolean sprint) {
        return DetectionContext.builder()
                .player(PLAYER)
                .from(0.0D, 64.0D, 0.0D)
                .to(horizontalDistance, 64.0D, 0.0D)
                .sprinting(sprint)
                .serverTps(20.0D)
                .timestamp(timestamp)
                .build();
    }

    @Test
    void backlogBurstDoesNotFabricateSpeed() {
        long now = 1_000_000L;
        // 20 legitimate 50 ms samples at 5.5 b/s (sprinting, under the 5.8 cap).
        for (int i = 0; i < 20; i++) {
            now += 50L;
            check.evaluate(sample(5.5D * 0.05D, now, true), data, sink());
        }
        // Server drains a backlog: three events inside one tick. The wall-clock
        // dt under-represents travel time; the sample would read as ~137 b/s.
        for (int i = 0; i < 3; i++) {
            now += 2L;
            check.evaluate(sample(0.275D, now, true), data, sink());
        }
        assertTrue(flagged.isEmpty(), "burst-drain samples must not accrue VL");
    }

    @Test
    void sustainedSprintCheatStillFlags() {
        long now = 1_000_000L;
        for (int i = 0; i < 200; i++) { // 10 s at 7.5 b/s sprinting
            now += 50L;
            check.evaluate(sample(7.5D * 0.05D, now, true), data, sink());
        }
        assertFalse(flagged.isEmpty(), "sustained 7.5 b/s sprinting must flag");
    }

    @Test
    void legitimateSprintJumpNeverFlags() {
        long now = 1_000_000L;
        // mineflayer-style sprint-jump: ~5.4 b/s bursts with periodic dips,
        // all under the sprint cap once the sprint flag registers.
        double[] speeds = {5.3D, 5.7D, 5.5D, 5.2D, 5.7D, 5.0D, 5.6D, 5.4D};
        for (int i = 0; i < 80; i++) {
            now += 50L;
            check.evaluate(sample(speeds[i % speeds.length] * 0.05D, now, true), data, sink());
        }
        assertTrue(flagged.isEmpty(), "legit sprint-jump speed under cap must not flag");
    }
}
