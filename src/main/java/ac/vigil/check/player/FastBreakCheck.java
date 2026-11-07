package ac.vigil.check.player;

import ac.vigil.check.Check;
import ac.vigil.check.CheckCategory;
import ac.vigil.check.ViolationCallback;
import ac.vigil.data.PlayerData;
import ac.vigil.detection.BreakContext;

/**
 * Detects block breaks that vanilla physics cannot produce.
 *
 * <h2>Algorithm (v2, Phase 6)</h2>
 * <p><b>Path A — digging lifecycle (packetevents present).</b> The break is
 * matched against the server-observed dig start (START_DIGGING → FINISHED_DIGGING).
 * The expected dig duration uses the full vanilla modifier stack
 * ({@link BreakContext#expectedBreakMillis()}): hardness, correct tool,
 * Efficiency, Haste, Mining Fatigue, underwater without Aqua Affinity,
 * off-ground, and the observed dig duration — compared per dig, not per
 * completion interval. Digs under the expectation accumulate missing time in
 * a decaying <b>penalty bucket</b>; VL requires sustained deficit across
 * breaks ({@code penaltyGraceMillis}, default 1500 ms) — a marginal single
 * dig never flags; a physically impossible deficit may do so on its own.
 * If tool or destroy-direction changed mid-dig the dig is marked inconclusive
 * and excluded from timing verdicts entirely.</p>
 * <p><b>Path B — completion interval (fallback; also without packetevents).</b>
 * Each break carries the vanilla lower-bound duration
 * ({@link BreakContext#minimumBreakMillis()}); blocks are mined strictly
 * sequentially, so the interval between completions must be at least the
 * current block's lower bound (one-tick leeway). Completions faster than
 * that are <b>impossible</b>; {@code minImpossibleStreak} (default 3)
 * consecutive impossible breaks are required before VL accrues.</p>
 * <p>Server-reported unbreakable material (hardness &lt; 0, e.g. bedrock)
 *     completing is an unconditional violation.</p>
 */
public final class FastBreakCheck implements Check<BreakContext> {

    /** One-tick leniency on client/server completion ordering. */
    private static final long TICK_LEEWAY_MILLIS = 50L;

    /** Penalty-bucket grace: milliseconds of unexplained dig-time deficit required before VL. */
    public static final double DEFAULT_PENALTY_GRACE_MILLIS = 1_500.0D;

    private final int minImpossibleStreak;
    private final double vlPerTrigger;
    private final double unbreakableVl;
    private final double penaltyGraceMillis;
    private final double penaltyVlPerTrigger;

    public FastBreakCheck(int minImpossibleStreak, double vlPerTrigger, double unbreakableVl) {
        this(minImpossibleStreak, vlPerTrigger, unbreakableVl, DEFAULT_PENALTY_GRACE_MILLIS, 1.0D);
    }

    public FastBreakCheck(int minImpossibleStreak, double vlPerTrigger, double unbreakableVl,
                          double penaltyGraceMillis, double penaltyVlPerTrigger) {
        this.minImpossibleStreak = minImpossibleStreak;
        this.vlPerTrigger = vlPerTrigger;
        this.unbreakableVl = unbreakableVl;
        this.penaltyGraceMillis = penaltyGraceMillis;
        this.penaltyVlPerTrigger = penaltyVlPerTrigger;
    }

    @Override
    public String getName() {
        return "fast-break";
    }

    @Override
    public CheckCategory getCategory() {
        return CheckCategory.PLAYER;
    }

    @Override
    public void evaluate(BreakContext ctx, PlayerData data, ViolationCallback callback) {
        long now = ctx.getTimestamp();
        var tracker = data.blockActionTracker();

        if (ctx.getHardness() < 0.0F) {
            // Unbreakable material completing is impossible, full stop.
            String debug = String.format(java.util.Locale.US,
                    "block=%s hardness=%.2f (unbreakable) ping=%d",
                    ctx.getBlockType(), ctx.getHardness(), ctx.getPingMillis());
            tracker.resetImpossibleBreakStreak();
            tracker.noteBreak(now);
            data.monitor().noteImpossibleBreak(debug);
            callback.onViolation(this, unbreakableVl, debug);
            return;
        }

        // Path A: real dig lifecycle observed via packetevents
        if (ctx.getDigDurationMillis() >= 0L) {
            tracker.noteBreak(now);
            tracker.resetImpossibleBreakStreak();
            if (ctx.isDigInconclusive()) {
                data.monitor().noteCheckEvidence(getName(), String.format(java.util.Locale.US,
                        "conclusive=false reason=tool-swap-or-direction-change dig=%dms block=%s",
                        ctx.getDigDurationMillis(), ctx.getBlockType()));
                return; // tool changed mid-dig: cannot judge the timing
            }
            double expected = ctx.expectedBreakMillis();
            long duration = ctx.getDigDurationMillis();
            double missing = expected - duration - TICK_LEEWAY_MILLIS;
            if (missing > 0.0D) {
                data.fastBreakPenalty().addMissing(missing, now);
            }
            double bucket = data.fastBreakPenalty().score(now);
            if (bucket <= penaltyGraceMillis) {
                return; // deficit exists but is within grace
            }
            data.fastBreakPenalty().dampen();
            String debug = String.format(java.util.Locale.US,
                    "block=%s hardness=%.2f dig=%dms expected=%.0fms bucket=%.0fms (grace=%.0fms) eff=%d haste=%d fatigue=%d water=%b aqua=%b ground=%b ping=%d",
                    ctx.getBlockType(), ctx.getHardness(), duration, expected, bucket, penaltyGraceMillis,
                    ctx.getEfficiency(), ctx.getHasteLevel(), ctx.getMiningFatigueLevel(),
                    ctx.isInWater(), ctx.hasAquaAffinity(), ctx.isOnGround(), ctx.getPingMillis());
            data.monitor().noteImpossibleBreak(debug);
            double amount = penaltyVlPerTrigger * Math.min(2.0D, 1.0D + Math.max(0.0D, bucket - penaltyGraceMillis) / penaltyGraceMillis);
            callback.onViolation(this, amount, debug);
            return;
        }

        // Path B: completion-interval fallback
        double minMillis = ctx.minimumBreakMillis();
        long interval = tracker.breakInterval(now);
        tracker.noteBreak(now);

        if (minMillis <= TICK_LEEWAY_MILLIS) {
            tracker.resetImpossibleBreakStreak();
            data.monitor().noteExemption(getName(), "instant-break material (vanilla lower bound <= 50ms)");
            return; // instant-break material: no timing signal exists
        }

        boolean impossible = interval != Long.MAX_VALUE && interval + TICK_LEEWAY_MILLIS < (long) minMillis;
        if (!impossible) {
            tracker.resetImpossibleBreakStreak();
            return;
        }

        data.monitor().noteImpossibleBreak(String.format(java.util.Locale.US,
                "block=%s interval=%dms minimum=%dms", ctx.getBlockType(), interval, (long) minMillis));
        int streak = tracker.addImpossibleBreakStreak();
        if (streak < minImpossibleStreak) {
            return;
        }

        double amount = vlPerTrigger * Math.min(2.0D, minMillis / Math.max(1.0D, interval) );
        String debug = String.format(java.util.Locale.US,
                "block=%s hardness=%.2f interval=%dms minimum=%dms streak=%d eff=%d haste=%d correctTool=%b ping=%d",
                ctx.getBlockType(), ctx.getHardness(), interval, (long) minMillis, streak,
                ctx.getEfficiency(), ctx.getHasteLevel(), ctx.isCorrectTool(), ctx.getPingMillis());
        callback.onViolation(this, amount, debug);
    }
}
