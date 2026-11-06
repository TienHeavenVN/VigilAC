package ac.vigil.check.movement;

import ac.vigil.check.Check;
import ac.vigil.check.CheckCategory;
import ac.vigil.check.ViolationCallback;
import ac.vigil.data.PlayerData;
import ac.vigil.detection.DetectionContext;

/**
 * Detects sustained horizontal movement faster than the player's expected
 * legitimate top speed.
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>Skip observations where speed is not the player's own locomotion:
 *       vehicle, elytra glide, liquid, teleport grace, knockback grace.
 *       Those reset the sustained-excess accumulator.</li>
 *   <li>Instant horizontal speed is {@code |dxz| / dt}. A per-sample exponential
 *       moving average ({@code alpha = 0.35}) smooths frame-to-frame jitter from
 *       network batching; the EMA is what gets compared against the cap.</li>
 *   <li>The cap is derived from vanilla attributes:
 *       walk 4.317 blocks/s (default cap 4.4), sprint 5.612 (default cap 5.8),
 *       plus 20% per Speed potion level (configurable), plus a slippery-block
 *       multiplier on ice/slime (default 1.6x).</li>
 *   <li>Sustained excess is accumulated in seconds. Only when the accumulated
 *       excess exceeds {@code minSustainSeconds} is VL added, scaled by the
 *       relative overflow
 *       ({@code add = excessVlPerSecond * dt * min(2, excess/cap)}). A single
 *       laggy or bursty packet burst can therefore never trigger a flag.</li>
 * </ol>
 *
 * <h2>False-positive considerations</h2>
 * <ul>
 *   <li>Sprint is read from the player's actual state, not assumed.</li>
 *   <li>Speed potion amplifiers are read from active effects each observation.</li>
 *   <li>Ice/slime multiply the cap instead of raising every cap.</li>
 *   <li>Knockback (PlayerVelocityEvent) and teleports suspend the check for a
 *       configurable grace window, as do vehicles and elytra.</li>
 *   <li>VL requires sustained excess, so brief server-side rubber-banding
 *       (common at high ping / low TPS) cannot produce VL on its own. A low-TPS
 *       guard additionally disables the check below 10 TPS.</li>
 * </ul>
 */
public final class SpeedCheck implements Check<DetectionContext> {

    /** Below this TPS the check disables itself to avoid FP storms during lag. */
    public static final double MIN_TPS = 10.0D;

    private final double walkCap;
    private final double sprintCap;
    private final double speedPotionBonus;
    private final double slipperyMultiplier;
    private final double excessVlPerSecond;
    private final double minSustainSeconds;

    public SpeedCheck(double walkCap,
                      double sprintCap,
                      double speedPotionBonus,
                      double slipperyMultiplier,
                      double excessVlPerSecond,
                      double minSustainSeconds) {
        this.walkCap = walkCap;
        this.sprintCap = sprintCap;
        this.speedPotionBonus = speedPotionBonus;
        this.slipperyMultiplier = slipperyMultiplier;
        this.excessVlPerSecond = excessVlPerSecond;
        this.minSustainSeconds = minSustainSeconds;
    }

    @Override
    public String getName() {
        return "speed";
    }

    @Override
    public CheckCategory getCategory() {
        return CheckCategory.MOVEMENT;
    }

    @Override
    public void evaluate(DetectionContext ctx, PlayerData data, ViolationCallback callback) {
        if (ctx.isTeleportGrace() || ctx.isKnockbackGrace() || ctx.isInVehicle()
                || ctx.isGliding() || ctx.isInLiquid() || ctx.getServerTps() < MIN_TPS) {
            data.resetExcessTime();
            data.setLastMoveAt(ctx.getTimestamp());
            return;
        }

        long now = ctx.getTimestamp();
        long last = data.getLastMoveAt();
        data.setLastMoveAt(now);
        if (last <= 0L) {
            return; // first sample: establish the clock
        }
        double dtMillis = now - last;
        if (dtMillis <= 0.0D || dtMillis > 2_000.0D) {
            // Clock hiccup or long pause: start a fresh window rather than flag.
            data.resetExcessTime();
            return;
        }
        if (dtMillis < 45.0D) {
            // Backlog burst: the server drained several queued packets inside one
            // tick interval. Wall-clock dt under-represents real travel time and
            // the sample would fabricate an impossible instantaneous speed
            // (observed: a 563 ms-ping client producing EMA=11.0 on a legitimate
            // sprint-jump). Skip the sample: the EMA and the excess-time window
            // stay anchored to samples the server actually had time to move.
            return;
        }
        double seconds = dtMillis / 1_000.0D;

        double instantSpeed = ctx.getHorizontalDistance() / seconds;
        double ema = data.updateSpeedEma(instantSpeed);

        double cap = ctx.isSprinting() ? sprintCap : walkCap;
        cap *= 1.0D + speedPotionBonus * Math.max(0, ctx.getSpeedPotionLevel());
        if (ctx.isOnSlipperyBlock()) {
            cap *= slipperyMultiplier;
        }

        if (ema <= cap) {
            // Drain accumulated excess twice as fast as it can accumulate.
            data.addExcessTime(-2.0D * seconds);
            return;
        }

        double excess = ema - cap;
        double totalExcess = data.addExcessTime(seconds);
        if (totalExcess < minSustainSeconds) {
            return; // not yet sustained long enough to count as evidence
        }
        double ratio = excess / cap;
        double amount = excessVlPerSecond * seconds * Math.min(2.0D, ratio);
        String debug = String.format(java.util.Locale.US,
                "ema=%.3f cap=%.3f ratio=%.2f sustained=%.1fs dt=%dms sprint=%b potion=%d slippery=%b ping=%d tps=%.2f",
                ema, cap, ratio, totalExcess, (long) dtMillis, ctx.isSprinting(),
                ctx.getSpeedPotionLevel(), ctx.isOnSlipperyBlock(), ctx.getPingMillis(), ctx.getServerTps());
        callback.onViolation(this, amount, debug);
    }
}
