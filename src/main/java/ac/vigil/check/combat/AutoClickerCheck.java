package ac.vigil.check.combat;

import ac.vigil.check.Check;
import ac.vigil.check.CheckCategory;
import ac.vigil.check.ViolationCallback;
import ac.vigil.data.PlayerData;
import ac.vigil.data.tracking.CombatTracker;
import ac.vigil.detection.AttackContext;

/**
 * Detects software-generated constant-rate attacking.
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>Attack intervals are kept in a rolling 20-sample window (recorded by
 *       the attack pipeline — see {@code AttackListener} — so every combat
 *       check observes the same stream exactly once).</li>
 *   <li><b>Rate gate</b>: sustained CPS (attacks in the trailing 1 s) above
 *       {@code cpsCap} (default 20.5, above the ~20/s hardware ceiling) with
 *       at least {@code minSamples} intervals on record.</li>
 *   <li><b>Regularity gate</b> (the stronger signal): humans produce bursty,
 *       noisy intervals (stddev typically 40-120 ms); clickers fire near-
 *       metronomically. If the window mean is below {@code roboticMeanMax}
 *       (default 150 ms) <b>and</b> the population stddev is below
 *       {@code roboticStddevMax} (default 12 ms), the window counts as
 *       robotic. {@code roboticMinStreak} (default 5) consecutive robotic
 *       windows are required before VL accrues.</li>
 * </ol>
 *
 * <h2>False-positive considerations</h2>
 * <ul>
 *   <li>Latency and jitter only raise stddev — they cannot make a human look
 *       more regular.</li>
 *   <li>High-CPS PvP players (14-17 CPS) are far below the rate gate and far
 *       above the stddev gate; both must pass.</li>
 *   <li>Creative/spectator gamemodes and vehicles are exempt.</li>
 * </ul>
 */
public final class AutoClickerCheck implements Check<AttackContext> {

    private final double cpsCap;
    private final int minSamples;
    private final double roboticMeanMax;
    private final double roboticStddevMax;
    private final int roboticMinSamples;
    private final int roboticMinStreak;
    private final double vlPerTrigger;

    public AutoClickerCheck(double cpsCap, int minSamples,
                            double roboticMeanMax, double roboticStddevMax,
                            int roboticMinSamples, int roboticMinStreak,
                            double vlPerTrigger) {
        this.cpsCap = cpsCap;
        this.minSamples = minSamples;
        this.roboticMeanMax = roboticMeanMax;
        this.roboticStddevMax = roboticStddevMax;
        this.roboticMinSamples = roboticMinSamples;
        this.roboticMinStreak = roboticMinStreak;
        this.vlPerTrigger = vlPerTrigger;
    }

    @Override
    public String getName() {
        return "auto-clicker";
    }

    @Override
    public CheckCategory getCategory() {
        return CheckCategory.COMBAT;
    }

    @Override
    public void evaluate(AttackContext ctx, PlayerData data, ViolationCallback callback) {
        if (data.isDebug()) {
            CombatTracker t = data.combatTracker();
            callback.onViolation(this, 0.0D, String.format(java.util.Locale.US,
                    "DIAG cps=%.2f samples=%d mean=%.1f std=%.1f yaw=%.1f",
                    t.cps(ctx.getTimestamp(), 1_000L), t.getIntervalSampleCount(),
                    t.intervalMean(), t.intervalStddev(), ctx.getYaw()));
        }
        if (ctx.isCreativeOrSpectator() || ctx.isAttackerInVehicle()) {
            return;
        }

        CombatTracker tracker = data.combatTracker();
        long now = ctx.getTimestamp();
        int samples = tracker.getIntervalSampleCount();
        double cps = tracker.cps(now, 1_000L);
        // Packet telemetry (optional): attack attempts include misses the damage
        // stream never sees. The rate gate uses the stronger of the two signals.
        double attemptRate = data.attackAttemptLedger().attemptRate(now, 1_000L);
        int attemptSamples = data.attackAttemptLedger().attemptsSince(now, 1_000L);
        double rate = Math.max(cps, attemptRate);
        int rateSamples = Math.max(samples, attemptSamples);

        if (rateSamples >= minSamples && rate > cpsCap) {
            double amount = vlPerTrigger * Math.min(2.0D, rate / cpsCap);
            String debug = String.format(java.util.Locale.US,
                    "rate=%.1f/s cps=%.1f attemptRate=%.1f cap=%.1f samples=%d/%d ping=%d",
                    rate, cps, attemptRate, cpsCap, samples, attemptSamples, ctx.getPingMillis());
            callback.onViolation(this, amount, debug);
            tracker.resetRoboticStreak();
            return;
        }

        boolean roboticWindow = samples >= roboticMinSamples;
        if (roboticWindow) {
            double mean = tracker.intervalMean();
            double stddev = tracker.intervalStddev();
            roboticWindow = mean > 0.0D && mean < roboticMeanMax && stddev < roboticStddevMax;
        }

        if (!roboticWindow) {
            tracker.resetRoboticStreak();
            return;
        }

        int streak = tracker.addRoboticStreak();
        if (streak < roboticMinStreak) {
            return;
        }

        double mean = tracker.intervalMean();
        double stddev = tracker.intervalStddev();
        double cv = tracker.intervalCv();
        double entropy = tracker.intervalEntropyBits(25);
        String debug = String.format(java.util.Locale.US,
                "meanInterval=%.1fms stddev=%.1fms cv=%.3f entropyBits=%.2f streak=%d cps=%.1f attemptRate=%.1f ping=%d",
                mean, stddev, cv, entropy, streak, cps, attemptRate, ctx.getPingMillis());
        callback.onViolation(this, vlPerTrigger, debug);
    }
}
