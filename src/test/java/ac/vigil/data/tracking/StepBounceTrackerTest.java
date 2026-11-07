package ac.vigil.data.tracking;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for the bounded slime-bounce state machine.
 */
class StepBounceTrackerTest {

    private StepBounceTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new StepBounceTracker();
    }

    private StepBounceTracker.Verdict tick(long now, double dy, boolean belowSlime) {
        return tracker.update(now, dy, belowSlime, false, 20.0D, false);
    }

    @Test
    void fullBounceArcIsExemptUntilApex() {
        long now = 1_000_000L;
        assertEquals(StepBounceTracker.Verdict.NOT_BOUNCE, tick(now, -0.5D, false));
        assertEquals(StepBounceTracker.Verdict.NOT_BOUNCE, tick(now + 50, -0.5D, false));
        // Launch tick: falling -> rising off slippery contact.
        assertEquals(StepBounceTracker.Verdict.EXEMPT_BOUNCE, tick(now + 100, 1.0D, true));
        assertTrue(tracker.isInBounceSequence());
        // Ballistic decay: 1.0 - 0.08*k
        assertEquals(StepBounceTracker.Verdict.EXEMPT_BOUNCE, tick(now + 150, 0.92D, false));
        assertEquals(StepBounceTracker.Verdict.EXEMPT_BOUNCE, tick(now + 200, 0.84D, false));
        assertEquals(StepBounceTracker.Verdict.EXEMPT_BOUNCE, tick(now + 250, 0.76D, false));
        assertEquals(StepBounceTracker.Verdict.EXEMPT_BOUNCE, tick(now + 300, 0.68D, false));
        assertEquals(StepBounceTracker.Verdict.EXEMPT_BOUNCE, tick(now + 350, 0.60D, false));
        // Apex reached; arc is over.
        assertFalse(tracker.isInBounceSequence());
        // Next falling tick must not inherit an exemption.
        assertEquals(StepBounceTracker.Verdict.NOT_BOUNCE, tick(now + 400, 0.20D, false));
    }

    @Test
    void sneakChargeBounceAtCeilingIsExempt() {
        long now = 1_000_000L;
        tick(now, -0.9D, false);
        // Full sneak-absorbed charge: launch 1.6 blocks/tick.
        assertEquals(StepBounceTracker.Verdict.EXEMPT_BOUNCE, tick(now + 50, 1.6D, true));
        assertEquals(StepBounceTracker.Verdict.EXEMPT_BOUNCE, tick(now + 100, 1.5D, false));
        assertEquals(StepBounceTracker.Verdict.EXEMPT_BOUNCE, tick(now + 150, 1.45D, false));
    }

    @Test
    void launchAboveSneakChargeCeilingIsRejected() {
        long now = 1_000_000L;
        tick(now, -0.9D, false);
        // 1.7 blocks/tick cannot be a slime bounce; Step must see it.
        assertEquals(StepBounceTracker.Verdict.NOT_BOUNCE, tick(now + 50, 1.7D, true));
        assertFalse(tracker.isInBounceSequence());
    }

    @Test
    void launchWithoutSlipperyContactIsRejected() {
        long now = 1_000_000L;
        tick(now, -0.5D, false);
        assertEquals(StepBounceTracker.Verdict.NOT_BOUNCE, tick(now + 50, 1.0D, false));
    }

    @Test
    void launchWithoutFallingBeforeIsRejected() {
        long now = 1_000_000L;
        // Standing still (prev dy = 0), then an upward jump off slime.
        tick(now, 0.0D, false);
        assertEquals(StepBounceTracker.Verdict.NOT_BOUNCE, tick(now + 50, 1.0D, true));
    }

    @Test
    void upwardAccelerationEndsTheArc() {
        long now = 1_000_000L;
        tick(now, -0.6D, false);
        assertEquals(StepBounceTracker.Verdict.EXEMPT_BOUNCE, tick(now + 50, 1.0D, true));
        // Hacked: rises must decay. 1.4 >> expected 0.92 and accelerates.
        assertEquals(StepBounceTracker.Verdict.NOT_BOUNCE, tick(now + 100, 1.4D, false));
        assertFalse(tracker.isInBounceSequence());
    }

    @Test
    void ballisticDeviationEndsTheArc() {
        long now = 1_000_000L;
        tick(now, -0.6D, false);
        assertEquals(StepBounceTracker.Verdict.EXEMPT_BOUNCE, tick(now + 50, 1.0D, true));
        // Expected 0.92; 0.30 deviates by 0.62.
        assertEquals(StepBounceTracker.Verdict.NOT_BOUNCE, tick(now + 100, 0.30D, false));
    }

    @Test
    void eighthSequenceInWindowExemptsNinthDoesNot() {
        long now = 1_000_000L;
        int exempts = 0;
        int rejects = 0;
        for (int i = 0; i < 9; i++) {
            // falling tick
            tick(now, -0.5D, false);
            StepBounceTracker.Verdict launch = tick(now + 1, 1.0D, true);
            if (launch == StepBounceTracker.Verdict.EXEMPT_BOUNCE) {
                exempts++;
            } else {
                rejects++;
            }
            // Close the arc on the next tick (deltaY<=0 ends it).
            tick(now + 2, 0.0D, false);
            now += 100; // stay within the 4 s window
        }
        assertEquals(8, exempts, "eight bounce sequences per 4 s are honored");
        assertEquals(1, rejects, "the ninth launch candidate in the window is refused");
        assertEquals(8L, tracker.sequenceCount());
    }

    @Test
    void windowRollsAndAdmitsNewSequences() {
        long now = 1_000_000L;
        for (int i = 0; i < 8; i++) {
            tick(now, -0.5D, false);
            tick(now + 1, 1.0D, true);
            tick(now + 2, 0.0D, false);
            now += 100;
        }
        // Jump beyond the 4 s rolling window; oldest sequences expire.
        now += 5_000L;
        assertEquals(0, tracker.sequencesInWindow(now));
        tick(now, -0.5D, false);
        assertEquals(StepBounceTracker.Verdict.EXEMPT_BOUNCE, tick(now + 1, 1.0D, true));
    }

    @Test
    void teleportDiscardsInFlightArc() {
        long now = 1_000_000L;
        tick(now, -0.6D, false);
        assertEquals(StepBounceTracker.Verdict.EXEMPT_BOUNCE, tick(now + 50, 1.0D, true));
        assertTrue(tracker.isInBounceSequence());
        assertEquals(StepBounceTracker.Verdict.NOT_BOUNCE,
                tracker.update(now + 100, 0.92D, false, false, 20.0D, true));
        assertFalse(tracker.isInBounceSequence());
    }

    @Test
    void lowTpsDiscardsInFlightArcAndRefusesCandidates() {
        long now = 1_000_000L;
        tick(now, -0.6D, false);
        assertEquals(StepBounceTracker.Verdict.EXEMPT_BOUNCE, tick(now + 50, 1.0D, true));
        assertEquals(StepBounceTracker.Verdict.NOT_BOUNCE,
                tracker.update(now + 100, 0.92D, false, false, 8.0D, false));
        assertFalse(tracker.isInBounceSequence());
        // And launches are refused too while TPS is low.
        assertEquals(StepBounceTracker.Verdict.NOT_BOUNCE, tick(now + 150, -0.5D, false));
        assertEquals(StepBounceTracker.Verdict.NOT_BOUNCE,
                tracker.update(now + 200, 1.0D, true, false, 8.0D, false));
    }

    @Test
    void nanTpsIsTreatedAsLowTps() {
        long now = 1_000_000L;
        assertEquals(StepBounceTracker.Verdict.NOT_BOUNCE,
                tracker.update(now, 1.0D, true, false, Double.NaN, false));
    }

    @Test
    void arcExpiresAfterFiveRisingTicks() {
        long now = 1_000_000L;
        tick(now, -0.6D, false);
        assertEquals(StepBounceTracker.Verdict.EXEMPT_BOUNCE, tick(now + 50, 1.6D, true));
        double expected = 1.6D;
        for (int k = 1; k <= 5; k++) {
            expected = 1.6D - 0.08D * k;
            StepBounceTracker.Verdict v = tick(now + 50L * (k + 1), expected, false);
            if (k < 5) {
                assertEquals(StepBounceTracker.Verdict.EXEMPT_BOUNCE, v, "tick " + k);
            }
        }
        // risingTicks reached MAX_RISING_TICKS (5); arc closed at k == 5.
        assertFalse(tracker.isInBounceSequence());
    }

    @Test
    void consecutiveBounceSequencesEachValidateIndependently() {
        long now = 1_000_000L;
        for (int arc = 0; arc < 3; arc++) {
            tick(now, -0.7D, false);
            assertEquals(StepBounceTracker.Verdict.EXEMPT_BOUNCE, tick(now + 50, 1.2D, true));
            assertEquals(StepBounceTracker.Verdict.EXEMPT_BOUNCE, tick(now + 100, 1.12D, false));
            // End the arc.
            tick(now + 150, 0.0D, false);
            now += 300;
        }
        assertEquals(3L, tracker.sequenceCount());
    }
}
