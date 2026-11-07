package ac.vigil.data.tracking;

import java.util.ArrayDeque;

/**
 * Bounded record of client arm swings ({@code ServerboundSwingPacket}, and
 * 26.3+ punch packets). Used to separate click cadence from attack outcomes:
 * a high swing rate with low hit rate is a normal combat signature, a high
 * swing rate alone is not evidence.
 *
 * <p>Synchronized O(1) amortized; fixed capacity.</p>
 */
public final class SwingTracker {

    public static final int CAPACITY = 64;

    private final ArrayDeque<Long> swingTimes = new ArrayDeque<>();
    private long lastSwingAt;

    public synchronized void noteSwing(long now) {
        swingTimes.addLast(now);
        if (swingTimes.size() > CAPACITY) {
            swingTimes.pollFirst();
        }
        lastSwingAt = now;
    }

    public synchronized int swingsSince(long now, long windowMillis) {
        evict(now, windowMillis);
        return swingTimes.size();
    }

    public synchronized double swingRate(long now, long windowMillis) {
        evict(now, windowMillis);
        if (windowMillis <= 0L) {
            return 0.0D;
        }
        return swingTimes.size() * 1000.0D / (double) windowMillis;
    }

    public synchronized long lastSwingAt() {
        return lastSwingAt;
    }

    public synchronized void clear() {
        swingTimes.clear();
        lastSwingAt = 0L;
    }

    private void evict(long now, long windowMillis) {
        long cutoff = now - windowMillis;
        while (!swingTimes.isEmpty() && swingTimes.peekFirst() < cutoff) {
            swingTimes.pollFirst();
        }
    }
}
