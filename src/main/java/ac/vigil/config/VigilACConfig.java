package ac.vigil.config;

import java.util.List;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Typed access to {@code config.yml}. Everything reads through this class;
 * reload replaces the instance rather than mutating shared state.
 */
public final class VigilACConfig {

    private final JavaPlugin plugin;

    public VigilACConfig(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    private FileConfiguration cfg() {
        return plugin.getConfig();
    }

    public String mode() {
        return cfg().getString("mode", "alert-only");
    }

    public boolean isAlertOnly() {
        return !"punish".equalsIgnoreCase(mode());
    }

    public CheckSettings checkSettings(String name) {
        String path = "checks." + name;
        boolean enabled = cfg().getBoolean(path + ".enabled", true);
        double alertVl = cfg().getDouble(path + ".alert-vl", 5.0D);
        double warnVl = cfg().getDouble(path + ".warn-vl", 10.0D);
        double kickVl = cfg().getDouble(path + ".kick-vl", 20.0D);
        double maxVl = cfg().getDouble(path + ".max-vl", 25.0D);
        return new CheckSettings(name, enabled, alertVl, warnVl, kickVl, maxVl);
    }

    public double getDouble(String path, double def) {
        return cfg().getDouble(path, def);
    }

    public int getInt(String path, int def) {
        return cfg().getInt(path, def);
    }

    public boolean getBoolean(String path, boolean def) {
        return cfg().getBoolean(path, def);
    }

    public String getString(String path, String def) {
        return cfg().getString(path, def);
    }

    public List<String> getStringList(String path) {
        return cfg().getStringList(path);
    }

    // --- convenience typed accessors (documented defaults in config.yml) ---

    public double speedWalkCap() { return getDouble("checks.speed.walk-cap", 4.4D); }
    public double speedSprintCap() { return getDouble("checks.speed.sprint-cap", 5.8D); }
    public double speedPotionBonus() { return getDouble("checks.speed.speed-potion-bonus", 0.2D); }
    public double speedSlipperyMultiplier() { return getDouble("checks.speed.slippery-multiplier", 1.6D); }
    public double speedExcessVlPerSecond() { return getDouble("checks.speed.excess-vl-per-second", 2.0D); }
    public double speedMinSustainSeconds() { return getDouble("checks.speed.min-sustain-seconds", 1.2D); }

    public double reachMaxReach() { return getDouble("checks.reach.max-reach", 3.0D); }
    public double reachTolerance() { return getDouble("checks.reach.tolerance", 0.03D); }
    public int reachMinAttacksOver() { return getInt("checks.reach.min-attacks-over", 4); }
    public double reachVlPerAttackOver() { return getDouble("checks.reach.vl-per-attack-over", 1.0D); }
    public int reachEmaSamples() { return getInt("checks.reach.ema-samples", 6); }

    public double decayPerSecond() { return getDouble("violations.decay-per-second", 1.0D); }
    public long decayDelayMillis() { return (long) (getDouble("violations.decay-delay-seconds", 5.0D) * 1000.0D); }

    public boolean storageEnabled() { return getBoolean("storage.enabled", true); }
    public String storageFile() { return getString("storage.file", "vigilac-violations.db"); }

    public String kickMessage() { return getString("punishments.kick-message", "&cKicked by VigilAC (reason: %check%)"); }
    public String warnMessage() { return getString("punishments.warn-message", "&e[VigilAC] Warning: unusual %check% activity detected."); }
    public List<String> commandsOnWarn() { return getStringList("punishments.commands-on-warn"); }
    public List<String> commandsOnKick() { return getStringList("punishments.commands-on-kick"); }

    /** Horizontal distance in one event treated as a teleport, not movement. */
    public double teleportThreshold() { return getDouble("confounders.teleport-threshold", 8.0D); }
    /** Grace window after a teleport event. */
    public long teleportGraceMillis() { return (long) (getDouble("confounders.teleport-grace-seconds", 2.0D) * 1000.0D); }
    /** Base grace window after a velocity (knockback) event. */
    public long velocityGraceMillis() { return (long) (getDouble("confounders.velocity-grace-millis", 800.0D)); }

    public boolean debugEnabled() { return getBoolean("debug", false); }
    public String exemptPermission() { return getString("exempt-permission", "vigilac.exempt"); }
}
