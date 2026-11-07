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

/**
 * Integration tests: StepCheck x StepBounceTracker. Bounce arcs must suspend
 * Step bookkeeping; everything else (including Step cheats wearing a slime
 * block as a costume) must flag.
 */
class StepCheckBounceTest {

    private static final UUID PLAYER = UUID.randomUUID();

    private StepCheck check;
    private PlayerData data;
    private List<Double> flagged;

    @BeforeEach
    void setUp() {
        check = new StepCheck(0.95D, 2, 2.5D);
        data = new PlayerData(PLAYER);
        flagged = new ArrayList<>();
    }

    private ViolationCallback sink() {
        return (c, vl, debug) -> flagged.add(vl);
    }

    private DetectionContext tick(double fromY, double dy, long timestamp, boolean belowSlime, boolean teleportGrace) {
        return DetectionContext.builder()
                .player(PLAYER)
                .from(0.0D, fromY, 0.0D)
                .to(0.0D, fromY + dy, 0.0D)
                .belowFeetSlippery(belowSlime)
                .teleportGrace(teleportGrace)
                .serverTps(20.0D)
                .timestamp(timestamp)
                .build();
    }

    @Test
    void sneakChargeBounceYieldsNoViolation() {
        long now = 1_000_000L;
        double y = 63.0D;
        // Two falling ticks.
        y += -0.6D;
        check.evaluate(tick(y + 0.6D, -0.6D, now, false, false), data, sink());
        check.evaluate(tick(y, -0.6D, now + 50, false, false), data, sink());
        // Sneak-charged bounce: every rising tick is > 0.95, which would be a
        // Step hack without the bounded arc model.
        double[] arc = {1.6D, 1.5D, 1.4D, 1.3D, 1.2D, 1.1D};
        for (int i = 0; i < arc.length; i++) {
            double dy = arc[i];
            check.evaluate(tick(y, dy, now + 100L * (i + 1), i == 0, false), data, sink());
            y += dy;
        }
        assertTrue(flagged.isEmpty(), "a validated sneak-charge bounce arc must never flag Step, got " + flagged);
    }

    @Test
    void sameArcWithoutSlimeContactStillFlags() {
        long now = 1_000_000L;
        double y = 63.0D;
        check.evaluate(tick(y, 1.5D, now, false, false), data, sink());
        check.evaluate(tick(y + 1.5D, 1.4D, now + 50, false, false), data, sink());
        assertFalse(flagged.isEmpty(), "upward arc with no slippery contact is Step evidence");
    }

    @Test
    void upwardAccelerationInsideFakeArcStillFlags() {
        long now = 1_000_000L;
        double y = 63.0D;
        // Falling ticks.
        check.evaluate(tick(y, -0.6D, now, false, false), data, sink());
        // Launch tick (would be a bounce)...
        check.evaluate(tick(y - 0.6D, 1.3D, now + 50, true, false), data, sink());
        // ...then the arc accelerates upward: impossible ballistically.
        check.evaluate(tick(y - 0.6D + 1.3D, 1.4D, now + 100, false, false), data, sink());
        check.evaluate(tick(y - 0.6D + 2.7D, 1.2D, now + 150, false, false), data, sink());
        assertFalse(flagged.isEmpty(), "acceleration inside the arc breaks the bounce model and exposes the Step hack");
    }

    @Test
    void teleportDiscardsArcExemption() {
        long now = 1_000_000L;
        double y = 63.0D;
        check.evaluate(tick(y, -0.6D, now, false, false), data, sink());
        check.evaluate(tick(y - 0.6D, 1.2D, now + 50, true, false), data, sink()); // EXEMPT_BOUNCE launch
        // Teleport event: arc must be discarded (and the streak reset as usual).
        check.evaluate(tick(y + 10.0D, 1.2D, now + 100, false, true), data, sink());
        // Back in normal play: two consecutive impossibilities flag.
        check.evaluate(tick(y + 10.0D, 1.2D, now + 150, false, false), data, sink());
        check.evaluate(tick(y + 11.2D, 1.2D, now + 200, false, false), data, sink());
        assertFalse(flagged.isEmpty(), "no stale exemption may survive a teleport");
    }

    @Test
    void exemptBounceTickLeavesImpossibilityStreakUntouched() {
        long now = 1_000_000L;
        double y = 63.0D;
        // Open a streak of 1 (no VL at streak < 2).
        check.evaluate(tick(y, 1.05D, now, false, false), data, sink());
        assertEquals(1, data.movementTracker().getStepImpossibilityStreak());
        // Falling tick resets the streak to 0 - legal observations always do.
        check.evaluate(tick(y, -0.6D, now + 50, false, false), data, sink());
        assertEquals(0, data.movementTracker().getStepImpossibilityStreak());
        // Launch tick (EXEMPT_BOUNCE): must NOT touch the streak counter at all.
        double y2 = y - 0.6D;
        check.evaluate(tick(y2, 1.2D, now + 100, true, false), data, sink());
        assertEquals(0, data.movementTracker().getStepImpossibilityStreak(),
                "EXEMPT_BOUNCE neither increments nor resets the streak");
        // Arc ticks are also untouched.
        check.evaluate(tick(y2 + 1.2D, 1.1D, now + 150, false, false), data, sink());
        assertEquals(0, data.movementTracker().getStepImpossibilityStreak(),
                "arc ticks must not accumulate step evidence");
    }
}
