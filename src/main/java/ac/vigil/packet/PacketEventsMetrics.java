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

    /**
     * Registers the metrics listener and returns the object packetevents actually
     * registered (a {@code PacketListenerAbstract} wrapping our listener, since
     * packetevents wraps {@link PacketListener} implementations via
     * {@code PacketListener#asAbstract(PacketListenerPriority)}). The caller must
     * keep this handle and pass it back to {@link #unregister(Object)}.
     */
    static Object register(JavaPlugin plugin, PlayerDataManager dataManager, String version) {
        return PacketEvents.getAPI()
                .getEventManager()
                .registerListener(new MetricsListener(dataManager, plugin.getLogger()), PacketListenerPriority.NORMAL);
    }

    /** Unregisters the object packetevents registered (called on plugin disable). Never throws. */
    static void unregister(Object registeredListener) {
        if (registeredListener == null) {
            return;
        }
        try {
            PacketEvents.getAPI().getEventManager().unregisterListener((com.github.retrooper.packetevents.event.PacketListenerCommon) registeredListener);
        } catch (Throwable throwable) {
            // packetevents itself is already unloading (or gone): nothing to unregister from.
        }
    }

    /** Counts incoming packets per player using atomics only (netty-thread safe). */
    public static final class MetricsListener implements PacketListener {

        private final PlayerDataManager dataManager;
        private final Logger logger;
        private final java.util.concurrent.atomic.AtomicBoolean warned = new java.util.concurrent.atomic.AtomicBoolean();

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
                if (data == null) {
                    return;
                }
                data.notePacket();
                String typeName = String.valueOf(event.getPacketType());
                if (isMovementType(typeName)) {
                    data.movementTracker().noteRawMovePacket(System.currentTimeMillis());
                }
            } catch (Throwable throwable) {
                if (warned.compareAndSet(false, true)) {
                    logger.warning("packetevents metrics failed once (further failures suppressed): " + throwable);
                }
            }
        }

        /** Broad, version-tolerant match for the four client movement packet types. */
        private static boolean isMovementType(String typeName) {
            String upper = typeName.toUpperCase(java.util.Locale.ROOT);
            return upper.contains("POSITION") || upper.contains("ROTATION") || upper.contains("FLYING");
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
