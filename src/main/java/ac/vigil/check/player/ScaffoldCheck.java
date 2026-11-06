package ac.vigil.check.player;

import ac.vigil.check.Check;
import ac.vigil.check.CheckCategory;
import ac.vigil.check.ViolationCallback;
import ac.vigil.data.PlayerData;
import ac.vigil.detection.PlaceContext;

/**
 * Detects scaffold-cheat block placement: placing a block far from where the
 * player is looking.
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>For each placement, the angle between the player's look vector and
 *       the direction to the placed block's center is computed (exact 3D
 *       angle; yaw 0 &rarr; +Z).</li>
 *   <li>Legitimate bridge/tower building places the block near the
 *       crosshair (0&deg;-40&deg;). Scaffold cheats place blocks under or
 *       behind the player regardless of view direction. Placements with an
 *       angle above {@code maxAngleDegrees} increment a per-player streak;
 *       any in-view placement resets it.</li>
 *   <li>{@code minStreak} (default 6) consecutive out-of-view placements are
 *       required before VL accrues — a single misclick can never flag.</li>
 * </ol>
 *
 * <h2>False-positive considerations</h2>
 * <ul>
 *   <li>Backward bridging while looking forward still places blocks near the
 *       crosshair ahead (the player turns, then places).</li>
 *   <li>Stream angle state lives in the per-player tracker, so players never
 *       share streaks.</li>
 *   <li>Latency does not affect the angle math (all values come from the
 *       placing player's own client state at receipt time).</li>
 * </ul>
 */
public final class ScaffoldCheck implements Check<PlaceContext> {

    private final double maxAngleDegrees;
    private final int minStreak;
    private final double vlPerPlacement;

    public ScaffoldCheck(double maxAngleDegrees, int minStreak, double vlPerPlacement) {
        this.maxAngleDegrees = maxAngleDegrees;
        this.minStreak = minStreak;
        this.vlPerPlacement = vlPerPlacement;
    }

    @Override
    public String getName() {
        return "scaffold";
    }

    @Override
    public CheckCategory getCategory() {
        return CheckCategory.PLAYER;
    }

    @Override
    public void evaluate(PlaceContext ctx, PlayerData data, ViolationCallback callback) {
        double dirX = ctx.getBlockX() + 0.5D - ctx.getFeetX();
        double dirY = ctx.getBlockY() + 0.5D - ctx.getFeetY();
        double dirZ = ctx.getBlockZ() + 0.5D - ctx.getFeetZ();
        double length = Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
        if (length < 1.0E-4D) {
            return; // placing the block at the feet position: bearing undefined
        }

        double yawRad = Math.toRadians(ctx.getYaw());
        double pitchRad = Math.toRadians(ctx.getPitch());
        double lookX = -Math.sin(yawRad) * Math.cos(pitchRad);
        double lookY = -Math.sin(pitchRad);
        double lookZ = Math.cos(yawRad) * Math.cos(pitchRad);

        double dot = lookX * (dirX / length) + lookY * (dirY / length) + lookZ * (dirZ / length);
        dot = Math.max(-1.0D, Math.min(1.0D, dot));
        double angle = Math.toDegrees(Math.acos(dot));

        var tracker = data.blockActionTracker();
        if (angle <= maxAngleDegrees) {
            tracker.resetOutOfViewPlaceStreak();
            return;
        }

        int streak = tracker.addOutOfViewPlaceStreak();
        if (streak < minStreak) {
            return;
        }

        double amount = vlPerPlacement * Math.min(2.0D, 1.0D + (streak - minStreak) * 0.25D);
        String debug = String.format(java.util.Locale.US,
                "angle=%.1fdeg max=%.1fdeg streak=%d block=%s ping=%d",
                angle, maxAngleDegrees, streak, ctx.getBlockType(), ctx.getPingMillis());
        callback.onViolation(this, amount, debug);
    }
}
