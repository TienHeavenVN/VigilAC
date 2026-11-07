package ac.vigil.listener;

import java.util.function.Supplier;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPistonExtendEvent;
import ac.vigil.config.VigilACConfig;
import ac.vigil.data.PlayerData;
import ac.vigil.data.PlayerDataManager;

/**
 * Pistons displace players (and bounce them via slime blocks) without
 * firing velocity or teleport events. Any player standing in the extension
 * corridor receives a short velocity-grace window so movement checks do not
 * punish piston physics. Grace only suppresses checks; it never clears VL.
 */
public final class PistonConfounderListener implements Listener {

    private static final double ENTITY_RADIUS = 1.7D;

    private final PlayerDataManager dataManager;
    private final Supplier<VigilACConfig> config;

    public PistonConfounderListener(PlayerDataManager dataManager, Supplier<VigilACConfig> config) {
        this.dataManager = dataManager;
        this.config = config;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (event.getBlocks().isEmpty()) {
            return;
        }
        org.bukkit.util.Vector dir = event.getDirection().getDirection();
        Block first = event.getBlock();

        for (Player player : event.getBlock().getWorld().getPlayers()) {
            PlayerData data = dataManager.get(player.getUniqueId());
            if (data == null) {
                continue;
            }
            org.bukkit.util.Vector rel = player.getLocation().toVector()
                    .subtract(first.getLocation().toVector().add(new org.bukkit.util.Vector(0.5D, 0.5D, 0.5D)));
            // Project onto the piston direction; within the push corridor if the
            // perpendicular offset is small and the axial offset is inside the line of blocks.
            double axial = rel.dot(dir);
            org.bukkit.util.Vector perp = rel.clone().subtract(dir.clone().multiply(axial));
            if (Math.abs(axial) <= event.getBlocks().size() + 1 && perp.lengthSquared() <= ENTITY_RADIUS * ENTITY_RADIUS) {
                long grace = config.get().velocityGraceMillis();
                data.setKnockbackGraceUntil(System.currentTimeMillis() + Math.max(grace, 1_000L));
            }
        }
    }
}
