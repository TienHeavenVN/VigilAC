package ac.vigil;

import java.io.File;
import ac.vigil.ai.AiConfig;
import ac.vigil.ai.AiServices;
import ac.vigil.capture.CaptureService;
import ac.vigil.ai.BukkitConfigSource;
import ac.vigil.alert.AlertManager;
import ac.vigil.check.CheckManager;
import ac.vigil.check.ViolationPipeline;
import ac.vigil.check.combat.AimAssistCheck;
import ac.vigil.check.combat.AutoClickerCheck;
import ac.vigil.check.combat.KillAuraCheck;
import ac.vigil.check.combat.ReachCheck;
import ac.vigil.check.movement.FlyCheck;
import ac.vigil.check.movement.InvalidMovementCheck;
import ac.vigil.check.movement.JesusCheck;
import ac.vigil.check.movement.NoFallCheck;
import ac.vigil.check.movement.SpeedCheck;
import ac.vigil.check.movement.StepCheck;
import ac.vigil.check.movement.TimerCheck;
import ac.vigil.check.player.FastBreakCheck;
import ac.vigil.check.player.FastPlaceCheck;
import ac.vigil.check.player.ScaffoldCheck;
import ac.vigil.command.VigilACCommand;
import ac.vigil.config.CheckSettings;
import ac.vigil.config.VigilACConfig;
import ac.vigil.data.PlayerData;
import ac.vigil.data.PlayerDataManager;
import ac.vigil.exempt.ExemptionManager;
import ac.vigil.listener.AttackAttemptDispatcher;
import ac.vigil.listener.AttackListener;
import ac.vigil.listener.BlockBreakListener;
import ac.vigil.listener.BlockPlaceListener;
import ac.vigil.listener.ConfounderListener;
import ac.vigil.listener.PistonConfounderListener;
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
    private PacketEventsHook.Handle packetEventsHandle;
    private AttackAttemptDispatcher attackAttemptDispatcher;
    private AiServices aiServices;
    private CaptureService captureService;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.config = new VigilACConfig(this);
        for (String problem : config.validate()) {
            getLogger().warning("Config: " + problem);
        }

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
        // Consent capture consent/session lifecycle is wired unconditionally; it no-ops when disabled.
        java.util.function.Supplier<String> mcVersionSupplier = () -> getServer().getBukkitVersion();
        this.captureService = new CaptureService(getLogger(), new ac.vigil.ai.BukkitConfigSource(getConfig()),
                playerDataManager, () -> getServer().getTPS()[0], System::currentTimeMillis, mcVersionSupplier);
        getServer().getPluginManager().registerEvents(new ac.vigil.listener.CaptureConsentListener(captureService), this);
        getServer().getPluginManager().registerEvents(
                new PlayerMoveListener(this::getVigilacConfig, playerDataManager, checkManager, pipeline, exemptionManager), this);
        getServer().getPluginManager().registerEvents(
                new AttackListener(this::getVigilacConfig, playerDataManager, checkManager, pipeline, exemptionManager), this);
        getServer().getPluginManager().registerEvents(
                new BlockPlaceListener(this::getVigilacConfig, playerDataManager, checkManager, pipeline, exemptionManager), this);
        getServer().getPluginManager().registerEvents(
                new BlockBreakListener(this::getVigilacConfig, playerDataManager, checkManager, pipeline, exemptionManager), this);
        getServer().getPluginManager().registerEvents(
                new ConfounderListener(playerDataManager, this::getVigilacConfig), this);
        getServer().getPluginManager().registerEvents(
                new ac.vigil.listener.InventoryListener(playerDataManager), this);
        getServer().getPluginManager().registerEvents(
                new ac.vigil.listener.HeldItemListener(playerDataManager), this);

        // Attack-attempt pipeline: netty -> bounded queue -> main-thread drain (SL-1).
        this.attackAttemptDispatcher = new AttackAttemptDispatcher(playerDataManager,
                new ac.vigil.environment.BukkitAttackGeometryResolver(), getLogger(),
                config.reachMaxReach() + config.reachTolerance());
        this.packetEventsHandle = PacketEventsHook.tryRegister(this, playerDataManager, attackAttemptDispatcher);
        getServer().getScheduler().runTaskTimer(this, attackAttemptDispatcher::drainTick, 1L, 1L);

        VigilACCommand command = new VigilACCommand(this);
        if (getCommand("vigilac") != null) {
            getCommand("vigilac").setExecutor(command);
            getCommand("vigilac").setTabCompleter(command);
        } else {
            getLogger().severe("Command 'vigilac' missing from plugin.yml — /vigilac will not work");
        }

        // AI-assist module (advisory only; defaults to OFF; deterministic engine unaffected).
        // Constructed after the pipeline exists, stopped before storage closes.
        AiConfig aiConfig = new AiConfig(new BukkitConfigSource(getConfig()));
        this.aiServices = new AiServices(getLogger(), aiConfig, playerDataManager,
                () -> getServer().getTPS()[0], System::currentTimeMillis);
        this.aiServices.start(getDataFolder().toPath());
        int aiIntervalTicks = aiConfig.inferenceIntervalSeconds() * 20;
        getServer().getScheduler().runTaskTimer(this, this::aiScanTick, 20L, aiIntervalTicks);

        this.captureService.start(getDataFolder().toPath());
        int captureIntervalTicks = this.captureService.config().intervalSeconds() * 20;
        getServer().getScheduler().runTaskTimer(this, this::captureTick, 20L, Math.max(20, captureIntervalTicks));

        // VL decay + storage flush ride the same 1s timer.
        getServer().getScheduler().runTaskTimer(this, this::maintenanceTick, 20L, 20L);

        getLogger().info("VigilAC enabled: " + checkManager.getCount() + " checks registered, mode="
                + (config.isAlertOnly() ? "alert-only" : "punish"));
    }

    @Override
    public void onDisable() {
        // 0) Stop the capture first: flush and close the corpus writer before anything else goes down.
        if (captureService != null) {
            captureService.stop();
            captureService = null;
        }
        // 1) Stop the AI module next: interrupt inference workers and discard pending jobs
        //    before any other subsystem shuts down.
        if (aiServices != null) {
            aiServices.stop();
            aiServices = null;
        }
        // 2) Stop the decay/flush scheduler so a queued maintenanceTick can
        //    never enqueue a record into a storage instance that is closing.
        if (getServer().getScheduler() != null) {
            getServer().getScheduler().cancelTasks(this);
        }
        // 3) Unregister the packetevents listener so a /reload never leaves
        //    stale listeners double-counting packet rates (which would feed the
        //    raw timer-rate input with N× traffic).
        if (packetEventsHandle != null) {
            packetEventsHandle.unregister();
            packetEventsHandle = null;
        }
        // 4) Flush and close violation storage (bounded wait).
        if (databaseManager != null) {
            databaseManager.close();
            databaseManager = null;
        }
        playerDataManager = null;
    }

    /** Human-readable packetevents status for /vigilac info. */
    public String getPacketEventsStatus() {
        return packetEventsHandle == null ? "not initialised" : packetEventsHandle.status();
    }

    public boolean isPacketEventsAvailable() {
        return packetEventsHandle != null && packetEventsHandle.isAvailable();
    }

    /** Re-reads config.yml and swaps typed views; check tuning takes effect immediately. */
    public void reloadConfiguration() {
        reloadConfig();
        this.config = new VigilACConfig(this);
        for (String problem : config.validate()) {
            getLogger().warning("Config: " + problem);
        }
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
        // Movement
        checkManager.register(new FlyCheck(
                cfg.flyMinHoverMillis(),
                cfg.flyHoverTolerance(),
                cfg.flyMinUpwardTicks(),
                cfg.flyVlPerObservation()));
        checkManager.register(new TimerCheck(
                cfg.timerThresholdPerSecond(),
                cfg.timerMinExcessMillis(),
                cfg.timerExcessVlPerSecond()));
        checkManager.register(new NoFallCheck(
                cfg.noFallMinFallDistance(),
                cfg.noFallDamageGraceMillis(),
                cfg.noFallMinSuspiciousLandings(),
                cfg.noFallVlPerLanding(),
                new ac.vigil.environment.BukkitBlockSupportProbe()));
        checkManager.register(new InvalidMovementCheck(
                cfg.invalidMaxVerticalDelta(),
                cfg.invalidVlPerImpossibility()));
        checkManager.register(new JesusCheck(
                cfg.jesusMinStreak(),
                cfg.jesusVlPerTick()));
        checkManager.register(new StepCheck(
                cfg.stepMaxLegal(),
                cfg.stepMinStreak(),
                cfg.stepVlPerStep()));
        // Combat
        checkManager.register(new KillAuraCheck(
                cfg.killAuraWindowMillis(),
                cfg.killAuraMinTargets(),
                cfg.killAuraVlPerTrigger()));
        checkManager.register(new AutoClickerCheck(
                cfg.autoClickerCpsCap(),
                cfg.autoClickerMinSamples(),
                cfg.autoClickerRoboticMeanMax(),
                cfg.autoClickerRoboticStddevMax(),
                cfg.autoClickerRoboticMinSamples(),
                cfg.autoClickerRoboticMinStreak(),
                cfg.autoClickerVlPerTrigger()));
        checkManager.register(new AimAssistCheck(
                cfg.aimAssistSnapAngularDelta(),
                cfg.aimAssistSnapAngleToTarget(),
                cfg.aimAssistSnapMaxIntervalMillis(),
                cfg.aimAssistMinSnapStreak(),
                cfg.aimAssistVlPerSnap()));
        // Player
        checkManager.registerPlace(new ScaffoldCheck(
                cfg.scaffoldMaxAngleDegrees(),
                cfg.scaffoldMinStreak(),
                cfg.scaffoldVlPerPlacement()));
        checkManager.registerPlace(new FastPlaceCheck(
                cfg.fastPlaceMaxPerSecond(),
                cfg.fastPlaceWindowMillis(),
                cfg.fastPlaceVlPerPlacement()));
        checkManager.registerBreak(new FastBreakCheck(
                cfg.fastBreakMinImpossibleStreak(),
                cfg.fastBreakVlPerTrigger(),
                cfg.fastBreakUnbreakableVl()));
    }

    private CheckSettings settingsFor(String checkName) {
        return getVigilacConfig().checkSettings(checkName);
    }

    private void captureTick() {
        CaptureService services = captureService;
        if (services == null) {
            return;
        }
        try {
            services.tick();
        } catch (RuntimeException t) {
            // Capture must never take the main thread down with it.
            getLogger().warning("[capture] capture tick failed: " + t);
        }
    }

    private void aiScanTick() {
        AiServices services = aiServices;
        if (services == null) {
            return;
        }
        try {
            services.scanTick();
        } catch (Throwable t) {
            // The AI module must never take the main thread down with it.
            getLogger().warning("[ai] scan tick failed: " + t);
        }
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

    /** AI-assist module. May be null during disable; always non-null while enabled. */
    public AiServices getAiServices() {
        return aiServices;
    }

    /** Consent-based capture module. May be null during disable; always non-null while enabled. */
    public CaptureService getCaptureService() {
        return captureService;
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
