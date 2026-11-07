package ac.vigil.data.tracking;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-player reach evidence reconstructed from packet-layer attack attempts
 * resolved on the main thread. Pure Java, synchronized, fixed capacity —
 * safe to call from the move/attack path and from the drain task.
 *
 * <h2>What this ledger is for</h2>
 * <ul>
 *   <li>Makes <b>rejected</b> attack attempts (packet sent, server refused)
 *       visible to the monitor — the damage-event stream never sees them.</li>
 *   <li>Pairs attempt packets with successful damage events (markDamaged) so
 *       attempt↔hit ordering can be inspected.</li>
 *   <li>Carries measured distance, the effective range in force (item-specific
 *       when known, otherwise "unknown"), item context and confidence — the
 *       fields required to audit an alert after the fact.</li>
 * </ul>
 *
 * <h2>What this ledger is NOT for</h2>
 * It never produces a violation level. An over-range rejected attempt is
 * monitor evidence, not a conviction: without a damage correlation, a miss
 * proves nothing about reach intent. Confidence is only {@code RESOLVED}
 * (entity geometry measured) or {@code INVALID} (entity gone, attacker
 * offline, other world — no geometry, no evidence).
 */
public final class ReachEvidenceLedger {

    public enum Confidence {
        /** Geometry was resolved on the main thread against a real bounding box. */
        RESOLVED,
        /** Geometry could not be resolved; entry carries no usable evidence. */
        INVALID
    }

    /** One resolved attack attempt. */
    public static final class Entry {
        private final long timestamp;
        private final int entityId;
        private final long sequence;
        private final double distanceEyeToBox;
        private final Double effectiveRange;   // null = unknown component (configured cap applies)
        private final String rangeSource;      // component | unknown
        private final String itemContext;      // held item material name
        private final String targetType;
        private final Confidence confidence;
        private boolean damaged;               // a successful damage event paired with this attempt
        private boolean rejected;              // swept as rejected (no damage within the pairing window)

        private Entry(long timestamp, int entityId, long sequence, double distanceEyeToBox,
                      Double effectiveRange, String rangeSource, String itemContext,
                      String targetType, Confidence confidence) {
            this.timestamp = timestamp;
            this.entityId = entityId;
            this.sequence = sequence;
            this.distanceEyeToBox = distanceEyeToBox;
            this.effectiveRange = effectiveRange;
            this.rangeSource = rangeSource;
            this.itemContext = itemContext;
            this.targetType = targetType;
            this.confidence = confidence;
        }

        public long getTimestamp() { return timestamp; }
        public int getEntityId() { return entityId; }
        public long getSequence() { return sequence; }
        public double getDistanceEyeToBox() { return distanceEyeToBox; }
        /** Item-specific range when a reliable component read succeeded, else null. */
        public Double getEffectiveRange() { return effectiveRange; }
        public String getRangeSource() { return rangeSource; }
        public String getItemContext() { return itemContext; }
        public String getTargetType() { return targetType; }
        public Confidence getConfidence() { return confidence; }
        public boolean isDamaged() { return damaged; }
        public boolean isRejected() { return rejected; }

        private void markDamaged() { this.damaged = true; }
        private void markRejected() { this.rejected = true; }
    }

    private static final int CAPACITY = 64;
    private static final long RETAIN_MILLIS = 10_000L;

    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    private long noteCount;
    private long resolvedCount;
    private long invalidCount;
    private long overflowDropCount;
    private long damagedCount;
    private long rejectedCount;
    private long overRangeRejectedCount;
    private long lastSequence = -1L;
    private long outOfOrderCount;

    /** Records a RESOLVED attempt (geometry measured). */
    public synchronized void noteResolved(long timestamp, int entityId, long sequence,
                                          double distanceEyeToBox, Double effectiveRange,
                                          String rangeSource, String itemContext, String targetType) {
        Entry entry = new Entry(timestamp, entityId, sequence, distanceEyeToBox,
                effectiveRange, rangeSource, itemContext, targetType, Confidence.RESOLVED);
        add(entry, sequence);
        resolvedCount++;
    }

    /** Records an INVALID attempt (no geometry; carries zero evidence value). */
    public synchronized void noteInvalid(long timestamp, int entityId, long sequence, String itemContext) {
        Entry entry = new Entry(timestamp, entityId, sequence, Double.NaN,
                null, "unknown", itemContext, "unknown", Confidence.INVALID);
        add(entry, sequence);
        invalidCount++;
    }

    private void add(Entry entry, long sequence) {
        if (lastSequence >= 0L && sequence < lastSequence) {
            outOfOrderCount++;
        }
        lastSequence = Math.max(lastSequence, sequence);
        entries.addFirst(entry);
        while (entries.size() > CAPACITY) {
            entries.pollLast();
            overflowDropCount++;
        }
        prune(entry.getTimestamp());
        noteCount++;
    }

    private void prune(long now) {
        while (!entries.isEmpty() && now - entries.peekLast().getTimestamp() > RETAIN_MILLIS) {
            entries.pollLast();
        }
    }

    /**
     * Pairs a successful damage event with the most recent unmatched RESOLVED
     * entry for the same entity within {@code windowMillis}. Returns true when
     * a pairing was made.
     */
    public synchronized boolean markDamaged(int entityId, long now, long windowMillis) {
        prune(now);
        for (Entry entry : entries) {
            if (entry.getConfidence() != Confidence.RESOLVED || entry.isDamaged() || entry.isRejected()) {
                continue;
            }
            if (entry.getEntityId() != entityId) {
                continue;
            }
            if (now - entry.getTimestamp() > windowMillis) {
                continue;
            }
            entry.markDamaged();
            damagedCount++;
            return true;
        }
        return false;
    }

    /**
     * Sweeps entries older than {@code rejectAfterMillis} that never paired
     * with a damage event; marks them rejected (once) and returns the over-range
     * ones (distance beyond {@code overRangeCap} — item-agnostic configured cap)
     * for monitor evidence. INVALID entries are never returned; unreachable
     * attacks produce no reach evidence.
     */
    public synchronized List<Entry> sweepRejected(long now, long rejectAfterMillis, double overRangeCap) {
        prune(now);
        List<Entry> overRange = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.isRejected() || entry.isDamaged()) {
                continue;
            }
            if (entry.getConfidence() != Confidence.RESOLVED) {
                continue;
            }
            if (now - entry.getTimestamp() < rejectAfterMillis) {
                continue;
            }
            entry.markRejected();
            rejectedCount++;
            Double cap = entry.getEffectiveRange() != null ? entry.getEffectiveRange() + 0.03D : overRangeCap;
            if (entry.getDistanceEyeToBox() > cap) {
                overRange.add(entry);
                overRangeRejectedCount++;
            }
        }
        return overRange;
    }

    public synchronized int size() { return entries.size(); }
    public synchronized long getNoteCount() { return noteCount; }
    public synchronized long getResolvedCount() { return resolvedCount; }
    public synchronized long getInvalidCount() { return invalidCount; }
    public synchronized long getOverflowDropCount() { return overflowDropCount; }
    public synchronized long getDamagedCount() { return damagedCount; }
    public synchronized long getRejectedCount() { return rejectedCount; }
    public synchronized long getOverRangeRejectedCount() { return overRangeRejectedCount; }
    public synchronized long getOutOfOrderCount() { return outOfOrderCount; }
}
