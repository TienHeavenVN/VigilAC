package ac.vigil.capture;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CaptureBufferTest {

    private static CaptureRecord rec(String scenario) {
        double[] features = new double[22];
        features[0] = 1.0D;
        return new CaptureRecord("a".repeat(32), "b".repeat(32), "1.21.8", "VANILLA", "MOUSE",
                20.0D, 31.0D, "hash", "LEGITIMATE", "SERVER_TELEMETRY_CURATION", 0.9D,
                java.time.Instant.ofEpochSecond(1_762_500_000L), scenario, features, "note");
    }

    @Test
    void dropsNewestWhenFullAndCountsDrops() {
        CaptureBuffer buffer = new CaptureBuffer(2);
        assertTrue(buffer.offer(rec("one")));
        assertTrue(buffer.offer(rec("two")));
        assertFalse(buffer.offer(rec("three")));
        assertFalse(buffer.offer(rec("four")));
        assertEquals(4, buffer.submitted());
        assertEquals(2, buffer.droppedFull());
        assertEquals("one", buffer.poll().scenario);
        assertEquals("two", buffer.poll().scenario);
        assertNull(buffer.poll());
        assertEquals(2, buffer.drained());
    }

    @Test
    void capacityOneWorks() {
        CaptureBuffer buffer = new CaptureBuffer(1);
        assertTrue(buffer.offer(rec("only")));
        assertFalse(buffer.offer(rec("rejected")));
        assertEquals(1, buffer.droppedFull());
    }

    @Test
    void capacityBelowOneIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new CaptureBuffer(0));
    }
}
