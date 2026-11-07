package ac.vigil.check.movement;

import ac.vigil.check.Check;
import ac.vigil.check.CheckCategory;
import ac.vigil.check.ViolationCallback;
import ac.vigil.data.PlayerData;
import ac.vigil.data.tracking.MovementTracker;
import ac.vigil.detection.DetectionContext;

/**
 * Detects the "step" hack: silently climbing a full block (or more) without
 * jumping, gliding, or levitation.
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>Vanilla auto-step lifts the player at most 0.6 blocks per tick. Any
 *       single observation with {@code deltaY > stepMaxLegal} (default 0.95,
 *       which clears vanilla auto-step plus float jitter, and also clears
 *       Jump Boost up to roughly level IV: jump velocity 0.42 + 0.1 per level)
 *       is impossible without an external modifier.</li>
 *   <li>Consecutive impossibilities are counted in the per-player tracker.
 *       One impossibility can race with piston displacement history or block
 *       placement under the feet, so evidence requires
 *       {@code minStreak} (default 2) consecutive impossibilities.</li>
 *   <li>The comparison uses a 1e-6 epsilon above the configured bound so a
 *       delta reconstructed as (from + d) - from never flips across the
 *       boundary on floating-point dust.</li>
 *   <li>Only observations that disqualify (legal or ambiguous rise) reset the
 *       streak — a single legal tick between two hacked steps therefore clears
 *       the streak and avoids stacking evidence across unrelated events.</li>
 * </ol>
 *
 * <h2>False-positive considerations</h2>
 * <ul>
 *   <li>Pistons are covered by the piston confounder's velocity grace
 *       ({@code isKnockbackGrace}) — pistons displace players vertically at
 *       up to 1.5 blocks/tick without firing velocity events; the grace window
 *       resets the streak.</li>
 *   <li>Levitation (vertical velocity ~0.9/tick) and vehicles are exempt.</li>
 *   <li>Elytra and teleports are exempt.</li>
 *   <li>Jump Boost: level VI and above can produce a first-tick rise above the
 *       0.95 threshold. Documented limitation: players with Jump Boost VI+
 *       may accumulate step evidence on their opening jump tick; the default
 *       {@code minStreak == 2} requires two CONSECUTIVE such ticks in one jump
 *       arc, which a normal jump (apex at tick ~8) never produces. Sustained
 *       flags therefore still indicate the hack, not the potion.</li>
 *   <li>Slime-block bounces: bounce velocity exceeds 0.95 in the first tick
 *       out of a charge jump off a slime block (up to ~1.5 for sneak-absorbed
 *       bounce). Documented limitation: exempt is not applied because the
 *       tracker Streak gate (2 consecutive ticks > 0.95) still excludes
 *       particle-single bounce ticks; a sneak-charged full bounce could add at
 *       most VL once per bounce — acceptable for alert-only mode.</li>
 *   <li>Stairs (0.5 rise), half slabs (0.5), carpet (0.125), stairs-up-block
 *       transitions all sit far below the threshold.</li>
 * </ul>
 */
public final class StepCheck implements Check<DetectionContext> {

    public static final double MIN_TPS = 10.0D;
    /** Rounding margin so a delta built as (from + d) - from never flips the comparison. */
    public static final double EPSILON = 1.0E-6D;

    private final double stepMaxLegal;
    private final int minStreak;
    private final double vlPerStep;

    public StepCheck(double stepMaxLegal, int minStreak, double vlPerStep) {
        this.stepMaxLegal = stepMaxLegal;
        this.minStreak = minStreak;
        this.vlPerStep = vlPerStep;
    }

    @Override
    public String getName() {
        return "step";
    }

    @Override
    public CheckCategory getCategory() {
        return CheckCategory.MOVEMENT;
    }

    @Override
    public void evaluate(DetectionContext ctx, PlayerData data, ViolationCallback callback) {
        MovementTracker tracker = data.movementTracker();

        if (ctx.isInVehicle() || ctx.isGliding() || ctx.isInLiquid()
                || ctx.isTeleportGrace() || ctx.isKnockbackGrace()
                || ctx.isLevitation() || ctx.isSlowFalling()
                || ctx.getServerTps() < MIN_TPS) {
            tracker.resetStepImpossibility();
            return;
        }

        if (ctx.getDeltaY() <= stepMaxLegal + EPSILON) {
            tracker.resetStepImpossibility();
            return;
        }

        int streak = tracker.noteStepImpossibility();
        if (streak < minStreak) {
            return;
        }

        String debug = String.format(java.util.Locale.US,
                "dy=%.3f stepStreak=%d ping=%d tps=%.2f",
                ctx.getDeltaY(), streak, ctx.getPingMillis(), ctx.getServerTps());
        callback.onViolation(this, vlPerStep, debug);
    }
}
