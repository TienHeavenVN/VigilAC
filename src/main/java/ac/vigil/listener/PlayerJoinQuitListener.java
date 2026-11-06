package ac.vigil.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import ac.vigil.data.PlayerDataManager;
import ac.vigil.exempt.ExemptionManager;

public final class PlayerJoinQuitListener implements Listener {

    private final PlayerDataManager dataManager;
    private final ExemptionManager exemptions;

    public PlayerJoinQuitListener(PlayerDataManager dataManager, ExemptionManager exemptions) {
        this.dataManager = dataManager;
        this.exemptions = exemptions;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        dataManager.load(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        // Remove state to avoid memory leaks; DB rows (if any) persist for audit.
        dataManager.unload(event.getPlayer().getUniqueId());
        exemptions.clear(event.getPlayer().getUniqueId());
    }
}
