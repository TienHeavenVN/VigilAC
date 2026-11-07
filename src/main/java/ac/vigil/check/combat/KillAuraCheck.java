package ac.vigil.check.combat;

import ac.vigil.check.Check;
import ac.vigil.check.CheckCategory;
import ac.vigil.check.ViolationCallback;
import ac.vigil.data.PlayerData;
import ac.vigil.data.tracking.CombatTracker;
import ac.vigil.detection.AttackContext;

/**
 * Detects kill-aura behaviour from independent combat signals.
 *
 * <h2>Algorithm (v2, Phase 6)</h2>
 * <p>The pre-v2 check flagged on "3 distinct damaged targets in 1 s" alone.
 * That is a single signal: two cleaving players and a mob farm look identical,
 * and the spec forbids that verdict. v2 requires an <b>anchor</b> plus
 * corroboration from <b>independent</b> streams:</p>
 * <ol>
 *   <li><b>Anchor — distinct targets</b>: distinct damaged entity ids within
 *       {@code windowMillis} ≥ {@code minTargets}. (Damage stream.)</li>
 *   <li><b>Regularity</b>: attack-interval coefficient of regularity — a full
 *       interval window with stddev &lt; {@code metronomicStddevMax} ms and at
 *       least {@code metronomicMinSamples} samples. (Damage stream.)</li>
 *   <li><b>Attempt rate</b>: packetevents-observed attack packets per second
 *       (attempt rate &ge; {@code minAttemptRate}) — includes misses, which the
 *       damage stream never sees. Absent packetevents, this signal is simply
 *       unavailable (contributes 0, never fabricates).</li>
 *   <li><b>Swingless attacks</b>: packetevents observed attempts outnumbering
 *       arm swings by 2:1 over the window — an attack packet with no swing is a
       client-injected attack. (Requires &ge; 6 attempts.)</li>
 * </ol>
 * VL accrues only when the anchor holds AND the total signal count reaches
 * {@code minSignals} (default 2). Amount scales with the number of signals.
 *
 * <h2>False-positive considerations</h2>
 * <ul>
 *   <li>Creative/spectator and vehicles are exempt.</li>
 *   <li>A mob farm produces the anchor and regularity (stray cadence), but
 *       human farm clicks are irreducibly noisy — stddev rarely sits under
 *       15 ms for 6+ consecutive intervals, and the attempt-rate gate (8/s) is
 *       above normal farming cadence. Treat alerts as evidence, not proof;
 *       never auto-ban from this check alone.</li>
 *   <li>Regularity alone (single target) can never flag — the anchor is
 *       mandatory.</li>
 * </ul>
 */
public final class KillAuraCheck implements Check<AttackContext> {

    private final long windowMillis;
    private final int minTargets;
    private final double vlPerTrigger;
    private final double minAttemptRate;
    private final double metronomicStddevMax;
    private final int metronomicMinSamples;
    private final int minSignals;

    public KillAuraCheck(long windowMillis, int minTargets, double vlPerTrigger) {
        this(windowMillis, minTargets, vlPerTrigger, 8.0D, 15.0D, 6, 2);
    }

    public KillAuraCheck(long windowMillis, int minTargets, double vlPerTrigger,
                         double minAttemptRate, double metronomicStddevMax,
                         int metronomicMinSamples, int minSignals) {
        this.windowMillis = windowMillis;
        this.minTargets = minTargets;
        this.vlPerTrigger = vlPerTrigger;
        this.minAttemptRate = minAttemptRate;
        this.metronomicStddevMax = metronomicStddevMax;
        this.metronomicMinSamples = metronomicMinSamples;
        this.minSignals = minSignals;
    }

    @Override
    public String getName() {
        return "kill-aura";
    }

    @Override
    public CheckCategory getCategory() {
        return CheckCategory.COMBAT;
    }

    @Override
    public void evaluate(AttackContext ctx, PlayerData data, ViolationCallback callback) {
        if (ctx.isCreativeOrSpectator() || ctx.isAttackerInVehicle()) {
            return;
        }

        CombatTracker tracker = data.combatTracker();
        long now = ctx.getTimestamp();
        int distinct = tracker.recordTarget(ctx.getTargetUniqueId(), now, (int) Math.min(Integer.MAX_VALUE, windowMillis));

        boolean anchor = distinct >= minTargets;

        // Signal 1 (anchor).
        int signals = anchor ? 1 : 0;

        // Signal 2: metronomic cadence over the damage stream.
        int samples = tracker.getIntervalSampleCount();
        double stddev = tracker.intervalStddev();
        double mean = tracker.intervalMean();
        boolean metronomic = samples >= metronomicMinSamples
                && !Double.isNaN(stddev) && !Double.isNaN(mean)
                && stddev < metronomicStddevMax && mean > 0.0D && mean < 400.0D;
        if (metronomic) {
            signals++;
        }

        // Signal 3: attack-attempt rate on the packet path (PE optional).
        double attemptRate = data.attackAttemptLedger().attemptRate(now, 1_000L);
        boolean highCadence = attemptRate >= minAttemptRate;
        if (highCadence) {
            signals++;
        }

        // Signal 4: attempts without swings (packet-injected attacks).
        int attempts = data.attackAttemptLedger().attemptsSince(now, 1_000L);
        int swings = data.swingTracker().swingsSince(now, 1_000L);
        boolean swingless = attempts >= 6 && swings * 2L < attempts;
        if (swingless) {
            signals++;
        }

        if (!anchor || signals < minSignals) {
            return;
        }

        double amount = vlPerTrigger * Math.min(2.0D, 1.0D + 0.5D * Math.max(0, signals - 2));
        String debug = String.format(java.util.Locale.US,
                "distinctTargets=%d window=%dms metronomic=%b attempts=%.1f/s swingless=%b signals=%d/4 ping=%d",
                distinct, windowMillis, metronomic, attemptRate, swingless, signals, ctx.getPingMillis());
        callback.onViolation(this, amount, debug);
    }
}
