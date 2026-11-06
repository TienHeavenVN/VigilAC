package ac.vigil.packet;

import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import ac.vigil.data.PlayerDataManager;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.event.UserConnectEvent;
import com.github.retrooper.packetevents.event.UserDisconnectEvent;
import com.github.retrooper.packetevents.event.UserLoginEvent;
import com.github.retrooper.packetevents.event.PacketEvent;

/**
 * Best-effort PacketEvents integration.
 *
 * <p>PacketEvents is an optional (soft-depend) integration: when the
 * packetevents plugin is present, a listener is registered that maintains
 * per-player packet throughput counters used by {@code /vigilac info}.
 * All counter updates are plain atomics/volatile on the netty thread; no
 * Bukkit API is touched off the main thread.</p>
 *
 * <p>If packetevents is absent (or an incompatible version is installed),
 * the plugin continues without packet metrics and logs a warning.</p>
 */
public final class PacketEventsHook {

    private PacketEventsHook() {
    }

    /** Returns true when the packet metrics listener was registered. */
    public static boolean tryRegister(JavaPlugin plugin, PlayerDataManager dataManager) {
        Plugin packetEvents = plugin.getServer().getPluginManager().getPlugin("packetevents");
        if (packetEvents == null || !packetEvents.isEnabled()) {
            plugin.getLogger().info("packetevents not found; packet throughput metrics are disabled (all checks work regardless)");
            return false;
        }
        try {
            PacketEvents.getAPI()
                    .getEventManager()
                    .registerListener(new MetricsListener(dataManager, plugin.getLogger()), PacketListenerPriority.NORMAL);
            plugin.getLogger().info("Hooked into packetevents " + packetEvents.getDescription().getVersion());
            return true;
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Failed to hook into packetevents; packet metrics disabled: " + throwable);
            return false;
        }
    }

    /** Counts incoming packets per player using atomics only (netty-thread safe). */
    public static final class MetricsListener implements PacketListener {

        private final PlayerDataManager dataManager;
        private final Logger logger;
        private boolean warned;

        MetricsListener(PlayerDataManager dataManager, Logger logger) {
            this.dataManager = dataManager;
            this.logger = logger;
        }

        @Override
        public void onPacketReceive(PacketReceiveEvent event) {
            try {
                var user = event.getUser();
                UUID uuid = user == null ? null : user.getUUID();
                if (uuid == null) {
                    return;
                }
                var data = dataManager.get(uuid);
                if (data != null) {
                    data.notePacket();
                }
            } catch (Throwable throwable) {
                if (!warned) {
                    warned = true;
                    logger.warning("packetevents metrics failed once (further failures suppressed): " + throwable);
                }
            }
        }

        @Override
        public void onPacketSend(PacketSendEvent event) {
            // not needed
        }

        @Override
        public void onUserConnect(UserConnectEvent event) {
            // not needed
        }

        @Override
        public void onUserLogin(UserLoginEvent event) {
            // not needed
        }

        @Override
        public void onUserDisconnect(UserDisconnectEvent event) {
            // not needed
        }

        @Override
        public void onPacketEventExternal(PacketEvent event) {
            // not needed
        }
    }
}
