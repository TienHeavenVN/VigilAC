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
 *       &le; 3.0 blocks for common weapons.</li>
 *   <li>The effective cap is <b>item-specific when reliable data exists</b>
 *       (e.g. a verified {@code attack_range} component, 1.21.11+ spears) via
 *       {@code ctx.getAttackRangeMax()}; otherwise the configured
 *       {@code maxReach} applies. A single hit can never raise VL: the
 *       consecutive-attack and windowed-average gates must both pass.</li>
 *   <li>An attack counts as over-reach when
 *       {@code distance > effectiveRange + tolerance}.</li>
 *   <li>Multi-observation confidence: after {@code minAttacksOver}
 *       <b>consecutive</b> over-reach attacks <b>and</b> a recent-window
 *       weighted average over the cap, VL is added, scaled by the excess:
 *       {@code add = min(2, vlPerAttackOver * (1 + excess))}.</li>
 *   <li>Any attack inside the cap resets the consecutive counter.</li>
 * </ol>
 *
 * <h2>Latency semantics (honest version)</h2>
 * Positions are evaluated at <em>receipt</em> time, not at swing time. If the
 * target strafes <em>away</em> from the attacker faster than the attacker's
 * position updates, the receipt-time distance is <em>larger</em> than the
 * swing-time distance — latency can make a legitimate attack look longer than
 * it was. If the target strafes toward the attacker, the opposite holds.
 * The default configuration adds <b>no</b> latency-based slack (the 0.03
 * tolerance covers floating point only). An opt-in
 * {@code checks.reach.lag-compensation} projects the target box backward by
 * its velocity over the attacker's ping (capped by
 * {@code lag-compensation-max-blocks}) — off by default until validated on
 * real lag traces; it never <em>extends</em> detection, it only narrows it.
 *
 * <h2>False-positive considerations</h2>
 * <ul>
 *   <li>Creative/spectator gamemodes are exempt entirely.</li>
 *   <li>Attacks issued from a vehicle are exempt (interaction geometry differs).</li>
 *   <li>Item-specific range, where read successfully, raises the cap — a
 *       spear jab at 4.0 blocks is vanilla-legal on 1.21.11+ servers.</li>
 *   <li>A single long-range hit can never raise VL: the consecutive-attack
 *       and windowed-average gates both must pass.</li>
 *   <li>Bounding boxes are entity-specific, so entity-size variance (e.g.
 *       slime, enderman) is handled naturally.</li>
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
        double compensation = 0.0D;
        if (ctx.isLagCompensationEnabled()) {
            double velocity = Math.sqrt(ctx.getTargetVelX() * ctx.getTargetVelX()
                    + ctx.getTargetVelY() * ctx.getTargetVelY()
                    + ctx.getTargetVelZ() * ctx.getTargetVelZ());
            compensation = Math.min(velocity * (Math.max(0L, ctx.getPingMillis()) / 1_000.0D),
                    Math.max(0.0D, ctx.getLagCompensationMaxBlocks()));
            distance -= compensation;
        }
        data.recordReach(distance, emaSamples);

        Double itemMax = ctx.getAttackRangeMax();
        double effectiveRange = (itemMax != null ? itemMax : maxReach);
        double allowed = effectiveRange + tolerance;
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
                "distance=%.3f allowed=%.3f excess=%.3f ema=%.3f consecutive=%d range=%s source=%s compensation=%.3f target=%s ping=%d",
                distance, allowed, excess, ema, consecutive,
                String.format(java.util.Locale.US, "%.2f", effectiveRange),
                ctx.getAttackRangeSource(), compensation, ctx.getTargetType(), ctx.getPingMillis());
        callback.onViolation(this, amount, debug);
    }
}
