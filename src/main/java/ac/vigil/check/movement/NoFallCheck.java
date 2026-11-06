package ac.vigil.check.movement;

import ac.vigil.check.Check;
import ac.vigil.check.CheckCategory;
import ac.vigil.check.ViolationCallback;
import ac.vigil.data.PlayerData;
import ac.vigil.data.tracking.MovementTracker;
import ac.vigil.detection.DetectionContext;

/**
 * Detects fall-damage cancellation by comparing estimated accumulated fall
 * distance against the presence of a damage event on the landing tick.
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>While airborne, every positive downward {@code deltaY} is
 *       accumulated ({@link MovementTracker}); the accumulator resets on
 *       leaving the ground state, so net falls include jumps and rises.</li>
 *   <li>On the landing tick (airborne → grounded transition), if the
 *       accumulated descent exceeds {@code minFallDistance} (default 3.5,
 *       matching the vanilla 3-block damage threshold) and no
 *       {@code EntityDamageEvent} for this player was recorded within
 *       {@code damageGraceMillis} (default 500 ms), the landing counts as
 *       <b>suspicious</b>.</li>
 *   <li>Falling far without damage has a legitimate explanation pool
 *       (slime/honey, Slow Falling, Levitation, water, &hellip;), all of
 *       which are exempted — so each suspicious landing increments a streak.
 *       Only after {@code minSuspiciousLandings} (default 3) consecutive
 *       suspicious landings is VL added.</li>
 * </ol>
 *
 * <h2>False-positive considerations</h2>
 * <ul>
 *   <li>Levitation, Slow Falling, elytra, vehicles, liquids, slime/honey
 *       (feet block), teleports and velocity all exempt the check.</li>
 *   <li>Fall damage has tick-grain timing: the 500 ms damage grace window
 *       covers the landing tick itself plus server lag.</li>
 *   <li>A single big fall with forgotten damage immunity (e.g. just exited
 *       levitation) resets the streak — three in a row are required.</li>
 * </ul>
 */
public final class NoFallCheck implements Check<DetectionContext> {

    public static final double MIN_TPS = 10.0D;

    private final double minFallDistance;
    private final long damageGraceMillis;
    private final int minSuspiciousLandings;
    private final double vlPerLanding;

    public NoFallCheck(double minFallDistance, long damageGraceMillis, int minSuspiciousLandings, double vlPerLanding) {
        this.minFallDistance = minFallDistance;
        this.damageGraceMillis = damageGraceMillis;
        this.minSuspiciousLandings = minSuspiciousLandings;
        this.vlPerLanding = vlPerLanding;
    }

    @Override
    public String getName() {
        return "no-fall";
    }

    @Override
    public CheckCategory getCategory() {
        return CheckCategory.MOVEMENT;
    }

    @Override
    public void evaluate(DetectionContext ctx, PlayerData data, ViolationCallback callback) {
        MovementTracker tracker = data.movementTracker();

        boolean exempt = ctx.isInVehicle() || ctx.isGliding() || ctx.isInLiquid()
                || ctx.isTeleportGrace() || ctx.isKnockbackGrace()
                || ctx.isLevitation() || ctx.isSlowFalling() || ctx.isOnSlipperyBlock()
                || ctx.getServerTps() < MIN_TPS;
        if (exempt) {
            if (!ctx.isOnGround()) {
                tracker.enterAirborne(ctx.getTimestamp());
                tracker.accumulateAirborne(ctx.getDeltaY());
            } else {
                tracker.leaveAirborne();
            }
            return;
        }

        long now = ctx.getTimestamp();
        if (!ctx.isOnGround()) {
            tracker.enterAirborne(now);
            tracker.accumulateAirborne(ctx.getDeltaY());
            return;
        }

        boolean wasAirborne = tracker.isAirborne();
        double descent = tracker.leaveAirborne();
        if (!wasAirborne || descent < minFallDistance) {
            if (descent < minFallDistance) {
                tracker.resetSuspiciousLandings();
            }
            return;
        }

        long sinceDamage = now - data.getLastDamageAt();
        if (data.getLastDamageAt() > 0L && sinceDamage >= 0L && sinceDamage <= damageGraceMillis) {
            tracker.resetSuspiciousLandings(); // fall damage applied: legit
            return;
        }

        int streak = tracker.addSuspiciousLanding();
        if (streak < minSuspiciousLandings) {
            return;
        }

        double amount = vlPerLanding * Math.min(2.0D, descent / minFallDistance);
        String debug = String.format(java.util.Locale.US,
                "fallDistance=%.2f sinceDamage=%dms streak=%d ping=%d tps=%.2f",
                descent, data.getLastDamageAt() > 0L ? sinceDamage : -1L, streak,
                ctx.getPingMillis(), ctx.getServerTps());
        callback.onViolation(this, amount, debug);
    }
}
