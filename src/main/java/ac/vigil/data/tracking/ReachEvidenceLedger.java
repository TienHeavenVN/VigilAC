package ac.vigil.data.tracking;

import java.util.ArrayDeque;
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
 *       attempt↔hit ordering can be inspected. Pairing works in <b>both
 *       orders</b>: a damage processed before the attempt's resolution is
 *       retained in a bounded ring of recent damage marks and paired
 *       retroactively inside {@link #noteResolved} — a deferred resolution
 *       can never leave a landed hit unpaired.</li>
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
 * offline, other world, or the entity itself is no longer valid — no geometry, no evidence).
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

    /**
     * Attempt↔damage pairing window: an attack packet and its damage event
     * are treated as the same attack within this window. This is the
     * authoritative value; listeners must use it (no duplicated literals).
     */
    public static final long PAIR_WINDOW_MILLIS = 400L;

    private static final int CAPACITY = 64;
    private static final long RETAIN_MILLIS = 10_000L;
    /** Bound on retained unpaired damage marks (one per landed hit). */
    private static final int DAMAGE_CAPACITY = 64;
    /** A damage mark older than this can never pair; it is dropped (unpaired marks never fabricate evidence). */
    private static final long DAMAGE_RETAIN_MILLIS = PAIR_WINDOW_MILLIS + 1_000L;

    /** One unpaired damage event, retained for retroactive pairing at resolution time. */
    private static final class DamageMark {
        private final int entityId;
        private final long atMillis;

        private DamageMark(int entityId, long atMillis) {
            this.entityId = entityId;
            this.atMillis = atMillis;
        }
    }

    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    private final ArrayDeque<DamageMark> recentDamages = new ArrayDeque<>();
    private long noteCount;
    private long resolvedCount;
    private long invalidCount;
    private long overflowDropCount;
    private long damagedCount;
    private long rejectedCount;
    private long overRangeRejectedCount;
    private long lastSequence = -1L;
    private long outOfOrderCount;
    private long damageMarkDropCount;

    /**
     * Records a RESOLVED attempt (geometry measured). After the entry is
     * stored, a retroactive pairing is attempted against the ring of recent
     * unpaired damage marks: when the damage for this attack was processed
     * <em>before</em> the (deferred) resolution — the normal case when the
     * server emits the damage event in the same tick as the attack packet —
     * the attempt is marked damaged immediately instead of being left to be
     * swept as rejected evidence for a landed hit.
     */
    public synchronized void noteResolved(long timestamp, int entityId, long sequence,
                                          double distanceEyeToBox, Double effectiveRange,
                                          String rangeSource, String itemContext, String targetType) {
        Entry entry = new Entry(timestamp, entityId, sequence, distanceEyeToBox,
                effectiveRange, rangeSource, itemContext, targetType, Confidence.RESOLVED);
        add(entry, sequence);
        resolvedCount++;
        pairWithRecentDamage(entry);
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
        while (!recentDamages.isEmpty() && now - recentDamages.peekFirst().atMillis > DAMAGE_RETAIN_MILLIS) {
            recentDamages.pollFirst();
            damageMarkDropCount++;
        }
    }

    /**
     * Pairs a freshly resolved entry with the most recent unpaired damage
     * mark for the same entity inside the pair window. Consumes the mark.
     */
    private void pairWithRecentDamage(Entry entry) {
        for (java.util.Iterator<DamageMark> it = recentDamages.descendingIterator(); it.hasNext(); ) {
            DamageMark mark = it.next();
            if (mark.entityId == entry.getEntityId()
                    && Math.abs(entry.getTimestamp() - mark.atMillis) <= PAIR_WINDOW_MILLIS) {
                it.remove();
                entry.markDamaged();
                damagedCount++;
                return;
            }
        }
    }

    /**
     * Records a successful damage event for {@code entityId}. Pairs with the
     * most recent unmatched RESOLVED entry for the same entity within
     * {@code windowMillis} when one is already stored (newest first); when no
     * entry has been resolved yet — the damage can land before the attempt is
     * resolved (deferred resolution) — the mark is <b>retained</b> in a
     * bounded ring and paired retroactively when the resolution lands inside
     * {@link #noteResolved}. A landed hit therefore never goes unpaired.
     *
     * @return true when an immediate pairing was made; false when the damage
     *         was retained for deferred pairing (or nothing matched). Retained
     *         marks expire after the pair window plus a margin and are bounded
     *         in size; they never fabricate evidence.
     */
    public synchronized boolean markDamaged(int entityId, long now, long windowMillis) {
        prune(now);
        long effectiveWindow = Math.min(Math.max(windowMillis, 0L), PAIR_WINDOW_MILLIS);
        for (Entry entry : entries) {
            if (entry.getConfidence() != Confidence.RESOLVED || entry.isDamaged() || entry.isRejected()) {
                continue;
            }
            if (entry.getEntityId() != entityId) {
                continue;
            }
            if (Math.abs(now - entry.getTimestamp()) > effectiveWindow) {
                continue;
            }
            entry.markDamaged();
            damagedCount++;
            return true;
        }
        recentDamages.addLast(new DamageMark(entityId, now));
        while (recentDamages.size() > DAMAGE_CAPACITY) {
            recentDamages.pollFirst();
            damageMarkDropCount++;
        }
        return false;
    }

    /**
     * Sweeps entries older than {@code rejectAfterMillis} that never paired
     * with a damage event; marks them rejected (once) and returns the over-range
     * ones (distance beyond {@code overRangeCap} — item-agnostic configured cap)
     * for monitor evidence. INVALID entries are never returned; unreachable
     * attacks produce no reach evidence.
     *
     * <p>Callers must pass a {@code rejectAfterMillis} strictly greater than
     * the pair window (see {@code AttackAttemptDispatcher#REJECT_AFTER_MILLIS}):
     * rejection is final, so classifying an entry before its pair window
     * expires could record a landed hit as rejected evidence.</p>
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
    /** Unpaired damage marks dropped (capacity overflow or retention expiry). Never evidence. */
    public synchronized long getDamageMarkDropCount() { return damageMarkDropCount; }
    /** Unpaired damage marks currently retained for deferred pairing (diagnostics/tests). */
    public synchronized int getRecentDamageMarkCount() { return recentDamages.size(); }
}
