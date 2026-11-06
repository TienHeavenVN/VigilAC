package ac.vigil.check.movement;

import ac.vigil.check.Check;
import ac.vigil.check.CheckCategory;
import ac.vigil.check.ViolationCallback;
import ac.vigil.data.PlayerData;
import ac.vigil.data.tracking.MovementTracker;
import ac.vigil.detection.DetectionContext;

/**
 * Detects survival-mode flight and hovering by tracking sustained airborne
 * time without a corresponding fall.
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>Airborne periods open on the first move observation with
 *       {@code onGround=false} and close on the first grounded observation
 *       or any movement-modifier state (vehicle, elytra, liquid, teleport,
 *       velocity, Levitation, Slow Falling, low TPS).</li>
 *   <li>Two distinct fly signatures are tracked over the airborne period:</li>
 *   <ul>
 *     <li><b>Hover</b>: total accumulated |vertical motion| stays below
 *         {@code hoverTolerance} blocks while airborne longer than
 *         {@code minHoverMillis}. Vanilla cannot produce zero-net-vertical
 *         flight without a status effect (Slow Falling is exempted).</li>
 *     <li><b>Rising flight</b>: {@code minUpwardTicks} consecutive positive
 *         vertical ticks with net upward displacement — impossible without a
 *         jump or the Levitation effect (both exempted; jumps never sustain
 *         {@code minUpwardTicks} ticks and reset on any downward tick).</li>
 *   </ul>
 *   <li>Knockback grace suppresses the check because knockback trajectories
 *       routinely look like hover/rise for 1-2 seconds.</li>
 * </ol>
 *
 * <h2>False-positive considerations</h2>
 * <ul>
 *   <li>Elytra, vehicles, liquids, pistons (grace), teleports (grace),
 *       Levitation and Slow Falling potions are all exempt.</li>
 *   <li>Jumping peaks at ~1.3 s airborne — far below the 3 s default hover
 *       window. Terminal falls are never flagged (large descent).</li>
 *   <li>Slime/honey bounces trigger the upward-flight branch, so those are
 *       exempted via the feet-block slippery flag (covers SLIME_BLOCK /
 *       HONEY_BLOCK).</li>
 *   <li>No verdict is produced from a single observation: a cheat must
 *       sustain the pattern for the full airborne window.</li>
 * </ul>
 */
public final class FlyCheck implements Check<DetectionContext> {

    public static final double MIN_TPS = 10.0D;

    private final long minHoverMillis;
    private final double hoverTolerance;
    private final int minUpwardTicks;
    private final double vlPerObservation;

    public FlyCheck(long minHoverMillis, double hoverTolerance, int minUpwardTicks, double vlPerObservation) {
        this.minHoverMillis = minHoverMillis;
        this.hoverTolerance = hoverTolerance;
        this.minUpwardTicks = minUpwardTicks;
        this.vlPerObservation = vlPerObservation;
    }

    @Override
    public String getName() {
        return "fly";
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
            tracker.leaveAirborne(); // modifier active: reset evidence
            return;
        }

        long now = ctx.getTimestamp();
        if (ctx.isOnGround()) {
            tracker.leaveAirborne();
            return;
        }

        tracker.enterAirborne(now);
        tracker.accumulateAirborne(ctx.getDeltaY());

        long airborne = tracker.airborneMillis(now);
        if (airborne < minHoverMillis) {
            return; // too brief for fly evidence
        }

        double net = tracker.getNetVerticalAirborne();
        int upTicks = tracker.getUpwardAirborneTicks();
        double descent = tracker.getDescentAirborne();
        boolean bounceBlock = ctx.isOnSlipperyBlock(); // slime/honey family

        boolean hovering = Math.abs(net) <= hoverTolerance && descent <= hoverTolerance;
        boolean rising = !bounceBlock && upTicks >= minUpwardTicks && net > hoverTolerance;

        if (!hovering && !rising) {
            return;
        }

        double scale = rising ? Math.min(2.0D, 1.0D + net) : Math.min(2.0D, 1.0D + (hoverTolerance - Math.abs(net)));
        double amount = vlPerObservation * scale;
        String debug = String.format(java.util.Locale.US,
                "airborne=%dms netY=%.2f upTicks=%d descent=%.2f hover=%b rising=%b ping=%d tps=%.2f",
                airborne, net, upTicks, descent, hovering, rising, ctx.getPingMillis(), ctx.getServerTps());
        callback.onViolation(this, amount, debug);
    }
}
