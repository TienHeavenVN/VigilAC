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
        // A zero-delta packet claims no motion; it still carries the grounded
        // bit. Vanilla players land with an onGround=true, zero-motion packet,
        // so returning here unconditionally would discard the only no-fall
        // evidence (the landing) entirely. The downstream checks are all
        // delta-relative and behave correctly on zero deltas, and the packet
        // counts toward the timer rate.

        org.bukkit.entity.Player player = event.getPlayer();
        VigilACConfig cfg = config.get();

        PlayerData data = dataManager.load(player.getUniqueId());
        // Every move packet counts toward the timer rate, including rotation-only
        // ones; counted before any early return so the ring is never biased.
        long nowPacket = System.currentTimeMillis();
        data.movementTracker().noteMovePacket(nowPacket);
        // Bukkit coalesces position events to at most ~20/s on Paper; the optional
        // packetevents integration records the true pre-coalescing packet rate.
        double movePacketsPerSecond = Math.max(
                data.movementTracker().movePacketsPerSecond(nowPacket),
                data.movementTracker().rawMovePacketsPerSecond(nowPacket));

        if (player.hasPermission(cfg.exemptPermission())) {
            return;
        }
        if (exemptions.isExempt(player.getUniqueId(), ExemptionManager.ALL)) {
            return;
        }

        // Relative teleports inside one event: mark grace, record for the monitor, skip.
        if (from.distanceSquared(to) > cfg.teleportThreshold() * cfg.teleportThreshold()) {
            data.setTeleportGraceUntil(System.currentTimeMillis() + cfg.teleportGraceMillis());
            data.monitor().noteTeleport("relative-delta");
            return;
        }

        Block feet = to.getBlock();
        Block head = feet.getRelative(0, 1, 0);
        boolean inLiquid = feet.isLiquid();
        // Feet are dry but the block directly below is water: vanilla-impossible
        // unless the boots carry Frost Walker. Frost Walker converts water to
        // ice (feet end on ice, not water), but a fresh layer of frost that
        // re-melts between ticks can still read as water-below on one tick —
        // hence the frostWalker flag is reported separately instead of folding
        // it into inLiquid.
        boolean onWaterSurface = !inLiquid && feet.getRelative(0, -1, 0).getType() == Material.WATER;
        org.bukkit.inventory.ItemStack boots = player.getInventory().getBoots();
        boolean frostWalker = boots != null && boots.containsEnchantment(org.bukkit.enchantments.Enchantment.FROST_WALKER);
        boolean slippery = isSlippery(feet.getType());
        boolean belowFeetSlippery = isSlippery(from.getBlock().getRelative(0, -1, 0).getType())
                || isSlippery(feet.getRelative(0, -1, 0).getType());
        boolean inWeb = feet.getType() == org.bukkit.Material.COBWEB || head.getType() == org.bukkit.Material.COBWEB;
        org.bukkit.Material feetType = feet.getType();
        boolean onSlowBlock = feetType == org.bukkit.Material.SOUL_SAND
                || feetType == org.bukkit.Material.SOUL_SOIL
                || feetType == org.bukkit.Material.HONEY_BLOCK
                || feetType == org.bukkit.Material.SWEET_BERRY_BUSH;

        int speedLevel = 0;
        boolean levitation = false;
        boolean slowFalling = false;
        for (PotionEffect effect : player.getActivePotionEffects()) {
            if (effect.getType() == PotionEffectType.SPEED) {
                speedLevel = effect.getAmplifier() + 1;
            } else if (effect.getType() == PotionEffectType.LEVITATION) {
                levitation = true;
            } else if (effect.getType() == PotionEffectType.SLOW_FALLING) {
                slowFalling = true;
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
                .belowFeetSlippery(belowFeetSlippery)
                .riptiding(player.isRiptiding())
                .windBurstGrace(data.isWindBurstGrace())
                .inWeb(inWeb)
                .onSlowBlock(onSlowBlock)
                .onWaterSurface(onWaterSurface)
                .frostWalker(frostWalker)
                .teleportGrace(data.isTeleportGrace())
                .knockbackGrace(data.isKnockbackGrace())
                .speedPotionLevel(speedLevel)
                .movePacketsPerSecond(movePacketsPerSecond)
                .levitation(levitation)
                .slowFalling(slowFalling)
                .pingMillis(player.getPing())
                .serverTps(tps)
                .timestamp(System.currentTimeMillis())
                .build();

        data.monitor().noteMove(ac.vigil.monitor.MoveSample.builder()
                .at(to.getX(), to.getY(), to.getZ())
                .rotation(to.getYaw(), to.getPitch())
                .onGround(player.isOnGround())
                .sprinting(player.isSprinting())
                .sneaking(player.isSneaking())
                .gliding(player.isGliding())
                .swimming(player.isSwimming())
                .inVehicle(player.isInsideVehicle())
                .levitation(levitation)
                .slowFalling(slowFalling)
                .speedPotionLevel(speedLevel)
                .pingMillis((int) player.getPing())
                .serverTps(tps)
                .worldName(to.getWorld().getName())
                .gameMode(player.getGameMode().name())
                .timestamp(System.currentTimeMillis())
                .build());

        checkManager.evaluateMovement(context, data, pipeline.forPlayer(player, data));
    }

    private static boolean isSlippery(Material material) {
        String name = material.name();
        return name.contains("ICE") || material == Material.SLIME_BLOCK || material == Material.HONEY_BLOCK;
    }
}
