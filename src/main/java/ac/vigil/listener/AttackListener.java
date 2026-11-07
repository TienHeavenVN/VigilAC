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
import ac.vigil.exempt.ExemptionManager;

/**
 * Feeds melee attacks to the combat checks.
 *
 * <p>Only EntityDamageByEntityEvent is used: it is a main-thread, fully
 * reliable attack signal (attacking an entity always deals damage, which is
 * what triggers this event). Distance is evaluated against the target's
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
                .timestamp(System.currentTimeMillis())
                .targetUniqueId(target.getUniqueId().toString())
                .yaw(attackerLoc.getYaw())
                .pitch(attackerLoc.getPitch())
                .build();

        PlayerData data = dataManager.load(attacker.getUniqueId());
        // Combat statistics are recorded exactly once per attack here, not per
        // check, so CPS/interval windows are never double counted.
        data.combatTracker().recordAttack(context.getTimestamp());
        data.monitor().noteAttack(target.getType().name());
        checkManager.evaluateAttack(context, data, pipeline.forPlayer(attacker, data));
    }
}
