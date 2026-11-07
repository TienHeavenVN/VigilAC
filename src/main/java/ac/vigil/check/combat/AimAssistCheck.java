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
 * <h2>Algorithm (v2, Phase 6)</h2>
 * <ol>
 *   <li><b>Bearing</b>: angle between the attacker's look vector and the
 *       <em>nearest point of the target's bounding box</em> as seen from the
 *       eye — not the centroid. A tall entity's legs and torso both satisfy
 *       a small bearing, which is exactly what an assist needs.</li>
 *   <li><b>Swing magnitude</b>: with packetevents installed, the rotation
 *       stream is consulted for the <em>single largest</em> consecutive-sample
 *       angular burst in the {@code snapMaxIntervalMillis} before the attack
 *       ({@code maxDeltaInWindow}); a snap is concentrated in one or two
 *       packets, never smeared across many. Verdicts use the per-attack
 *       interval captured <em>before</em> the attack was recorded (the
 *       previously-used tracker age always read ~0 after recording, making the
 *       interval gate vacuous — fixed in Phase 7). Without packetevents, the
 *       swing is derived from the previous attack's rotation (fallback).</li>
 *   <li>{@code minSnapStreak} (default 4) consecutive snaps on a target with
 *       angle &lt; {@code snapAngleToTarget} are required before VL accrues.</li>
 * </ol>
 *
 * <p>Smooth, continuous re-aiming never accumulates a snap streak; nothing in
 * this check penalizes "good aim" — it penalizes instantaneous re-orientation
 * that ends inside a target's box.</p>
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
        if (data.isDebug()) {
            CombatTracker t = data.combatTracker();
            double swing = -1.0D;
            if (t.hasLastAim()) {
                double yd = AttackGeometry.shortestAngleDegrees(t.getLastYaw(), ctx.getYaw());
                double pd = Math.abs(t.getLastPitch() - ctx.getPitch());
                swing = Math.hypot(yd, pd);
            }
            callback.onViolation(this, 0.0D, String.format(java.util.Locale.US,
                    "DIAG swing=%.1f int=%d yaw=%.1f streak=%d",
                    swing, t.lastAttackAgeMillis(ctx.getTimestamp()), ctx.getYaw(), t.getAimSnapStreak()));
        }
        if (ctx.isCreativeOrSpectator() || ctx.isAttackerInVehicle()) {
            return;
        }

        double eyeX = ctx.getEyeX(), eyeY = ctx.getEyeY(), eyeZ = ctx.getEyeZ();
        double angleToTarget = AttackGeometry.angleToClosestPointOnBox(
                ctx.getYaw(), ctx.getPitch(), eyeX, eyeY, eyeZ,
                ctx.getBoxMinX(), ctx.getBoxMinY(), ctx.getBoxMinZ(),
                ctx.getBoxMaxX(), ctx.getBoxMaxY(), ctx.getBoxMaxZ());

        CombatTracker tracker = data.combatTracker();
        long now = ctx.getTimestamp();

        boolean snap = false;
        double swing = -1.0D;
        // Interval since the PREVIOUS attack. AttackListener captured it before
        // recording this attack; fall back to the tracker's age (pre-record path).
        long previousAttackAt = ctx.getPreviousAttackAtMillis();
        long interval = previousAttackAt >= 0L
                ? Math.max(0L, now - previousAttackAt)
                : tracker.lastAttackAgeMillis(now);
        if (data.rotationTracker().size(now) >= 2) {
            // Telemetry path: the single largest rotation burst in the window,
            // never a sum of smooth tracking. A real assist teleports the
            // crosshair in one or two packets; humans distribute motion.
            swing = data.rotationTracker().maxDeltaInWindow(now, snapMaxIntervalMillis);
            snap = swing > snapAngularDelta
                    && interval <= snapMaxIntervalMillis
                    && angleToTarget < snapAngleToTarget;
        } else if (tracker.hasLastAim()) {
            // Fallback path (packetevents absent): swing between consecutive attacks.
            double yawDelta = AttackGeometry.shortestAngleDegrees(tracker.getLastYaw(), ctx.getYaw());
            double pitchDelta = Math.abs(tracker.getLastPitch() - ctx.getPitch());
            swing = Math.hypot(yawDelta, pitchDelta);
            snap = swing > snapAngularDelta
                    && interval <= snapMaxIntervalMillis
                    && angleToTarget < snapAngleToTarget;
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
                "angleToBox=%.2fdeg swing=%.1fdeg streak=%d interval=%dms ping=%d",
                angleToTarget, swing, streak, interval, ctx.getPingMillis());
        callback.onViolation(this, amount, debug);
    }
}
