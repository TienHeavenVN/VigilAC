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

    private static final String[] KNOWN_CHECKS = {
        "speed", "reach", "fly", "timer", "no-fall", "invalid-movement",
        "kill-aura", "auto-clicker", "aim-assist", "scaffold", "fast-place", "fast-break"
    };

    /**
     * Validates every known check's VL ladder against the configured values
     * and returns human-readable problems (empty when coherent). Callers
     * should log each problem loudly at boot/reload.
     */
    public List<String> validate() {
        java.util.Map<String, double[]> ladders = new java.util.LinkedHashMap<>();
        for (String name : KNOWN_CHECKS) {
            CheckSettings settings = checkSettings(name);
            ladders.put(name, new double[] {settings.getAlertVl(), settings.getWarnVl(), settings.getKickVl(), settings.getMaxVl()});
        }
        return ConfigValidator.validateAll(ladders);
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

    public long flyMinHoverMillis() { return (long) (getDouble("checks.fly.min-hover-millis", 3_000.0D)); }
    public double flyHoverTolerance() { return getDouble("checks.fly.hover-tolerance", 0.6D); }
    public int flyMinUpwardTicks() { return getInt("checks.fly.min-upward-ticks", 10); }
    public double flyVlPerObservation() { return getDouble("checks.fly.vl-per-observation", 1.0D); }

    public double timerThresholdPerSecond() { return getDouble("checks.timer.threshold-per-second", 21.5D); }
    public long timerMinExcessMillis() { return (long) (getDouble("checks.timer.min-excess-millis", 3_000.0D)); }
    public double timerExcessVlPerSecond() { return getDouble("checks.timer.excess-vl-per-second", 2.0D); }

    public double noFallMinFallDistance() { return getDouble("checks.no-fall.min-fall-distance", 3.5D); }
    public long noFallDamageGraceMillis() { return (long) (getDouble("checks.no-fall.damage-grace-millis", 500.0D)); }
    public int noFallMinSuspiciousLandings() { return getInt("checks.no-fall.min-suspicious-landings", 3); }
    public double noFallVlPerLanding() { return getDouble("checks.no-fall.vl-per-landing", 1.5D); }

    public double invalidMaxVerticalDelta() { return getDouble("checks.invalid-movement.max-vertical-delta", 4.25D); }
    public double invalidVlPerImpossibility() { return getDouble("checks.invalid-movement.vl-per-impossibility", 4.0D); }

    public long killAuraWindowMillis() { return (long) (getDouble("checks.kill-aura.window-millis", 1_000.0D)); }
    public int killAuraMinTargets() { return getInt("checks.kill-aura.min-targets", 3); }
    public double killAuraVlPerTrigger() { return getDouble("checks.kill-aura.vl-per-trigger", 1.0D); }

    public double autoClickerCpsCap() { return getDouble("checks.auto-clicker.cps-cap", 20.5D); }
    public int autoClickerMinSamples() { return getInt("checks.auto-clicker.min-samples", 10); }
    public double autoClickerRoboticMeanMax() { return getDouble("checks.auto-clicker.robotic-mean-max", 150.0D); }
    public double autoClickerRoboticStddevMax() { return getDouble("checks.auto-clicker.robotic-stddev-max", 12.0D); }
    public int autoClickerRoboticMinSamples() { return getInt("checks.auto-clicker.robotic-min-samples", 10); }
    public int autoClickerRoboticMinStreak() { return getInt("checks.auto-clicker.robotic-min-streak", 5); }
    public double autoClickerVlPerTrigger() { return getDouble("checks.auto-clicker.vl-per-trigger", 1.0D); }

    public double aimAssistSnapAngularDelta() { return getDouble("checks.aim-assist.snap-angular-delta", 45.0D); }
    public double aimAssistSnapAngleToTarget() { return getDouble("checks.aim-assist.snap-angle-to-target", 6.0D); }
    public long aimAssistSnapMaxIntervalMillis() { return (long) (getDouble("checks.aim-assist.snap-max-interval-millis", 120.0D)); }
    public int aimAssistMinSnapStreak() { return getInt("checks.aim-assist.min-snap-streak", 4); }
    public double aimAssistVlPerSnap() { return getDouble("checks.aim-assist.vl-per-snap", 1.0D); }

    public double scaffoldMaxAngleDegrees() { return getDouble("checks.scaffold.max-angle-degrees", 80.0D); }
    public int scaffoldMinStreak() { return getInt("checks.scaffold.min-streak", 6); }
    public double scaffoldVlPerPlacement() { return getDouble("checks.scaffold.vl-per-placement", 1.0D); }

    public double fastPlaceMaxPerSecond() { return getDouble("checks.fast-place.max-per-second", 7.0D); }
    public long fastPlaceWindowMillis() { return (long) (getDouble("checks.fast-place.window-millis", 2_000.0D)); }
    public double fastPlaceVlPerPlacement() { return getDouble("checks.fast-place.vl-per-placement", 1.0D); }

    public int fastBreakMinImpossibleStreak() { return getInt("checks.fast-break.min-impossible-streak", 3); }
    public double fastBreakVlPerTrigger() { return getDouble("checks.fast-break.vl-per-trigger", 1.0D); }
    public double fastBreakUnbreakableVl() { return getDouble("checks.fast-break.unbreakable-vl", 6.0D); }

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
