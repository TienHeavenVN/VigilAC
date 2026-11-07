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

class NoFallCheckTest {

    private static final UUID PLAYER = UUID.randomUUID();

    private NoFallCheck check;
    private PlayerData data;
    private List<Double> flagged;

    @BeforeEach
    void setUp() {
        check = new NoFallCheck(3.5D, 500L, 3, 1.5D);
        data = new PlayerData(PLAYER);
        flagged = new ArrayList<>();
    }

    private ViolationCallback sink() {
        return (c, vl, debug) -> flagged.add(vl);
    }

    private DetectionContext airborne(double dy, long timestamp) {
        return DetectionContext.builder()
                .player(PLAYER)
                .from(0.0D, 100.0D, 0.0D)
                .to(0.0D, 100.0D + dy, 0.0D)
                .onGround(false)
                .serverTps(20.0D)
                .timestamp(timestamp)
                .build();
    }

    private DetectionContext grounded(long timestamp) {
        return DetectionContext.builder()
                .player(PLAYER)
                .from(0.0D, 64.0D, 0.0D)
                .to(0.5D, 64.0D, 0.0D)
                .onGround(true)
                .serverTps(20.0D)
                .timestamp(timestamp)
                .build();
    }

    /**
     * One full 5-block fall: 5 downward ticks of 1.0 each, then a landing tick.
     * damageOnLanding: 0 = none, 1 = FALL damage, 2 = non-FALL damage.
     */
    private void oneFall(long start, int damageOnLanding) {
        long now = start;
        for (int i = 0; i < 5; i++) {
            now += 50L;
            check.evaluate(airborne(-1.0D, now), data, sink());
        }
        if (damageOnLanding == 1) {
            data.monitor().noteDamage(true); // FALL damage: exculpatory
        } else if (damageOnLanding == 2) {
            data.monitor().noteDamage(false); // non-FALL damage: must NOT excuse
        }
        now += 50L;
        check.evaluate(grounded(now), data, sink());
    }

    @Test
    void fallingWithDamageNeverFlags() {
        for (int fall = 0; fall < 5; fall++) {
            oneFall(1_000_000L + fall * 10_000L, 1);
        }
        assertTrue(flagged.isEmpty(), "fall damage on landing suppresses all evidence");
    }

    @Test
    void unrelatedDamageDoesNotSuppressNoFallEvidence() {
        // Non-fall damage between repeats must not reset the suspicious-landing streak:
        // 3 impossible 5-block falls, each "protected" by unrelated damage, must still flag.
        for (int fall = 0; fall < 3; fall++) {
            oneFall(1_000_000L + fall * 10_000L, 2);
        }
        assertFalse(flagged.isEmpty(), "unrelated (non-fall) damage must not suppress NoFall evidence");
    }

    @Test
    void repeatedImpossibleLandingsFlag() {
        for (int fall = 0; fall < 3; fall++) {
            oneFall(1_000_000L + fall * 10_000L, 0);
        }
        assertFalse(flagged.isEmpty(), "three consecutive 5-block landings without damage must flag");
    }

    @Test
    void singleImpossibleLandingDoesNotFlag() {
        oneFall(1_000_000L, 0);
        oneFall(2_000_000L, 1);  // fall damage this time resets the streak
        oneFall(3_000_000L, 0);
        oneFall(4_000_000L, 0);
        oneFall(5_000_000L, 0);
        // landings: fall1 (streak 1), fall2 w/damage (streak reset), fall3 (1), fall4 (2), fall5 (3) -> flag on fall5
        assertFalse(flagged.isEmpty(), "after the reset, three consecutive suspicious landings flag");
    }

    @Test
    void shortFallNeverFlags() {
        long now = 1_000_000L;
        for (int fall = 0; fall < 6; fall++) {
            now += 50L;
            check.evaluate(airborne(-0.5D, now), data, sink()); // 1.0 block total
            now += 50L;
            check.evaluate(grounded(now), data, sink());
        }
        assertTrue(flagged.isEmpty(), "1-block falls are below the damage threshold");
    }

    @Test
    void slowFallingExempt() {
        long now = 1_000_000L;
        for (int fall = 0; fall < 4; fall++) {
            for (int i = 0; i < 5; i++) {
                now += 50L;
                check.evaluate(DetectionContext.builder()
                        .player(PLAYER).from(0.0D, 100.0D, 0.0D).to(0.0D, 99.0D, 0.0D)
                        .onGround(false).slowFalling(true).serverTps(20.0D).timestamp(now).build(),
                        data, sink());
            }
            now += 50L;
            check.evaluate(grounded(now), data, sink());
        }
        assertTrue(flagged.isEmpty());
    }
}
