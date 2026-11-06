package ac.vigil.check.movement;

import ac.vigil.check.Check;
import ac.vigil.check.CheckCategory;
import ac.vigil.check.ViolationCallback;
import ac.vigil.data.PlayerData;
import ac.vigil.detection.DetectionContext;

/**
 * Detects geometrically impossible movement observations.
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>Non-finite coordinates or rotations (NaN, &#177;Infinity) can only be
 *       produced by a client sending crafted packets; vanilla never emits
 *       them. Flagged with full VL immediately (evidence is conclusive).</li>
 *   <li>Absolute per-tick vertical travel beyond
 *       {@code maxVerticalDelta} (default 4.25, above the 3.92 blocks/tick
 *       vanilla terminal velocity at 78.4 m/s and below the listener's
 *       teleport threshold for horizontal travel) is impossible for any
 *       vanilla mechanic outside the exempted states (elytra, liquids,
 *       vehicles, teleports, velocity).</li>
 * </ol>
 *
 * <p>Horizontal speed is deliberately NOT re-checked here (SpeedCheck owns
 * that signal); this check exists for impossible geometry only.</p>
 */
public final class InvalidMovementCheck implements Check<DetectionContext> {

    public static final double MIN_TPS = 10.0D;

    private final double maxVerticalDelta;
    private final double vlPerImpossibility;

    public InvalidMovementCheck(double maxVerticalDelta, double vlPerImpossibility) {
        this.maxVerticalDelta = maxVerticalDelta;
        this.vlPerImpossibility = vlPerImpossibility;
    }

    @Override
    public String getName() {
        return "invalid-movement";
    }

    @Override
    public CheckCategory getCategory() {
        return CheckCategory.MOVEMENT;
    }

    @Override
    public void evaluate(DetectionContext ctx, PlayerData data, ViolationCallback callback) {
        boolean finite = Double.isFinite(ctx.getFromX()) && Double.isFinite(ctx.getFromY()) && Double.isFinite(ctx.getFromZ())
                && Double.isFinite(ctx.getToX()) && Double.isFinite(ctx.getToY()) && Double.isFinite(ctx.getToZ())
                && Double.isFinite(ctx.getDeltaY()) && Float.isFinite(ctx.getYaw()) && Float.isFinite(ctx.getPitch());
        if (!finite) {
            String debug = String.format(java.util.Locale.US,
                    "from=(%.2f,%.2f,%.2f) to=(%.2f,%.2f,%.2f) yaw=%.2f pitch=%.2f",
                    ctx.getFromX(), ctx.getFromY(), ctx.getFromZ(),
                    ctx.getToX(), ctx.getToY(), ctx.getToZ(), ctx.getYaw(), ctx.getPitch());
            callback.onViolation(this, vlPerImpossibility, debug);
            return;
        }

        boolean predictable = ctx.isInVehicle() || ctx.isGliding() || ctx.isInLiquid()
                || ctx.isTeleportGrace() || ctx.isKnockbackGrace() || ctx.isLevitation()
                || ctx.getServerTps() < MIN_TPS;
        if (predictable) {
            return;
        }

        double absDy = Math.abs(ctx.getDeltaY());
        if (absDy > maxVerticalDelta) {
            String debug = String.format(java.util.Locale.US,
                    "verticalDelta=%.3f max=%.3f gliding=%b liquid=%b ping=%d",
                    absDy, maxVerticalDelta, ctx.isGliding(), ctx.isInLiquid(), ctx.getPingMillis());
            callback.onViolation(this, vlPerImpossibility, debug);
        }
    }
}
