package ac.vigil.data.tracking;

import java.util.ArrayDeque;

/**
 * Per-player block interaction evidence accumulator, owned by
 * {@code PlayerData}. Pure Java (no Bukkit) so every path is unit-testable.
 * All public methods are synchronized; block events fire on the main thread.
 */
public final class BlockActionTracker {

    private long lastBreakAt;
    private int impossibleBreakStreak;
    private int outOfViewPlaceStreak;
    private final ArrayDeque<Long> placeTimes = new ArrayDeque<>();

    /** Records a completed break at {@code now}. */
    public synchronized void noteBreak(long now) {
        this.lastBreakAt = now;
    }

    /** Epoch ms of the previous recorded break, or 0 if none is on record. */
    public synchronized long getLastBreakAt() {
        return lastBreakAt;
    }

    /**
     * Interval between the previous and current break. {@code Long.MAX_VALUE}
     * when no previous break is on record (nothing to compare against).
     */
    public synchronized long breakInterval(long now) {
        return lastBreakAt <= 0L ? Long.MAX_VALUE : Math.max(0L, now - lastBreakAt);
    }

    public synchronized int addImpossibleBreakStreak() {
        return ++impossibleBreakStreak;
    }

    public synchronized void resetImpossibleBreakStreak() {
        impossibleBreakStreak = 0;
    }

    public synchronized int getImpossibleBreakStreak() {
        return impossibleBreakStreak;
    }

    public synchronized int addOutOfViewPlaceStreak() {
        return ++outOfViewPlaceStreak;
    }

    public synchronized void resetOutOfViewPlaceStreak() {
        outOfViewPlaceStreak = 0;
    }

    public synchronized int getOutOfViewPlaceStreak() {
        return outOfViewPlaceStreak;
    }

    /** Records a placement at {@code now}; returns the number of placements in the trailing window. */
    public synchronized int recordPlace(long now, long windowMillis) {
        placeTimes.addLast(now);
        long cutoff = now - windowMillis;
        while (!placeTimes.isEmpty() && placeTimes.peekFirst() < cutoff) {
            placeTimes.pollFirst();
        }
        return placeTimes.size();
    }
}
