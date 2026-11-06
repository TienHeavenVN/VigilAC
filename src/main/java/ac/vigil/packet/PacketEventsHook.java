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
            return PacketEventsMetrics.register(plugin, dataManager, packetEvents.getDescription().getVersion());
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Failed to hook into packetevents; packet metrics disabled: " + throwable);
            return false;
        }
    }
}
