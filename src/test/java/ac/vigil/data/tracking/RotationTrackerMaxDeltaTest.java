package ac.vigil.data.tracking;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The aim-snap metric must isolate the single largest rotation burst in the
 * window — never an accumulation over smooth tracking.
 */
class RotationTrackerMaxDeltaTest {

    private RotationTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new RotationTracker();
    }

    @Test
    void flickAndReturnDoesNotAccumulate() {
        long now = 1_000_000L;
        tracker.sample(now, 0.0F, 0.0F);
        tracker.sample(now + 50, 80.0F, 0.0F);   // 80° burst
        tracker.sample(now + 100, 0.0F, 0.0F);   // 80° back — same burst magnitude, not additive
        double covered = tracker.angularDistanceCovered(now + 100, 200L);
        double maxBurst = tracker.maxDeltaInWindow(now + 100, 200L);
        assertEquals(160.0D, covered, 0.5D, "total motion accumulates (diagnostic metric)");
        assertEquals(80.0D, maxBurst, 0.5D, "the snap metric isolates ONE burst");
    }

    @Test
    void smoothTrackingHasNoBurst() {
        long now = 1_000_000L;
        float yaw = 90.0F;
        for (int i = 0; i < 10; i++) {
            tracker.sample(now, yaw, 0.0F);
            yaw -= 4.5F; // 4.5° per step - smooth correction
            now += 100;
        }
        assertTrue(tracker.maxDeltaInWindow(now, 1_200L) < 5.0D);
        assertTrue(tracker.angularDistanceCovered(now, 1_200L) > 40.0D);
    }

    @Test
    void emptyWhenFewerThanTwoSamples() {
        long now = 1_000_000L;
        assertEquals(0.0D, tracker.maxDeltaInWindow(now, 200L));
        tracker.sample(now, 10.0F, 0.0F);
        assertEquals(0.0D, tracker.maxDeltaInWindow(now, 200L));
    }

    @Test
    void pairsOutsideTheWindowDoNotInflateTheBurst() {
        long now = 1_000_000L;
        tracker.sample(now, 0.0F, 0.0F);
        tracker.sample(now + 50, 120.0F, 0.0F); // ancient 120° burst, 5 s ago
        long later = now + 5_000L;
        tracker.sample(later, 121.0F, 0.0F);
        tracker.sample(later + 50, 123.5F, 0.0F);
        double maxBurst = tracker.maxDeltaInWindow(later + 50, 200L);
        assertEquals(2.5D, maxBurst, 0.5D, "only the trailing 200 ms counts");
    }
}
