package ac.vigil.data.tracking;

import java.util.ArrayDeque;

/**
 * Bounded record of melee attack <em>attempts</em> (client packets: 1.21.x
 * {@code INTERACT_ENTITY/ATTACK}, 26.1+ {@code ATTACK}), independent of
 * whether the attack landed. Fed by the packet telemetry path when
 * packetevents is installed; empty otherwise (checks must degrade, never
 * crash).
 *
 * <p>All methods are synchronized O(1) amortized. Capacity is fixed, so
 * memory is bounded regardless of packet rate.</p>
 */
public final class AttackAttemptLedger {

    public static final int CAPACITY = 64;

    private final ArrayDeque<Long> attemptTimes = new ArrayDeque<>();
    private long lastAttemptAt;
    private long lastEntityId = -1L;

    /** Records an attack attempt against {@code entityId} at {@code now}. */
    public synchronized void noteAttempt(long now, int entityId) {
        attemptTimes.addLast(now);
        if (attemptTimes.size() > CAPACITY) {
            attemptTimes.pollFirst();
        }
        this.lastAttemptAt = now;
        this.lastEntityId = entityId;
    }

    /** Number of attempts recorded in the trailing {@code windowMillis}. */
    public synchronized int attemptsSince(long now, long windowMillis) {
        evict(now, windowMillis);
        return attemptTimes.size();
    }

    /** Attempts between {@code fromExclusive} and {@code toInclusive} (both ms). */
    public synchronized int attemptsBetween(long fromExclusive, long toInclusive) {
        int count = 0;
        for (Long t : attemptTimes) {
            if (t > fromExclusive && t <= toInclusive) {
                count++;
            }
        }
        return count;
    }

    public synchronized long lastAttemptAt() {
        return lastAttemptAt;
    }

    public synchronized int lastEntityId() {
        return (int) lastEntityId;
    }

    /** Attempts per second over the trailing window. */
    public synchronized double attemptRate(long now, long windowMillis) {
        evict(now, windowMillis);
        if (windowMillis <= 0L) {
            return 0.0D;
        }
        return attemptTimes.size() * 1000.0D / (double) windowMillis;
    }

    public synchronized void clear() {
        attemptTimes.clear();
        lastAttemptAt = 0L;
        lastEntityId = -1L;
    }

    private void evict(long now, long windowMillis) {
        long cutoff = now - windowMillis;
        while (!attemptTimes.isEmpty() && attemptTimes.peekFirst() < cutoff) {
            attemptTimes.pollFirst();
        }
    }
}
