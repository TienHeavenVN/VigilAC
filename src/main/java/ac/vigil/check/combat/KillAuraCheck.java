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
 * <h2>Algorithm (v3, Phase 7)</h2>
 * <p>The pre-v2 check flagged on "3 distinct damaged targets in 1 s" alone.
 * That is a single signal: two cleaving players and a mob farm look identical,
 * and the spec forbids that verdict. v3 requires an <b>anchor</b> plus
 * corroboration from <b>independent</b> streams, and — unlike v2 — frequency-
 * based evidence (attempt rate) alone can never corroborate:</p>
 * <ol>
 *   <li><b>Anchor — distinct targets</b>: distinct damaged entity ids within
 *       {@code windowMillis} ≥ {@code minTargets}. (Damage stream.)</li>
 *   <li><b>Regularity (machine corroboration)</b>: a full interval window
 *       with stddev &lt; {@code metronomicStddevMax} ms and at least
 *       {@code metronomicMinSamples} samples. (Damage stream.)</li>
 *   <li><b>Swingless attacks (injection corroboration)</b>: packetevents
 *       observed attempts outnumbering arm swings by 2:1 over the trailing
 *       second (requires ≥ 6 attempts) — an attack packet with no swing is a
 *       client-injected attack. (Attempt stream vs swing stream.)</li>
 *   <li><b>Attempt rate (scaling only)</b>: packetevents-observed attempts
 *       per second ≥ {@code minAttemptRate}. Includes misses, which the
 *       damage stream never sees; raises VL amount, never the gate.</li>
 * </ol>
 * Conviction requires <b>anchor AND (regularity OR swingless)</b> — attempts-
 * per-second can never, by itself, corroborate the anchor. Amount scales with
 * the number of corroborating sub-signals present (regularity, swingless,
 * attempt rate). Latency jitter only inflates interval stddev, which breaks
 * regularity rather than manufacturing it.
 *
 * <h2>False-positive considerations</h2>
 * <ul>
 *   <li>Creative/spectator and vehicles are exempt.</li>
 *   <li>A mob farm produces the anchor and regularity (stray cadence), but
 *       human farm clicks are irreducibly noisy — stddev rarely sits under
 *       15 ms for 6+ consecutive intervals, and farming cadence almost never
 *       crosses the swingless gate either. Treat alerts as evidence, not proof;
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

    public KillAuraCheck(long windowMillis, int minTargets, double vlPerTrigger) {
        this(windowMillis, minTargets, vlPerTrigger, 8.0D, 15.0D, 6);
    }

    public KillAuraCheck(long windowMillis, int minTargets, double vlPerTrigger,
                         double minAttemptRate, double metronomicStddevMax,
                         int metronomicMinSamples) {
        this.windowMillis = windowMillis;
        this.minTargets = minTargets;
        this.vlPerTrigger = vlPerTrigger;
        this.minAttemptRate = minAttemptRate;
        this.metronomicStddevMax = metronomicStddevMax;
        this.metronomicMinSamples = metronomicMinSamples;
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

        // Machine-evidence corroboration candidate 1: metronomic cadence over
        // the damage stream. Latency jitter inflates stddev, so lag BREAKS
        // this signal rather than manufacturing it.
        int samples = tracker.getIntervalSampleCount();
        double stddev = tracker.intervalStddev();
        double mean = tracker.intervalMean();
        boolean metronomic = samples >= metronomicMinSamples
                && !Double.isNaN(stddev) && !Double.isNaN(mean)
                && stddev < metronomicStddevMax && mean > 0.0D && mean < 400.0D;

        // Machine-evidence corroboration candidate 2: attempts without swings
        // (packet-injected attacks). Requires the packet path (PE optional).
        int attempts = data.attackAttemptLedger().attemptsSince(now, 1_000L);
        int swings = data.swingTracker().swingsSince(now, 1_000L);
        boolean swingless = attempts >= 6 && swings * 2L < attempts;

        // Attempt rate (misses included): scales the verdict, NEVER gates it.
        double attemptRate = data.attackAttemptLedger().attemptRate(now, 1_000L);
        boolean highCadence = attemptRate >= minAttemptRate;

        if (!anchor || !(metronomic || swingless)) {
            return;
        }

        int corroborations = (metronomic ? 1 : 0) + (swingless ? 1 : 0) + (highCadence ? 1 : 0);
        double amount = vlPerTrigger * Math.min(2.0D, 1.0D + 0.5D * (corroborations - 1));
        String debug = String.format(java.util.Locale.US,
                "distinctTargets=%d window=%dms metronomic=%b swingless=%b attempts=%.1f/s highCadence=%b corroborations=%d ping=%d",
                distinct, windowMillis, metronomic, swingless, attemptRate, highCadence, corroborations, ctx.getPingMillis());
        callback.onViolation(this, amount, debug);
    }
}
