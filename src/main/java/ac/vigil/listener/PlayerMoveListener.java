package ac.vigil.listener;

import java.util.function.Supplier;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import ac.vigil.check.CheckManager;
import ac.vigil.check.ViolationPipeline;
import ac.vigil.config.VigilACConfig;
import ac.vigil.data.PlayerData;
import ac.vigil.data.PlayerDataManager;
import ac.vigil.detection.DetectionContext;
import ac.vigil.exempt.ExemptionManager;

/**
 * Builds {@link DetectionContext} snapshots from move events and dispatches
 * them to the check pipeline.
 *
 * <p>Performance contract: rotations that do not change position bypass all
 * detection work; everything here is O(1). Block reads are limited to the
 * feet block for liquid/slippery detection.</p>
 */
public final class PlayerMoveListener implements Listener {

    private final Supplier<VigilACConfig> config;
    private final PlayerDataManager dataManager;
    private final CheckManager checkManager;
    private final ViolationPipeline pipeline;
    private final ExemptionManager exemptions;

    public PlayerMoveListener(Supplier<VigilACConfig> config,
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
    public void onMove(PlayerMoveEvent event) {
        org.bukkit.Location to = event.getTo();
        org.bukkit.Location from = event.getFrom();
        if (to == null || from == null) {
            return;
        }
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        if (dx == 0.0D && dy == 0.0D && dz == 0.0D) {
            return; // rotation-only event: no detection work
        }

        org.bukkit.entity.Player player = event.getPlayer();
        VigilACConfig cfg = config.get();

        if (player.hasPermission(cfg.exemptPermission())) {
            return;
        }
        if (exemptions.isExempt(player.getUniqueId(), ExemptionManager.ALL)) {
            return;
        }

        PlayerData data = dataManager.load(player.getUniqueId());

        // Relative teleports inside one event: mark grace and skip.
        if (from.distanceSquared(to) > cfg.teleportThreshold() * cfg.teleportThreshold()) {
            data.setTeleportGraceUntil(System.currentTimeMillis() + cfg.teleportGraceMillis());
            return;
        }

        Block feet = to.getBlock();
        boolean inLiquid = feet.isLiquid();
        boolean slippery = isSlippery(feet.getType());

        int speedLevel = 0;
        for (PotionEffect effect : player.getActivePotionEffects()) {
            if (effect.getType() == PotionEffectType.SPEED) {
                speedLevel = effect.getAmplifier() + 1;
                break;
            }
        }

        double[] tpsValues = org.bukkit.Bukkit.getServer().getTPS();
        double tps = tpsValues.length > 0 ? tpsValues[0] : 20.0D;

        DetectionContext context = DetectionContext.builder()
                .player(player.getUniqueId())
                .worldName(to.getWorld().getName())
                .from(from.getX(), from.getY(), from.getZ())
                .to(to.getX(), to.getY(), to.getZ())
                .yaw(to.getYaw())
                .pitch(to.getPitch())
                .onGround(player.isOnGround())
                .sprinting(player.isSprinting())
                .sneaking(player.isSneaking())
                .gliding(player.isGliding())
                .inVehicle(player.isInsideVehicle())
                .inLiquid(inLiquid)
                .onSlipperyBlock(slippery)
                .teleportGrace(data.isTeleportGrace())
                .knockbackGrace(data.isKnockbackGrace())
                .speedPotionLevel(speedLevel)
                .pingMillis(player.getPing())
                .serverTps(tps)
                .timestamp(System.currentTimeMillis())
                .build();

        checkManager.evaluateMovement(context, data, pipeline.forPlayer(player, data));
    }

    private static boolean isSlippery(Material material) {
        String name = material.name();
        return name.contains("ICE") || material == Material.SLIME_BLOCK || material == Material.HONEY_BLOCK;
    }
}
