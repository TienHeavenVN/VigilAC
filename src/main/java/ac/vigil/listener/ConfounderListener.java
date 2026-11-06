package ac.vigil.listener;

import java.util.function.Supplier;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import ac.vigil.config.VigilACConfig;
import ac.vigil.data.PlayerData;
import ac.vigil.data.PlayerDataManager;

/**
 * Tracks server-side movement modifiers that invalidate normal movement
 * expectations: teleports and velocity (knockback). Each sets a timed grace
 * window consulted by movement checks.
 */
public final class ConfounderListener implements Listener {

    private final PlayerDataManager dataManager;
    private final Supplier<VigilACConfig> config;

    public ConfounderListener(PlayerDataManager dataManager, Supplier<VigilACConfig> config) {
        this.dataManager = dataManager;
        this.config = config;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        PlayerData data = dataManager.get(event.getPlayer().getUniqueId());
        if (data != null) {
            data.setTeleportGraceUntil(System.currentTimeMillis() + config.get().teleportGraceMillis());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent event) {
        PlayerData data = dataManager.get(event.getPlayer().getUniqueId());
        if (data == null) {
            return;
        }
        double speed = event.getVelocity().length();
        // Stronger impulses deserve slightly longer grace, capped at 2 seconds.
        long grace = config.get().velocityGraceMillis() + (long) (speed * 100.0D);
        data.setKnockbackGraceUntil(System.currentTimeMillis() + Math.min(grace, 2_000L));
    }
}
