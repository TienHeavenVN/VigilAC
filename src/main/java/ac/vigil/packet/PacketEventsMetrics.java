package ac.vigil.packet;

import java.util.UUID;
import java.util.logging.Logger;
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
 * Implementation of the optional packetevents integration. This class and its
 * nested listener reference the packetevents API directly, so it must only be
 * loaded when the packetevents plugin is present — the caller
 * ({@link PacketEventsHook}) guarantees that.
 */
final class PacketEventsMetrics {

    private PacketEventsMetrics() {
    }

    static boolean register(JavaPlugin plugin, PlayerDataManager dataManager, String version) {
        PacketEvents.getAPI()
                .getEventManager()
                .registerListener(new MetricsListener(dataManager, plugin.getLogger()), PacketListenerPriority.NORMAL);
        plugin.getLogger().info("Hooked into packetevents " + version);
        return true;
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
