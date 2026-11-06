package ac.vigil.data.tracking;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MovementTrackerTest {

    @Test
    void rawRingCountsOnlyTrailingSecond() {
        MovementTracker tracker = new MovementTracker();
        long now = 1_000_000L;
        for (int i = 0; i < 30; i++) {
            tracker.noteRawMovePacket(now);
            now += 20L;
        }
        assertEquals(30.0D, tracker.rawMovePacketsPerSecond(now), 0.001D,
                "30 packets inside the trailing second must count fully");
        long later = now - 60_000L;
        assertEquals(0.0D, tracker.rawMovePacketsPerSecond(later), 0.001D,
                "packets older than the trailing second must not count");
    }

    @Test
    void rawRingIndependentOfEventRing() {
        MovementTracker tracker = new MovementTracker();
        long now = 1_000_000L;
        for (int i = 0; i < 25; i++) {
            tracker.noteRawMovePacket(now);
            tracker.noteMovePacket(now);
            now += 20L;
        }
        for (int i = 0; i < 3; i++) { // extra events without raw packets
            tracker.noteMovePacket(now);
            now += 20L;
        }
        assertEquals(25.0D, tracker.rawMovePacketsPerSecond(now), 0.001D);
        assertEquals(28.0D, tracker.movePacketsPerSecond(now), 0.001D);
    }

    @Test
    void maxPicksRawOverEventRate() {
        // Mirrors PlayerMoveListener's Math.max(eventRate, rawRate): when the
        // server coalesces move events to 20/s but PE sees 25 raw packets/s,
        // the timer evidence path must see 25/s.
        MovementTracker tracker = new MovementTracker();
        long now = 1_000_000L;
        for (int i = 0; i < 20; i++) {
            tracker.noteMovePacket(now + i * 50L);
        }
        for (int i = 0; i < 25; i++) {
            tracker.noteRawMovePacket(now + i * 40L);
        }
        long sampleAt = now + 1_000L;
        double effective = Math.max(tracker.movePacketsPerSecond(sampleAt), tracker.rawMovePacketsPerSecond(sampleAt));
        assertTrue(effective >= 25.0D, "effective rate must reflect the raw packet stream, got " + effective);
    }
}
