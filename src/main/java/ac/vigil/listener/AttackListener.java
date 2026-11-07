package ac.vigil.listener;

import java.util.function.Supplier;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import ac.vigil.check.CheckManager;
import ac.vigil.check.ViolationPipeline;
import ac.vigil.config.VigilACConfig;
import ac.vigil.data.PlayerData;
import ac.vigil.data.PlayerDataManager;
import ac.vigil.detection.AttackContext;
import ac.vigil.environment.AttackRangeResolver;
import ac.vigil.exempt.ExemptionManager;

/**
 * Feeds melee attacks to the combat checks.
 *
 * <p>The damage event path is complemented by the packet-layer attack-attempt
 * pipeline (see {@code AttackAttemptDispatcher}): rejected attacks never reach
 * this listener, so they cannot be evaluated here — they surface as monitor
 * evidence instead (SL-1). Distance is evaluated against the target's
 * axis-aligned bounding box derived from its width/height, which is correct
 * for every entity type.</p>
 */
public final class AttackListener implements Listener {

    private final Supplier<VigilACConfig> config;
    private final PlayerDataManager dataManager;
    private final CheckManager checkManager;
    private final ViolationPipeline pipeline;
    private final ExemptionManager exemptions;

    public AttackListener(Supplier<VigilACConfig> config,
                          PlayerDataManager dataManager,
                          CheckManager checkManager,
                          ViolationPipeline pipeline,
                          ExemptionManager exemptions) {
        this.config = config;
        this.dataManager = dataManager;
        this.checkManager = checkManager;
        this.pipeline = pipeline;
        this.exemptions = exemptions;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) {
            return;
        }
        Entity target = event.getEntity();
        if (target == null) {
            return;
        }
        VigilACConfig cfg = config.get();
        if (attacker.hasPermission(cfg.exemptPermission())) {
            return;
        }
        if (exemptions.isExempt(attacker.getUniqueId(), ExemptionManager.ALL)) {
            return;
        }

        org.bukkit.Location eye = attacker.getEyeLocation();
        org.bukkit.Location targetLoc = target.getLocation();
        org.bukkit.Location attackerLoc = attacker.getLocation();
        double halfWidth = target.getWidth() / 2.0D;
        double height = target.getHeight();

        boolean creativeOrSpectator = switch (attacker.getGameMode()) {
            case CREATIVE, SPECTATOR -> true;
            default -> false;
        };

        org.bukkit.util.Vector targetVelocity = target.getVelocity();
        org.bukkit.inventory.ItemStack mainHand = attacker.getInventory().getItemInMainHand();
        AttackRangeResolver.Result range = AttackRangeResolver.resolve(mainHand);

        PlayerData data = dataManager.load(attacker.getUniqueId());
        long previousAttackAt = data.combatTracker().getLastAttackAt();
        long nowMillis = System.currentTimeMillis();

        AttackContext context = AttackContext.builder()
                .attacker(attacker.getUniqueId())
                .targetType(target.getType().name())
                .eye(eye.getX(), eye.getY(), eye.getZ())
                .box(targetLoc.getX() - halfWidth, targetLoc.getY(), targetLoc.getZ() - halfWidth,
                     targetLoc.getX() + halfWidth, targetLoc.getY() + height, targetLoc.getZ() + halfWidth)
                .creativeOrSpectator(creativeOrSpectator)
                .attackerInVehicle(attacker.isInsideVehicle())
                .targetInVehicle(target.isInsideVehicle())
                .pingMillis(attacker.getPing())
                .timestamp(nowMillis)
                .targetUniqueId(target.getUniqueId().toString())
                .yaw(attackerLoc.getYaw())
                .pitch(attackerLoc.getPitch())
                .previousAttackAtMillis(previousAttackAt > 0L ? previousAttackAt : -1L)
                .attackRangeMax(range.getMaxRange())
                .attackRangeSource(range.getSource() == AttackRangeResolver.Source.COMPONENT ? "component" : "unknown")
                .targetVelocity(targetVelocity.getX(), targetVelocity.getY(), targetVelocity.getZ())
                .lagCompensationEnabled(cfg.reachLagCompensation())
                .lagCompensationMaxBlocks(cfg.reachLagCompensationMaxBlocks())
                .build();

        // Pair with the packet-layer attempt, if any: the damage event confirms
        // the swing connected — attempts that never pair are classified rejected.
        data.reachEvidenceLedger().markDamaged(target.getEntityId(), nowMillis, 400L);

        // Combat statistics are recorded exactly once per attack here, not per
        // check, so CPS/interval windows are never double counted.
        data.combatTracker().recordAttack(context.getTimestamp());
        data.monitor().noteAttack(target.getType().name());
        checkManager.evaluateAttack(context, data, pipeline.forPlayer(attacker, data));
    }
}
