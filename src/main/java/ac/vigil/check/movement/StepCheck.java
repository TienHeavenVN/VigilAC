package ac.vigil.check.movement;

import ac.vigil.check.Check;
import ac.vigil.check.CheckCategory;
import ac.vigil.check.ViolationCallback;
import ac.vigil.data.PlayerData;
import ac.vigil.data.tracking.MovementTracker;
import ac.vigil.data.tracking.StepBounceTracker;
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
 * <h2>Slime bounce — bounded, evidence-based exemption</h2>
 * There is deliberately <b>no global slime exemption</b> (slippery flags alone
 * never silence this check). Instead, every tick feeds
 * {@link StepBounceTracker}, a bounded state machine. A tick is exempt from
 * impossibility accumulation only while it fits a validated ballistic arc:
 * falling before launch, slime-family contact at/under the feet, launch
 * velocity within the vanilla sneak-charge ceiling (1.6 blocks/tick),
 * expected decay {@code launchV - 0.08k} within ±0.22 blocks per tick, never
 * accelerating upward, at most 5 rising ticks / 1.5 s per arc, at most 8
 * sequences per 4 s, hard expiry, and a full reset on teleport or TPS &lt; 10.
 * Outside those bounds (or after rate-limit exhaustion) this check applies
 * exactly as if the tracker did not exist — actual Step cheats are preserved.
 * Only Step bookkeeping is suspended inside a validated arc; every other
 * check evaluates the player normally.
 *
 * <h2>False-positive considerations</h2>
 * <ul>
 *   <li>Pistons are covered by the piston confounder's velocity grace
 *       ({@code isKnockbackGrace}) — pistons displace players vertically at
 *       up to 1.5 blocks/tick without firing velocity events; the grace window
 *       resets the streak and discards any in-flight bounce arc.</li>
 *   <li>Levitation (vertical velocity ~0.9/tick) and vehicles are exempt.</li>
 *   <li>Elytra and teleports are exempt.</li>
 *   <li>Jump Boost: level VI and above can produce a first-tick rise above the
 *       0.95 threshold. Documented limitation: players with Jump Boost VI+
 *       may accumulate step evidence on their opening jump tick; the default
 *       {@code minStreak == 2} requires two CONSECUTIVE such ticks in one jump
 *       arc, which a normal jump (apex at tick ~8) never produces. Sustained
 *       flags therefore still indicate the hack, not the potion.</li>
 *   <li>Slime-block bounces, including sneak-charged full bounces (launch up
 *       to ~1.6 blocks/tick), are handled by the bounded tracker above — they
 *       carry no streak credit. Ice/honey arcs and Jump Boost launches on ice
 *       can borrow the same tracker budget; the rate limit (8 sequences /
 *       4 s) and the ballistic deviation gates bound that reuse, and
 *       acquisition still requires a falling-to-rising transition off
 *       slippery contact.</li>
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
            data.stepBounceTracker().resetState();
            tracker.resetStepImpossibility();
            return;
        }

        // Bounded slime-bounce validation runs before any impossibility logic.
        // A validated arc suspends only Step bookkeeping for this tick; it can
        // never clear an existing streak (an arc is not proof of past honesty).
        StepBounceTracker.Verdict bounce = data.stepBounceTracker().update(
                ctx.getTimestamp(), ctx.getDeltaY(),
                ctx.isBelowFeetSlippery(), ctx.isOnSlipperyBlock(),
                ctx.getServerTps(), ctx.isTeleportGrace());
        if (bounce == StepBounceTracker.Verdict.EXEMPT_BOUNCE) {
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
