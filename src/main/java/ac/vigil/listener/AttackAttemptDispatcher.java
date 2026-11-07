package ac.vigil.listener;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.logging.Logger;
import ac.vigil.data.PlayerData;
import ac.vigil.data.PlayerDataManager;
import ac.vigil.data.tracking.ReachEvidenceLedger;
import ac.vigil.detection.AttackAttemptSnapshot;
import ac.vigil.detection.AttackGeometryResolution;
import ac.vigil.detection.AttackGeometryResolver;
import ac.vigil.packet.AttackAttemptSink;

/**
 * Bounded hand-off between the netty packet path and the main server thread.
 *
 * <h2>Queue discipline</h2>
 * <ul>
 *   <li>Fixed capacity {@value #CAPACITY}; {@link #submit(AttackAttemptSnapshot)}
 *       is non-blocking and never touches Bukkit — it either enqueues or drops
 *       the incoming snapshot and increments the overflow counter. A full
 *       queue therefore <b>cannot</b> convict anyone; at worst some attempts
 *       are not individually resolved (visible via {@link #getOverflowCount()}).</li>
 *   <li>{@link #drainTick()} runs once per tick on the main thread (wired in
 *       {@code VigilAC#onEnable}) and resolves at most {@value #DRAIN_PER_TICK}
 *       snapshots per invocation, bounding main-thread cost to O(nearby entities)
 *       per resolved attempt.</li>
 *   <li>Every snapshot is resolved exactly once; stale/invalid entity
 *       references (entity removed, dead, in another world, or no bounding
 *       box) are recorded as {@code INVALID} confidence and carry no evidence
 *       weight.</li>
 * </ul>
 *
 * <h2>Ordering</h2>
 * The queue is FIFO and each snapshot carries a process-monotonic
 * {@code sequence}; the drain preserves packet order (the ledger additionally
 * counts out-of-order arrivals for diagnostics).
 *
 * <h2>Verdicts</h2>
 * This class never adds violation levels. Over-range rejected attempts are
 * written to the per-player monitor evidence log only.
 */
public final class AttackAttemptDispatcher implements AttackAttemptSink {

    /** Maximum pending attack attempts. 256 ≈ 13 s of 20 attempts/s across all players per tick. */
    public static final int CAPACITY = 256;
    private static final int DRAIN_PER_TICK = 64;
    /** Attempt↔damage pairing window (ms). Single source: {@link ReachEvidenceLedger#PAIR_WINDOW_MILLIS}. */
    public static final long PAIR_WINDOW_MILLIS = ReachEvidenceLedger.PAIR_WINDOW_MILLIS;
    /**
     * Attempts older than this without a paired hit are classified as rejected.
     * Strictly greater than {@link #PAIR_WINDOW_MILLIS}: rejection is final, so
     * classification must not happen while a damage could still pair.
     */
    public static final long REJECT_AFTER_MILLIS = PAIR_WINDOW_MILLIS + 100L;

    private final PlayerDataManager dataManager;
    private final AttackGeometryResolver resolver;
    private final Logger logger;
    /** Configured reach cap (+tolerance) used to classify rejected attempts as over-range evidence. */
    private final double defaultOverRangeCap;
    private final ArrayBlockingQueue<AttackAttemptSnapshot> queue = new ArrayBlockingQueue<>(CAPACITY);

    private volatile long submittedCount;
    private volatile long overflowCount;
    private volatile long resolvedCount;
    private volatile long invalidCount;
    private volatile long rejectedOverRangeCount;
    private int sweepTickCounter;

    public AttackAttemptDispatcher(PlayerDataManager dataManager,
                                   AttackGeometryResolver resolver,
                                   Logger logger) {
        this(dataManager, resolver, logger, 3.03D);
    }

    public AttackAttemptDispatcher(PlayerDataManager dataManager,
                                   AttackGeometryResolver resolver,
                                   Logger logger,
                                   double defaultOverRangeCap) {
        this.dataManager = dataManager;
        this.resolver = resolver;
        this.logger = logger;
        this.defaultOverRangeCap = defaultOverRangeCap;
    }

    @Override
    public void submit(AttackAttemptSnapshot snapshot) {
        submittedCount++;
        if (!queue.offer(snapshot)) {
            // Full: drop the NEW attempt, count it, never convict.
            long dropped = ++overflowCount;
            // One log line per 256 drops at most; this runs on the netty thread.
            if ((dropped & 0xFFL) == 1L) {
                try {
                    logger.warning("Attack-attempt hand-off queue is full; dropping attempts (total dropped=" + dropped + ")");
                } catch (Throwable ignored) {
                    // logging must never break the packet path
                }
            }
        }
    }

    /** Main-thread drain; call once per tick. Resolves at most {@value #DRAIN_PER_TICK} attempts. */
    public void drainTick() {
        int budget = DRAIN_PER_TICK;
        AttackAttemptSnapshot snapshot;
        while (budget-- > 0 && (snapshot = queue.poll()) != null) {
            resolveOne(snapshot);
        }
        // Rejection sweep is O(tracked players x ledger size); once per second is plenty.
        if (++sweepTickCounter >= 20) {
            sweepTickCounter = 0;
            sweep();
        }
    }

    private void resolveOne(AttackAttemptSnapshot snapshot) {
        PlayerData data = dataManager.get(snapshot.getAttacker());
        if (data == null) {
            invalidCount++; // no per-player state — nothing to convict, nothing to store
            return;
        }
        AttackGeometryResolution result = resolver.resolve(snapshot.getAttacker(), snapshot.getEntityId());
        if (result == null || !result.isFound()) {
            data.reachEvidenceLedger().noteInvalid(snapshot.getTimestamp(), snapshot.getEntityId(),
                    snapshot.getSequence(), result == null ? "AIR" : result.getItemContext());
            invalidCount++;
            return;
        }
        data.reachEvidenceLedger().noteResolved(snapshot.getTimestamp(), snapshot.getEntityId(),
                snapshot.getSequence(), result.distanceEyeToBox(),
                result.getAttackRangeMax(), result.getRangeSource(),
                result.getItemContext(), result.getTargetType());
        resolvedCount++;
    }

    private void sweep() {
        // Paired hits are marked damage-matched (markDamaged) and retroactively
        // paired at resolution time (noteResolved) with damage processed before
        // a deferred resolution — a landed hit is never classified rejected.
        // Entries classified here provably outlived their pair window. Only
        // over-range ones surface as monitor evidence.
        long now = System.currentTimeMillis();
        for (PlayerData data : dataManager.getAll()) {
            if (data == null) {
                continue;
            }
            List<ReachEvidenceLedger.Entry> overRange =
                    data.reachEvidenceLedger().sweepRejected(now, REJECT_AFTER_MILLIS, defaultOverRangeCap);
            for (ReachEvidenceLedger.Entry entry : overRange) {
                rejectedOverRangeCount++;
                data.monitor().noteCheckEvidence("reach-attempt",
                        String.format(java.util.Locale.US,
                                "rejected distance=%.3f range=%s source=%s item=%s target=%s entity=%d",
                                entry.getDistanceEyeToBox(),
                                entry.getEffectiveRange() == null ? "3.03(default)" : String.format(java.util.Locale.US, "%.2f+0.03", entry.getEffectiveRange()),
                                entry.getRangeSource(), entry.getItemContext(),
                                entry.getTargetType(), entry.getEntityId()));
            }
        }
    }

    /** Pairs a successful damage event with a recent attempt; called from the damage listener. */
    public void noteDamage(PlayerData data, int targetEntityId, long now) {
        if (data == null) {
            return;
        }
        data.reachEvidenceLedger().markDamaged(targetEntityId, now, PAIR_WINDOW_MILLIS);
    }

    public long getSubmittedCount() { return submittedCount; }
    public long getOverflowCount() { return overflowCount; }
    public long getResolvedCount() { return resolvedCount; }
    public long getInvalidCount() { return invalidCount; }
    public long getRejectedOverRangeCount() { return rejectedOverRangeCount; }
    public int getPendingCount() { return queue.size(); }
}
