package ac.vigil.check.combat;

import ac.vigil.check.Check;
import ac.vigil.check.CheckCategory;
import ac.vigil.check.ViolationCallback;
import ac.vigil.data.PlayerData;
import ac.vigil.data.tracking.CombatTracker;
import ac.vigil.detection.AttackContext;

/**
 * Detects rapid multi-target melee (kill aura) from the damage stream.
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>Every melee attack's damaged entity id is appended to a short
 *       rolling history; the number of <b>distinct</b> entity ids damaged
 *       within {@code windowMillis} (default 1 s) is computed.</li>
 *   <li>Since 1.9 attack strength is cooldown-gated, a full-damage swing is
 *       limited to ~1.6/s; even uncharged-spam swings (~5/s) cannot
 *       meaningfully re-aim between three separate entities per second.
 *       {@code minTargets} distinct entities inside the window (default 3)
 *       is therefore strong evidence of an aura.</li>
 *   <li>Only the attack that crosses the threshold raises VL (scaled by how
 *       far the count exceeds the threshold), so legitimate crowd control
 *       at the boundary accrues slowly and decays normally.</li>
 * </ol>
 *
 * <h2>False-positive considerations</h2>
 * <ul>
 *   <li>Creative/spectator gamemodes and vehicles are exempt.</li>
 *   <li>Mob-farm sword spam CAN reach the threshold; treat alerts from this
 *       check as stronger-than-speed signals, not proof. In punish mode,
 *       tune {@code min-targets} up for farm-heavy servers.</li>
 *   <li>Latency does not multiply distinct-target counts (positions are
 *       evaluated at receipt).</li>
 * </ul>
 */
public final class KillAuraCheck implements Check<AttackContext> {

    private final long windowMillis;
    private final int minTargets;
    private final double vlPerTrigger;

    public KillAuraCheck(long windowMillis, int minTargets, double vlPerTrigger) {
        this.windowMillis = windowMillis;
        this.minTargets = minTargets;
        this.vlPerTrigger = vlPerTrigger;
    }

    @Override
    public String getName() {
        return "kill-aura";
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

        CombatTracker tracker = data.combatTracker();
        long now = ctx.getTimestamp();
        int distinct = tracker.recordTarget(ctx.getTargetUniqueId(), now, (int) Math.min(Integer.MAX_VALUE, windowMillis));

        if (distinct < minTargets) {
            return;
        }

        double amount = vlPerTrigger * Math.min(2.0D, 1.0D + (distinct - minTargets) * 0.5D);
        String debug = String.format(java.util.Locale.US,
                "distinctTargets=%d window=%dms lastTarget=%s ping=%d",
                distinct, windowMillis, ctx.getTargetUniqueId(), ctx.getPingMillis());
        callback.onViolation(this, amount, debug);
    }
}
