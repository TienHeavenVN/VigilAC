package ac.vigil.packet;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import ac.vigil.data.PlayerDataManager;

/**
 * Best-effort PacketEvents integration.
 *
 * <p>PacketEvents is an optional (soft-depend) integration. This class is
 * deliberately free of any packetevents references: when packetevents is not
 * installed, the gate below returns before the implementation classes
 * ({@link PacketEventsMetrics} / {@link PacketEventsTelemetry}) are ever
 * resolved, so the JVM never loads the packetevents API on servers without
 * the plugin. All netty-thread state lives in fixed-capacity synchronized
 * trackers; no Bukkit API is touched off the main thread.</p>
 *
 * <p>The returned {@link Handle} must be unregistered on plugin disable so a
 * /reload never leaves stale listeners double-counting packets.</p>
 */
public final class PacketEventsHook {

    private PacketEventsHook() {
    }

    /**
     * Registers the metrics and structured-telemetry listeners if packetevents
     * is present. Never throws. When packetevents is absent, per-player trackers
     * simply stay empty and every check degrades to its event-driven path.</p>
     */
    public static Handle tryRegister(JavaPlugin plugin, PlayerDataManager dataManager) {
        return tryRegister(plugin, dataManager, null);
    }

    /**
     * Same registration, plus the attack-attempt sink fed from netty threads.
     * Pass {@code null} to keep pre-Phase-7 behaviour (no attack-attempt
     * capture); the dispatcher is an internal listener package class and is
     * deliberately not referenced here.
     */
    public static Handle tryRegister(JavaPlugin plugin, PlayerDataManager dataManager,
                                     AttackAttemptSink attackAttemptSink) {
        Plugin packetEvents = plugin.getServer().getPluginManager().getPlugin("packetevents");
        if (packetEvents == null || !packetEvents.isEnabled()) {
            plugin.getLogger().info("packetevents not found; packet metrics/telemetry disabled (all checks work regardless)");
            return new Handle(null, null, "absent");
        }
        try {
            String version = packetEvents.getDescription().getVersion();
            Object metrics = PacketEventsMetrics.register(plugin, dataManager, version);
            Object telemetry = PacketEventsTelemetry.register(plugin, dataManager, version, attackAttemptSink);
            plugin.getLogger().info("Hooked into packetevents " + version + " (metrics + telemetry)");
            return new Handle(metrics, telemetry, "hooked packetevents " + version);
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Failed to hook into packetevents; packet metrics/telemetry disabled: " + throwable);
            return new Handle(null, null, "failed: " + throwable.getClass().getSimpleName());
        }
    }

    /**
     * Holds the registered packetevents listeners (or none) and the human-readable
     * outcome. {@link #unregister()} is idempotent and safe to call at any time.
     */
    public static final class Handle {
        /** Object packetevents registered (a PacketListenerCommon), kept as Object so this class never hard-references packetevents. */
        private final Object registeredListener;
        private final Object registeredTelemetry;
        private final String status;

        private Handle(Object registeredListener, Object registeredTelemetry, String status) {
            this.registeredListener = registeredListener;
            this.registeredTelemetry = registeredTelemetry;
            this.status = status;
        }

        /** Human-readable outcome, e.g. {@code hooked packetevents 2.14.0}, {@code absent}, {@code failed: NoClassDefFoundError}. */
        public String status() {
            return status;
        }

        public boolean isAvailable() {
            return registeredListener != null || registeredTelemetry != null;
        }

        /** Unregister both packet listeners packetevents holds; call on plugin disable. Safe to call twice. */
        public void unregister() {
            // Never even touch the packetevents classes when nothing was registered
            // (keeps the packetevents-absent server free of any PE class loading).
            if (registeredListener == null && registeredTelemetry == null) {
                return;
            }
            PacketEventsMetrics.unregister(registeredListener);
            PacketEventsTelemetry.unregister(registeredTelemetry);
        }
    }
}
