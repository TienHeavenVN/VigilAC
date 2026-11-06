package ac.vigil.check.combat;

import ac.vigil.check.Check;
import ac.vigil.check.CheckCategory;
import ac.vigil.check.ViolationCallback;
import ac.vigil.data.PlayerData;
import ac.vigil.data.tracking.CombatTracker;
import ac.vigil.detection.AttackContext;

/**
 * Detects instant aim snapping onto attacked entities.
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>Computes the angle between the attacker's look vector and the
 *       direction to the center of the attacked bounding box (exact vector
 *       math; MC yaw 0 &rarr; +Z, increasing clockwise).</li>
 *   <li>Keeps the previous attack's rotation snapshot. A <b>snap</b> is an
 *       angular swing larger than {@code snapAngularDelta} between two
 *       attacks closer together than {@code snapMaxIntervalMillis} that
 *       lands within {@code snapAngleToTarget} of the target center.
 *       Humans re-aim smoothly over several ticks and cannot teleport their
 *       crosshair from arbitrary orientation to sub-6&deg; bearing in under
 *       120 ms.</li>
 *   <li>{@code minSnapStreak} (default 4) consecutive snaps are required
 *       before VL accrues; any attack that is not a snap resets the streak.</li>
 * </ol>
 *
 * <h2>False-positive considerations</h2>
 * <ul>
 *   <li>Animal/mob hits right as the player turns to them can look snap-ish;
 *       the streak gate and the 6&deg; bearing requirement keep borderline
 *       human flicks out.</li>
 *   <li>Latency does not change where the client reports the swing origin.</li>
 *   <li>Creative/spectator gamemodes and vehicles are exempt.</li>
 * </ul>
 */
public final class AimAssistCheck implements Check<AttackContext> {

    private final double snapAngularDelta;
    private final double snapAngleToTarget;
    private final long snapMaxIntervalMillis;
    private final int minSnapStreak;
    private final double vlPerSnap;

    public AimAssistCheck(double snapAngularDelta, double snapAngleToTarget,
                          long snapMaxIntervalMillis, int minSnapStreak, double vlPerSnap) {
        this.snapAngularDelta = snapAngularDelta;
        this.snapAngleToTarget = snapAngleToTarget;
        this.snapMaxIntervalMillis = snapMaxIntervalMillis;
        this.minSnapStreak = minSnapStreak;
        this.vlPerSnap = vlPerSnap;
    }

    @Override
    public String getName() {
        return "aim-assist";
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

        double eyeX = ctx.getEyeX(), eyeY = ctx.getEyeY(), eyeZ = ctx.getEyeZ();
        double cx = (ctx.getBoxMinX() + ctx.getBoxMaxX()) * 0.5D;
        double cy = (ctx.getBoxMinY() + ctx.getBoxMaxY()) * 0.5D;
        double cz = (ctx.getBoxMinZ() + ctx.getBoxMaxZ()) * 0.5D;
        double dx = cx - eyeX, dy = cy - eyeY, dz = cz - eyeZ;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1.0E-4D) {
            return; // hitbox center at the eye: bearing is undefined, not suspicious
        }

        double angleToTarget = angleBetweenLookAnd(ctx.getYaw(), ctx.getPitch(), dx / length, dy / length, dz / length);

        CombatTracker tracker = data.combatTracker();
        long now = ctx.getTimestamp();

        boolean snap = false;
        if (tracker.hasLastAim()) {
            double yawDelta = shortestAngleDegrees(tracker.getLastYaw(), ctx.getYaw());
            double pitchDelta = Math.abs(tracker.getLastPitch() - ctx.getPitch());
            double swing = Math.hypot(yawDelta, pitchDelta);
            long interval = tracker.lastAttackAgeMillis(now);
            snap = swing > snapAngularDelta && interval <= snapMaxIntervalMillis && angleToTarget < snapAngleToTarget;
        }

        tracker.recordAim(ctx.getYaw(), ctx.getPitch());

        if (!snap) {
            tracker.resetAimSnapStreak();
            return;
        }

        int streak = tracker.addAimSnapStreak();
        if (streak < minSnapStreak) {
            return;
        }

        double amount = vlPerSnap * Math.min(2.0D, 1.0D + (streak - minSnapStreak) * 0.25D);
        String debug = String.format(java.util.Locale.US,
                "angleToTarget=%.2fdeg streak=%d interval=%dms ping=%d",
                angleToTarget, streak, tracker.lastAttackAgeMillis(now), ctx.getPingMillis());
        callback.onViolation(this, amount, debug);
    }

    /** Angle between the MC look vector for (yaw, pitch) and a normalized direction, in degrees. */
    static double angleBetweenLookAnd(float yaw, float pitch, double dirX, double dirY, double dirZ) {
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        double lookX = -Math.sin(yawRad) * Math.cos(pitchRad);
        double lookY = -Math.sin(pitchRad);
        double lookZ = Math.cos(yawRad) * Math.cos(pitchRad);
        double dot = lookX * dirX + lookY * dirY + lookZ * dirZ;
        dot = Math.max(-1.0D, Math.min(1.0D, dot));
        return Math.toDegrees(Math.acos(dot));
    }

    /** Smallest absolute angular distance between two yaw values, in degrees (0-180). */
    static double shortestAngleDegrees(float from, float to) {
        double diff = (to - from) % 360.0D;
        if (diff > 180.0D) {
            diff -= 360.0D;
        } else if (diff < -180.0D) {
            diff += 360.0D;
        }
        return Math.abs(diff);
    }
}
