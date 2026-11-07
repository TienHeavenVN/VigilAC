package ac.vigil.check.combat;

import ac.vigil.check.Check;
import ac.vigil.check.CheckCategory;
import ac.vigil.check.ViolationCallback;
import ac.vigil.data.PlayerData;
import ac.vigil.detection.AttackContext;

/**
 * Detects melee attacks whose hit distance exceeds the vanilla attack reach.
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>For every attack we compute the distance from the attacker's eye
 *       position to the closest point on the target's axis-aligned bounding
 *       box ({@link AttackContext#distanceEyeToBox()}). This is the exact
 *       quantity vanilla uses, so legitimate hits cluster at
 *       &le; 3.0 blocks for players.</li>
 *   <li>An attack counts as over-reach when
 *       {@code distance > maxReach + tolerance} (defaults 3.0 + 0.03).</li>
 *   <li>Multi-observation confidence: after {@code minAttacksOver}
 *       <b>consecutive</b> over-reach attacks <b>and</b> a recent-window
 *       weighted average over the cap, VL is added, scaled by the excess:
 *       {@code add = min(2, vlPerAttackOver * (1 + excess))}.</li>
 *   <li>Any attack inside the cap resets the consecutive counter.</li>
 * </ol>
 *
 * <h2>False-positive considerations</h2>
 * <ul>
 *   <li>Creative/spectator gamemodes are exempt entirely.</li>
 *   <li>Attacks issued from a vehicle are exempt (interaction geometry differs).</li>
 *   <li>Latency does <b>not</b> increase reach: the server evaluates positions
 *       at receipt time, and a latency-lagged attacker is seen at a position
 *       at least as close as when they swung. No ping-based slack is added;
 *       the 0.03 tolerance covers floating point only.</li>
 *   <li>A single long-range hit can never raise VL: the consecutive-attack
 *       and windowed-average gates both must pass.</li>
 *   <li>Bounding boxes are entity-specific (the listener feeds the real
 *       target box), so entity-size variance (e.g. slime, enderman) is handled
 *       naturally.</li>
 * </ul>
 */
public final class ReachCheck implements Check<AttackContext> {

    private final double maxReach;
    private final double tolerance;
    private final int minAttacksOver;
    private final double vlPerAttackOver;
    private final int emaSamples;

    public ReachCheck(double maxReach,
                      double tolerance,
                      int minAttacksOver,
                      double vlPerAttackOver,
                      int emaSamples) {
        this.maxReach = maxReach;
        this.tolerance = tolerance;
        this.minAttacksOver = minAttacksOver;
        this.vlPerAttackOver = vlPerAttackOver;
        this.emaSamples = emaSamples;
    }

    @Override
    public String getName() {
        return "reach";
    }

    @Override
    public CheckCategory getCategory() {
        return CheckCategory.COMBAT;
    }

    @Override
    public void evaluate(AttackContext ctx, PlayerData data, ViolationCallback callback) {
        if (ctx.isCreativeOrSpectator() || ctx.isAttackerInVehicle()) {
            data.resetOverReachCounter();
            return;
        }

        double distance = ctx.distanceEyeToBox();
        data.recordReach(distance, emaSamples);

        double allowed = maxReach + tolerance;
        double excess = distance - allowed;
        if (excess <= 0.0D) {
            data.resetOverReachCounter();
            return;
        }

        int consecutive = data.addOverReachAttack();
        double ema = data.reachEma();
        if (consecutive < minAttacksOver || ema <= allowed) {
            return; // not yet enough sustained evidence
        }

        double amount = Math.min(2.0D, vlPerAttackOver * (1.0D + excess));
        String debug = String.format(java.util.Locale.US,
                "distance=%.3f allowed=%.3f excess=%.3f ema=%.3f consecutive=%d target=%s ping=%d",
                distance, allowed, excess, ema, consecutive, ctx.getTargetType(), ctx.getPingMillis());
        callback.onViolation(this, amount, debug);
    }
}
