package ac.vigil.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import ac.vigil.data.PlayerData;
import ac.vigil.data.PlayerDataManager;

/**
 * Records inventory clicks for the per-player monitor. Observability only —
 * no check consumes this signal yet; it exists so the monitor keeps a bounded
 * trail of inventory activity (view type only) as context for future
 * inventory-related analysis, at O(1) cost per click.
 */
public final class InventoryListener implements Listener {

    private final PlayerDataManager dataManager;

    public InventoryListener(PlayerDataManager dataManager) {
        this.dataManager = dataManager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        PlayerData data = dataManager.get(player.getUniqueId());
        if (data == null) {
            return; // never observed this player; don't manufacture data on the inventory path
        }
        data.monitor().noteInventoryClick(String.valueOf(event.getView().getType()));
    }
}
