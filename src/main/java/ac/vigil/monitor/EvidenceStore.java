package ac.vigil.monitor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Bounded per-player evidence ring. All methods are synchronized; callers are
 * the main thread (events) and, rarely, the decay/query path.
 *
 * <h2>Bounds and cost</h2>
 * <ul>
 *   <li>Capacity: {@code capacity} most recent entries (default 256).</li>
 *   <li>TTL: entries older than {@code ttlMillis} are evicted on write and on
 *       query time ({@code countByTypeSince}/{@code snapshot}).</li>
 *   <li>{@link #add} is O(1) amortized (eviction is bounded by capacity).</li>
 *   <li>Queries scan at most {@code capacity} entries — never unbounded.</li>
 * </ul>
 */
public final class EvidenceStore {

    public static final int DEFAULT_CAPACITY = 256;
    public static final long DEFAULT_TTL_MILLIS = 90_000L;

    private final int capacity;
    private final long ttlMillis;
    private final java.util.function.LongSupplier clock;
    private final ArrayDeque<Evidence> ring = new ArrayDeque<>();

    public EvidenceStore() {
        this(DEFAULT_CAPACITY, DEFAULT_TTL_MILLIS);
    }

    public EvidenceStore(int capacity, long ttlMillis) {
        this(capacity, ttlMillis, System::currentTimeMillis);
    }

    /** Test-visible constructor: every internal timestamp derives from {@code clock}. */
    public EvidenceStore(int capacity, long ttlMillis, java.util.function.LongSupplier clock) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1");
        }
        this.capacity = capacity;
        this.ttlMillis = ttlMillis;
        this.clock = clock;
    }

    /** Adds one evidence entry; evicts expired/overflown entries. O(1) amortized. */
    public synchronized void add(Evidence evidence) {
        evict(evidence.timeMillis());
        while (ring.size() >= capacity) {
            ring.pollFirst();
        }
        ring.addLast(evidence);
    }

    /** Convenience factory + add. */
    public synchronized void add(long now, EvidenceType type, double confidence, String source, String detail) {
        add(new Evidence(now, type, confidence, source, detail));
    }

    /** Number of entries of {@code type} recorded at/after {@code sinceMillis} (expired entries excluded). */
    public synchronized int countByTypeSince(EvidenceType type, long sinceMillis) {
        int count = 0;
        long cutoff = Math.max(sinceMillis, clock.getAsLong() - ttlMillis);
        for (Evidence e : ring) {
            if (e.timeMillis() >= cutoff && e.type() == type) {
                count++;
            }
        }
        return count;
    }

    /** Most recent entry of {@code type}, or null if none (or all expired). */
    public synchronized Evidence latestOfType(EvidenceType type) {
        evict(clock.getAsLong());
        Evidence result = null;
        for (Evidence e : ring) {
            if (e.type() == type) {
                result = e; // ring is chronological: last match wins
            }
        }
        return result;
    }

    /** Chronological snapshot of unexpired entries (newest last). Bounded by capacity. */
    public synchronized List<Evidence> snapshot() {
        evict(clock.getAsLong());
        return new ArrayList<>(ring);
    }

    /** Current unexpired entry count (bounded by capacity). */
    public synchronized int size() {
        evict(clock.getAsLong());
        return ring.size();
    }

    /** Removes entries older than the TTL relative to {@code now}. */
    private void evict(long now) {
        long cutoff = now - ttlMillis;
        while (!ring.isEmpty() && ring.peekFirst().timeMillis() < cutoff) {
            ring.pollFirst();
        }
    }
}
