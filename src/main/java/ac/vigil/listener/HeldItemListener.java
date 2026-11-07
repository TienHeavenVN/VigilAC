package ac.vigil.listener;

import ac.vigil.data.PlayerData;
import ac.vigil.data.PlayerDataManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemHeldEvent;

/**
 * Tracks main-hand hotbar changes so the digging tracker can mark an
 * in-progress dig as inconclusive (vanilla resets break progress on tool
 * change). Main thread; O(1); no allocation beyond the UUID lookup.
 */
public final class HeldItemListener implements Listener {

    private final PlayerDataManager playerDataManager;

    public HeldItemListener(PlayerDataManager playerDataManager) {
        this.playerDataManager = playerDataManager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemHeld(PlayerItemHeldEvent event) {
        PlayerData data = playerDataManager.get(event.getPlayer().getUniqueId());
        if (data != null) {
            data.diggingTracker().noteHeldItemChange();
        }
    }
}
