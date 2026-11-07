package ac.vigil.check.player;

import ac.vigil.check.Check;
import ac.vigil.check.CheckCategory;
import ac.vigil.check.ViolationCallback;
import ac.vigil.data.PlayerData;
import ac.vigil.detection.BreakContext;

/**
 * Detects block breaks that vanilla physics cannot produce.
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>Each break carries the vanilla lower-bound duration
 *       ({@link BreakContext#minimumBreakMillis()}) computed from block
 *       hardness, correct-tool facts, the held item's Efficiency enchant and
 *       the haste amplifier — the exact inputs vanilla uses.</li>
 *   <li>Blocks are mined strictly sequentially, so the time between the
 *       previous completion and the current one must be at least the current
 *       block's lower bound (one-tick leeway for client-side batching).
 *       Completions faster than that are counted as <b>impossible</b>.</li>
 *   <li>{@code minImpossibleStreak} (default 3) consecutive impossible breaks
 *       are required before VL accrues — the first flag impossible break
 *       (fresh tool assumptions, server rubber-banding) is never punished.</li>
 *   <li>Server-reported unbreakable material (hardness &lt; 0, e.g. bedrock)
 *       completing is an unconditional violation.</li>
 * </ol>
 */
public final class FastBreakCheck implements Check<BreakContext> {

    /** One-tick leniency on client/server completion ordering. */
    private static final long TICK_LEEWAY_MILLIS = 50L;

    private final int minImpossibleStreak;
    private final double vlPerTrigger;
    private final double unbreakableVl;

    public FastBreakCheck(int minImpossibleStreak, double vlPerTrigger, double unbreakableVl) {
        this.minImpossibleStreak = minImpossibleStreak;
        this.vlPerTrigger = vlPerTrigger;
        this.unbreakableVl = unbreakableVl;
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
