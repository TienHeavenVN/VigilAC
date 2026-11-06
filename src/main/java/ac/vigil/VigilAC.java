package ac.vigil;

import java.io.File;
import ac.vigil.alert.AlertManager;
import ac.vigil.check.CheckManager;
import ac.vigil.check.ViolationPipeline;
import ac.vigil.check.combat.ReachCheck;
import ac.vigil.check.movement.SpeedCheck;
import ac.vigil.command.VigilACCommand;
import ac.vigil.config.CheckSettings;
import ac.vigil.config.VigilACConfig;
import ac.vigil.data.PlayerData;
import ac.vigil.data.PlayerDataManager;
import ac.vigil.exempt.ExemptionManager;
import ac.vigil.listener.AttackListener;
import ac.vigil.listener.ConfounderListener;
import ac.vigil.listener.PlayerJoinQuitListener;
import ac.vigil.listener.PlayerMoveListener;
import ac.vigil.packet.PacketEventsHook;
import ac.vigil.punishment.PunishmentManager;
import ac.vigil.storage.DatabaseManager;
import ac.vigil.violation.ViolationManager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * VigilAC — production-oriented anti-cheat for Paper servers.
 *
 * <p>Lifecycle: constructs configuration, storage and detection subsystems at
 * boot; registers checks; wires listeners and the command tree; schedules VL
 * decay. Default mode is {@code alert-only}; punishments require an explicit
 * {@code mode: punish} in config.yml.</p>
 */
public final class VigilAC extends JavaPlugin {

    private volatile VigilACConfig config;
    private PlayerDataManager playerDataManager;
    private CheckManager checkManager;
    private ViolationManager violationManager;
    private DatabaseManager databaseManager;
    private AlertManager alertManager;
    private PunishmentManager punishmentManager;
    private ExemptionManager exemptionManager;
    private ViolationPipeline pipeline;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.config = new VigilACConfig(this);

        this.exemptionManager = new ExemptionManager();
        this.playerDataManager = new PlayerDataManager();
        this.checkManager = new CheckManager();
        registerChecks(config);

        this.alertManager = new AlertManager();

        File storageFile = new File(getDataFolder(), config.storageFile());
        this.databaseManager = new DatabaseManager(storageFile, config.storageEnabled(), getLogger());
        this.databaseManager.start();

        this.violationManager = new ViolationManager(this::settingsFor);

        this.punishmentManager = new PunishmentManager(this::getVigilacConfig, alertManager, databaseManager);
        this.violationManager.addListener(punishmentManager::handle);

        this.pipeline = new ViolationPipeline(this::getVigilacConfig, exemptionManager, violationManager, alertManager);

        getServer().getPluginManager().registerEvents(new PlayerJoinQuitListener(playerDataManager, exemptionManager), this);
        getServer().getPluginManager().registerEvents(
                new PlayerMoveListener(this::getVigilacConfig, playerDataManager, checkManager, pipeline, exemptionManager), this);
        getServer().getPluginManager().registerEvents(
                new AttackListener(this::getVigilacConfig, playerDataManager, checkManager, pipeline, exemptionManager), this);
        getServer().getPluginManager().registerEvents(
                new ConfounderListener(playerDataManager, this::getVigilacConfig), this);

        PacketEventsHook.tryRegister(this, playerDataManager);

        VigilACCommand command = new VigilACCommand(this);
        if (getCommand("vigilac") != null) {
            getCommand("vigilac").setExecutor(command);
            getCommand("vigilac").setTabCompleter(command);
        } else {
            getLogger().severe("Command 'vigilac' missing from plugin.yml — /vigilac will not work");
        }

        // VL decay + storage flush ride the same 1s timer.
        getServer().getScheduler().runTaskTimer(this, this::maintenanceTick, 20L, 20L);

        getLogger().info("VigilAC enabled: " + checkManager.getCount() + " checks registered, mode="
                + (config.isAlertOnly() ? "alert-only" : "punish"));
    }

    @Override
    public void onDisable() {
        if (databaseManager != null) {
            databaseManager.close();
        }
        if (getServer().getScheduler() != null) {
            getServer().getScheduler().cancelTasks(this);
        }
        playerDataManager = null;
    }

    /** Re-reads config.yml and swaps typed views; check tuning takes effect immediately. */
    public void reloadConfiguration() {
        reloadConfig();
        this.config = new VigilACConfig(this);
        getLogger().info("Configuration reloaded (mode=" + (config.isAlertOnly() ? "alert-only" : "punish") + ")");
    }

    private void registerChecks(VigilACConfig cfg) {
        checkManager.register(new SpeedCheck(
                cfg.speedWalkCap(),
                cfg.speedSprintCap(),
                cfg.speedPotionBonus(),
                cfg.speedSlipperyMultiplier(),
                cfg.speedExcessVlPerSecond(),
                cfg.speedMinSustainSeconds()));
        checkManager.register(new ReachCheck(
                cfg.reachMaxReach(),
                cfg.reachTolerance(),
                cfg.reachMinAttacksOver(),
                cfg.reachVlPerAttackOver(),
                cfg.reachEmaSamples()));
    }

    private CheckSettings settingsFor(String checkName) {
        return getVigilacConfig().checkSettings(checkName);
    }

    private void maintenanceTick() {
        VigilACConfig cfg = getVigilacConfig();
        double decayPerTick = Math.max(0.0D, cfg.decayPerSecond()); // one tick == one second
        long delay = cfg.decayDelayMillis();
        for (PlayerData data : playerDataManager.getAll()) {
            data.decay(decayPerTick, delay);
        }
        if (databaseManager != null) {
            databaseManager.flushIfDue();
        }
    }

    public VigilACConfig getVigilacConfig() {
        return config;
    }

    public PlayerDataManager getPlayerDataManager() {
        return playerDataManager;
    }

    public CheckManager getCheckManager() {
        return checkManager;
    }

    public ViolationManager getViolationManager() {
        return violationManager;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public AlertManager getAlertManager() {
        return alertManager;
    }

    public PunishmentManager getPunishmentManager() {
        return punishmentManager;
    }

    public ExemptionManager getExemptionManager() {
        return exemptionManager;
    }
}
