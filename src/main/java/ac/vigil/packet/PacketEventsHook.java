package ac.vigil.packet;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import ac.vigil.data.PlayerDataManager;

/**
 * Best-effort PacketEvents integration.
 *
 * <p>PacketEvents is an optional (soft-depend) integration. This class is
 * deliberately free of any packetevents references: when packetevents is not
 * installed, the gate below returns before the implementation class
 * ({@link PacketEventsMetrics}) is ever resolved, so the JVM never loads the
 * packetevents API on servers without the plugin. All counters updated from
 * the netty thread are plain atomics; no Bukkit API is touched off the main
 * thread.</p>
 *
 * <p>The returned {@link Handle} must be unregistered on plugin disable so a
 * /reload never leaves stale listeners double-counting packets.</p>
 */
public final class PacketEventsHook {

    private PacketEventsHook() {
    }

    /** Registers the metrics listener if packetevents is present. Never throws. */
    public static Handle tryRegister(JavaPlugin plugin, PlayerDataManager dataManager) {
        Plugin packetEvents = plugin.getServer().getPluginManager().getPlugin("packetevents");
        if (packetEvents == null || !packetEvents.isEnabled()) {
            plugin.getLogger().info("packetevents not found; packet throughput metrics are disabled (all checks work regardless)");
            return new Handle(null, "absent");
        }
        try {
            String version = packetEvents.getDescription().getVersion();
            Object registered = PacketEventsMetrics.register(plugin, dataManager, version);
            plugin.getLogger().info("Hooked into packetevents " + version);
            return new Handle(registered, "hooked packetevents " + version);
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Failed to hook into packetevents; packet metrics disabled: " + throwable);
            return new Handle(null, "failed: " + throwable.getClass().getSimpleName());
        }
    }

    /**
     * Holds the registered packetevents listener (or none) and the human-readable
     * outcome. {@link #unregister()} is idempotent and safe to call at any time.
     */
    public static final class Handle {
        /** Object packetevents registered (a PacketListenerCommon), kept as Object so this class never hard-references packetevents. */
        private final Object registeredListener;
        private final String status;

        private Handle(Object registeredListener, String status) {
            this.registeredListener = registeredListener;
            this.status = status;
        }

        /** Human-readable outcome, e.g. {@code hooked packetevents 2.14.0}, {@code absent}, {@code failed: NoClassDefFoundError}. */
        public String status() {
            return status;
        }

        public boolean isAvailable() {
            return registeredListener != null;
        }

        /** Unregister the packet listener packetevents holds; call on plugin disable. Safe to call twice. */
        public void unregister() {
            // Never even touch PacketEventsMetrics when nothing was registered
            // (keeps the packetevents-absent server free of any PE class loading).
            if (registeredListener == null) {
                return;
            }
            PacketEventsMetrics.unregister(registeredListener);
        }
    }
}
