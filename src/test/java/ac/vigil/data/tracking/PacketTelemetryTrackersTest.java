package ac.vigil.data.tracking;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

class PacketTelemetryTrackersTest {

    // ---------------- AttackAttemptLedger

    @Test
    void attemptLedgerCountsWithinWindow() {
        AttackAttemptLedger ledger = new AttackAttemptLedger();
        ledger.noteAttempt(1_000L, 42);
        ledger.noteAttempt(1_200L, 43);
        ledger.noteAttempt(1_400L, 44);
        assertEquals(3, ledger.attemptsSince(1_400L, 1_000L));
        assertEquals(2, ledger.attemptsBetween(1_000L, 1_400L));
        assertEquals(3.0D, ledger.attemptRate(1_400L, 1_000L), 1e-9);
        assertEquals(44, ledger.lastEntityId());
    }

    @Test
    void attemptLedgerEvictsOldEntries() {
        AttackAttemptLedger ledger = new AttackAttemptLedger();
        ledger.noteAttempt(1_000L, 1);
        ledger.noteAttempt(9_000L, 2);
        assertEquals(1, ledger.attemptsSince(9_000L, 1_000L));
        assertEquals(0, ledger.attemptsBetween(1_000L, 1_000L));
    }

    @Test
    void attemptLedgerIsCapacityBounded() {
        AttackAttemptLedger ledger = new AttackAttemptLedger();
        for (int i = 0; i < AttackAttemptLedger.CAPACITY + 20; i++) {
            ledger.noteAttempt(10_000L + i, i);
        }
        assertEquals(AttackAttemptLedger.CAPACITY, ledger.attemptsSince(10_000L + AttackAttemptLedger.CAPACITY, 100_000L));
    }

    // ---------------- SwingTracker

    @Test
    void swingTrackerCountsAndRates() {
        SwingTracker swings = new SwingTracker();
        for (int i = 0; i < 10; i++) {
            swings.noteSwing(2_000L + i * 40L);
        }
        assertEquals(10, swings.swingsSince(2_360L, 1_000L));
        assertEquals(10.0D, swings.swingRate(2_360L, 1_000L), 1e-9);
    }

    // ---------------- RotationTracker

    @Test
    void rotationTrackerKeepsBoundedWindow() {
        RotationTracker rotations = new RotationTracker();
        rotations.sample(1_000L, 0.0F, 0.0F);
        rotations.sample(1_100L, 10.0F, 0.0F);
        rotations.sample(1_200L, 30.0F, 5.0F);
        assertEquals(3, rotations.size(1_200L));
        List<RotationTracker.Sample> w = rotations.window(1_200L);
        assertEquals(3, w.size());
        assertEquals(30.0F, w.get(2).yaw);
        assertEquals(RotationTracker.shortestAngleDegrees(350F, 10F), 20.0D, 1e-9);
    }

    @Test
    void rotationTrackerAngularDistance() {
        RotationTracker rotations = new RotationTracker();
        rotations.sample(1_000L, 0.0F, 0.0F);
        rotations.sample(1_050L, 5.0F, 0.0F);
        rotations.sample(1_100L, 15.0F, 10.0F);
        double covered = rotations.angularDistanceCovered(1_100L, 500L);
        assertEquals(5.0D + Math.hypot(10.0D, 10.0D), covered, 1e-6); // 5° yaw + hypot(yaw 10°, pitch 10°)
    }

    @Test
    void rotationTrackerSamplesOutsideWindowAreDropped() {
        RotationTracker rotations = new RotationTracker();
        rotations.sample(1_000L, 0.0F, 0.0F);
        rotations.sample(50_000L, 90.0F, 0.0F);
        assertEquals(1, rotations.size(50_000L));
    }

    // ---------------- DiggingTracker

    @Test
    void digLifecycleStartComplete() {
        DiggingTracker dig = new DiggingTracker();
        dig.start(10, 64, 10, 1_000L);
        DiggingTracker.CompletedDig done = dig.complete(3_000L);
        assertNotNull(done);
        assertEquals(2_000L, done.getDurationMillis());
        assertEquals(10, done.getDig().getX());
        assertEquals(1, dig.completedDigs());
        assertNull(dig.current());
    }

    @Test
    void digAbortCancelsState() {
        DiggingTracker dig = new DiggingTracker();
        dig.start(1, 64, 1, 1_000L);
        DiggingTracker.ActiveDig aborted = dig.abort(1_500L);
        assertNotNull(aborted);
        assertEquals(1, dig.abortedDigs());
        assertNull(dig.complete(2_000L), "completed dig must not resolve after abort");
    }

    @Test
    void digTargetChangeAbandonsPreviousDig() {
        DiggingTracker dig = new DiggingTracker();
        dig.start(1, 64, 1, 1_000L);
        dig.start(2, 64, 2, 2_000L);
        assertEquals(1, dig.abandonedDigs());
        DiggingTracker.ActiveDig current = dig.current();
        assertNotNull(current);
        assertEquals(2_000L, current.getStartedAt());
    }

    @Test
    void digRestartSamePositionResetsTimer() {
        DiggingTracker dig = new DiggingTracker();
        dig.start(1, 64, 1, 1_000L);
        dig.start(1, 64, 1, 2_000L);
        assertEquals(0, dig.abandonedDigs());
        DiggingTracker.CompletedDig done = dig.complete(3_000L);
        assertNotNull(done);
        assertEquals(1_000L, done.getDurationMillis());
    }

    @Test
    void digToolChangeIsMarkedInconclusive() {
        DiggingTracker dig = new DiggingTracker();
        dig.start(5, 64, 5, 1_000L);
        dig.noteHeldItemChange();
        DiggingTracker.CompletedDig done = dig.complete(1_500L);
        assertNotNull(done);
        assertTrue(done.getDig().isToolChanged());
    }

    @Test
    void digFinishWithoutStartYieldsNothing() {
        DiggingTracker dig = new DiggingTracker();
        assertNull(dig.complete(1_000L));
        assertNull(dig.abort(1_000L));
    }
}
