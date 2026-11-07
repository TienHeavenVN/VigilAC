package ac.vigil.ai;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;
import java.util.logging.Logger;
import ac.vigil.ai.assistant.AdminAssistant;
import ac.vigil.ai.assistant.NoopLlmProvider;
import ac.vigil.ai.evidence.AiAssessment;
import ac.vigil.ai.evidence.AiEvidenceCorrelator;
import ac.vigil.ai.ml.AnomalyModel;
import ac.vigil.ai.ml.ModelRegistry;
import ac.vigil.ai.report.AiReportBuilder;
import ac.vigil.ai.report.PlayerProfile;
import ac.vigil.ai.runtime.AiInferenceSink;
import ac.vigil.ai.runtime.AiShadowWorkerPool;
import ac.vigil.ai.runtime.AiStatus;
import ac.vigil.ai.telemetry.AiContext;
import ac.vigil.ai.telemetry.AiFeatureSnapshot;
import ac.vigil.ai.telemetry.FeatureExtractor;
import ac.vigil.ai.telemetry.FeatureValidator;
import ac.vigil.data.PlayerData;
import ac.vigil.data.PlayerDataManager;

/**
 * Lifecycle owner of the AI module. Responsibilities:
 * <ul>
 *   <li>load (and verify) models at startup; on total failure the module degrades
 *       to correlator-only mode with an explicit reason — the deterministic
 *       engine is never affected;</li>
 *   <li>run the periodic scan tick: extract features → validate → submit to the
 *       bounded worker pool (never inference on Netty/main threads);</li>
 *   <li>retain a bounded ML history per player for trend display;</li>
 *   <li>produce assessments/reports on demand for the admin command;</li>
 *   <li>stop cleanly on plugin disable.</li>
 * </ul>
 *
 * <p>AI output NEVER feeds violation levels. This class holds no reference to
 * any violation writer and the shadow sink only records advisory evidence.</p>
 */
public final class AiServices {

    private static final int ML_HISTORY_LIMIT = 16;
    private static final long HISTORY_EVICT_MILLIS = 10L * 60_000L;

    private final Logger logger;
    private final AiConfig config;
    private final PlayerDataManager players;
    private final DoubleSupplier tpsSupplier;
    private final LongSupplier clock;
    private final AiEvidenceCorrelator correlator;

    private List<ModelRegistry.LoadedModel> loadedModels = List.of();
    private AiShadowWorkerPool pool;
    private boolean started;

    /** player → (oldest … newest) shadow scores. Bounded per player and by map size. */
    private final ConcurrentHashMap<UUID, Deque<Double>> mlHistory = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> mlHistoryUpdatedAt = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> latestMlModel = new ConcurrentHashMap<>();

    public AiServices(Logger logger, AiConfig config, PlayerDataManager players,
                      DoubleSupplier tpsSupplier, LongSupplier clock) {
        this.logger = logger;
        this.config = config;
        this.players = players;
        this.tpsSupplier = tpsSupplier;
        this.clock = clock;
        this.correlator = new AiEvidenceCorrelator(config.mlThreshold(), config.minCombatSamples());
    }

    /** Loads models and starts the pool. Safe to call once; a no-op when already started. */
    public synchronized void start(Path baseDirectory) {
        if (started) {
            return;
        }
        started = true;
        List<String> problems = config.problems();
        for (String problem : problems) {
            logger.warning("[ai] config: " + problem);
        }
        if (!config.enabled()) {
            logger.info("[ai] disabled by config (ai.enabled=false) — no model loading, no inference.");
            loadedModels = List.of();
            return;
        }
        Path modelsDir = baseDirectory.resolve(config.modelsDirectory());
        this.loadedModels = ModelRegistry.loadFromDirectory(modelsDir);
        List<AnomalyModel> usable = new ArrayList<>();
        for (ModelRegistry.LoadedModel loaded : loadedModels) {
            if (loaded.loaded()) {
                usable.add(loaded.model());
            }
            logger.info("[ai] model " + loaded.modelId() + ": " + loaded.status());
        }
        if (loadedModels.isEmpty()) {
            logger.info("[ai] no models under " + modelsDir + " — shadow scoring inactive; correlator still advisory.");
        }
        AiInferenceSink sink = new AiInferenceSink() {
            @Override
            public void onScored(UUID playerId, String modelId, double score, long latencyMillis) {
                recordMlScore(playerId, modelId, score);
                PlayerData data = players.get(playerId);
                if (data != null && data.monitor() != null) {
                    data.monitor().noteCheckEvidence("ai-shadow",
                            String.format(java.util.Locale.US, "model=%s score=%.3f", modelId, score));
                }
            }

            @Override
            public void onFailed(UUID playerId, String modelId, String reason) {
                logger.info("[ai] inference failed for " + playerId + " (" + modelId + "): " + abbreviate(reason));
            }
        };
        this.pool = new AiShadowWorkerPool(config.maxQueueSize(), config.workerThreads(),
                config.inferenceTimeoutMillis(), usable, sink, logger, clock);
        this.pool.start();
    }

    /** Periodic tick. Extracts features for every tracked player and enqueues them — never blocks on inference. */
    public void scanTick() {
        if (!config.enabled() || pool == null) {
            return;
        }
        long now = clock.getAsLong();
        for (PlayerData data : players.getAll()) {
            try {
                AiContext context = buildContext(data, now);
                AiFeatureSnapshot snapshot = FeatureExtractor.extract(data, context);
                if (FeatureValidator.check(snapshot) != FeatureValidator.Result.OK) {
                    continue; // extractor should never emit invalid; skip defensively
                }
                pool.submit(data.getUuid(), snapshot);
                evictHistoryIfNeeded(now);
            } catch (RuntimeException e) {
                logger.info("[ai] scanTick skipped " + data.getUuid() + ": " + abbreviate(e.toString()));
            }
        }
    }

    private AiContext buildContext(PlayerData data, long now) {
        var monitor = data.monitor();
        double ping = monitor == null ? 0.0D : monitor.pingEma();
        boolean onGround = monitor == null || monitor.isOnGround();
        long lastVelocityAt = monitor == null ? 0L : monitor.lastVelocityAt();
        long lastTeleportAt = monitor == null ? 0L : monitor.lastTeleportAt();
        boolean freshVelocity = lastVelocityAt > 0 && now - lastVelocityAt >= 0 && now - lastVelocityAt <= ac.vigil.monitor.PlayerMonitor.EXEMPTION_FRESHNESS_MILLIS;
        boolean freshTeleport = lastTeleportAt > 0 && now - lastTeleportAt >= 0 && now - lastTeleportAt <= ac.vigil.monitor.PlayerMonitor.EXEMPTION_FRESHNESS_MILLIS;
        int samples = data.combatTracker().getIntervalSampleCount();
        return new AiContext(now, ping, tpsSupplier.getAsDouble(), onGround, freshVelocity, freshTeleport, samples);
    }

    private void recordMlScore(UUID playerId, String modelId, double score) {
        mlHistory.compute(playerId, (id, deque) -> {
            Deque<Double> d = deque == null ? new ArrayDeque<>(ML_HISTORY_LIMIT + 1) : deque;
            synchronized (d) {
                d.addLast(score);
                while (d.size() > ML_HISTORY_LIMIT) {
                    d.pollFirst();
                }
            }
            return d;
        });
        mlHistoryUpdatedAt.put(playerId, clock.getAsLong());
        latestMlModel.put(playerId, modelId);
    }

    private void evictHistoryIfNeeded(long now) {
        if (mlHistory.size() <= 1024) {
            return;
        }
        for (Map.Entry<UUID, Long> entry : mlHistoryUpdatedAt.entrySet()) {
            if (now - entry.getValue() > HISTORY_EVICT_MILLIS) {
                mlHistory.remove(entry.getKey());
                mlHistoryUpdatedAt.remove(entry.getKey());
                latestMlModel.remove(entry.getKey());
            }
        }
    }

    /** Latest shadow score for a player, or null when none has been produced. */
    public Double latestMlScore(UUID uuid) {
        Deque<Double> deque = mlHistory.get(uuid);
        if (deque == null) {
            return null;
        }
        synchronized (deque) {
            return deque.peekLast();
        }
    }

    public List<Double> mlHistory(UUID uuid) {
        Deque<Double> deque = mlHistory.get(uuid);
        if (deque == null) {
            return List.of();
        }
        synchronized (deque) {
            return List.copyOf(deque);
        }
    }

    /** Builds the correlator assessment for a player from live monitor evidence + latest ML score. */
    public AiAssessment assessmentFor(PlayerData data, long now) {
        UUID uuid = data.getUuid();
        var monitor = data.monitor();
        java.util.List<ac.vigil.monitor.Evidence> evidence = monitor == null ? List.of() : monitor.evidenceSnapshot();
        Double score = latestMlScore(uuid);
        String modelId = latestMlModel.get(uuid);
        double ping = monitor == null ? 0.0D : monitor.pingEma();
        long lastVelocityAt = monitor == null ? 0L : monitor.lastVelocityAt();
        long lastTeleportAt = monitor == null ? 0L : monitor.lastTeleportAt();
        int samples = data.combatTracker().getIntervalSampleCount();
        return correlator.assess(evidence, score, modelId, ping, lastVelocityAt, lastTeleportAt, samples, now);
    }

    /** Builds the behavioral profile projection used by the report and the admin assistant. */
    public PlayerProfile profileFor(PlayerData data, String displayName, long now) {
        if (data == null) {
            return null;
        }
        var monitor = data.monitor();
        int evidenceCount = 0;
        List<String> evidenceChecks = new ArrayList<>();
        if (monitor != null) {
            for (ac.vigil.monitor.Evidence e : monitor.evidenceSnapshot()) {
                if (e.type() == ac.vigil.monitor.EvidenceType.CHECK_EVIDENCE
                        && e.check() != null && e.check().equalsIgnoreCase("ai-shadow")) {
                    continue; // shadow origin — not deterministic evidence
                }
                evidenceCount++;
                if (e.check() != null && !e.check().isEmpty() && !evidenceChecks.contains(e.check())) {
                    evidenceChecks.add(e.check());
                }
            }
        }
        int vlChecks = 0;
        for (double vl : data.getViolationLevelsSnapshot().values()) {
            if (vl > 0.0D) {
                vlChecks++;
            }
        }
        long rawBreakInterval = data.blockActionTracker().breakInterval(now);
        long breakInterval = rawBreakInterval <= 0L || rawBreakInterval == Long.MAX_VALUE
                ? 0L : Math.min(rawBreakInterval, 60_000L); // Long.MAX_VALUE ⇒ no break on record (unknown) ⇒ 0
        PlayerProfile profile = PlayerProfile.builder()
                .targetName(displayName)
                .snapshotAtMillis(now)
                .cps1s(data.combatTracker().cps(now, 1_000L))
                .cps5s(data.combatTracker().cps(now, 5_000L))
                .clickIntervalMean(data.combatTracker().intervalMean())
                .clickIntervalStddev(data.combatTracker().intervalStddev())
                .clickIntervalCv(data.combatTracker().intervalCv())
                .clickIntervalEntropyBits(data.combatTracker().intervalEntropyBits(100))
                .rotationAngularVelocity1s(data.rotationTracker().angularDistanceCovered(now, 1_000L))
                .rotationMaxDelta1s(data.rotationTracker().maxDeltaInWindow(now, 1_000L))
                .reachEma(data.reachEma())
                .reachSamples(data.getReachSampleCount())
                .packetsPerSecond(data.getPacketsPerSecond())
                .breakIntervalMillisClipped(breakInterval)
                .impossibleBreakStreak(data.blockActionTracker().getImpossibleBreakStreak())
                .pingMillis(monitor == null ? 0.0D : monitor.pingEma())
                .deterministicEvidenceCount(evidenceCount)
                .deterministicCheckNames(evidenceChecks)
                .totalViolationLevelChecks(vlChecks)
                .mlHistory(mlHistory(data.getUuid()))
                .build();
        return profile;
    }

    /** Renders the admin report for a player. Returns null when the player is not tracked. */
    public String reportFor(PlayerData data, String displayName, long now) {
        PlayerProfile profile = profileFor(data, displayName, now);
        if (profile == null) {
            return null;
        }
        AiAssessment assessment = config.enabled() ? assessmentFor(data, now) : null;
        return AiReportBuilder.render(profile, assessment, modelStatusLines(), config.mlThreshold(), counterLines());
    }

    /** Configured admin assistant for on-demand use; NO LLM provider is shipped. */
    public AdminAssistant assistantFor(String targetName) {
        return new AdminAssistant(config.adminAssistantEnabled(), config.llmEnabled(),
                new NoopLlmProvider(), config.llmRedactIdentifiers(), false, targetName);
    }

    public AiConfig config() {
        return config;
    }

    public boolean isStarted() {
        return started;
    }

    /** Machine-readable status for /vigilac ai status. */
    public AiStatus status() {
        String reason = null;
        if (!config.enabled()) {
            reason = "disabled by config (ai.enabled=false)";
        } else if (loadedModels.isEmpty()) {
            reason = "no models loaded — shadow scoring inactive; deterministic engine unaffected";
        } else {
            boolean anyUsable = false;
            for (ModelRegistry.LoadedModel loaded : loadedModels) {
                anyUsable |= loaded.loaded();
            }
            if (!anyUsable) {
                reason = "all models unavailable — shadow scoring inactive; deterministic engine unaffected";
            }
        }
        long submitted = pool == null ? 0 : pool.getSubmitted();
        long dropped = pool == null ? 0 : pool.getDroppedFull();
        long completed = pool == null ? 0 : pool.getCompleted();
        long failed = pool == null ? 0 : pool.getFailed();
        long timedOut = pool == null ? 0 : pool.getTimedOut();
        long invalid = pool == null ? 0 : pool.getInvalidSnapshots();
        double latency = pool == null ? 0.0D : pool.getAverageLatencyMillis();
        return new AiStatus(config.enabled(), true, reason, modelStatusLines(),
                submitted, dropped, completed, failed, timedOut, invalid, latency);
    }

    private List<String> modelStatusLines() {
        List<String> lines = new ArrayList<>();
        for (ModelRegistry.LoadedModel loaded : loadedModels) {
            lines.add(loaded.modelId() + ": " + loaded.status());
        }
        return lines;
    }

    private List<String> counterLines() {
        if (pool == null) {
            return List.of("inference pool not started", "dropped=0", "completed=0", "failed=0");
        }
        return List.of(
                "pending=" + pool.getPendingCount(),
                "submitted=" + pool.getSubmitted(),
                "droppedFull=" + pool.getDroppedFull(),
                "completed=" + pool.getCompleted(),
                "failed=" + pool.getFailed(),
                "timedOut=" + pool.getTimedOut(),
                "invalidSnapshots=" + pool.getInvalidSnapshots(),
                String.format(java.util.Locale.US, "avgLatencyMs(ema)=%.3f", pool.getAverageLatencyMillis()));
    }

    /** Stops the pool and clears per-player history. Idempotent. */
    public synchronized void stop() {
        if (pool != null) {
            pool.stop();
            pool = null;
        }
        mlHistory.clear();
        mlHistoryUpdatedAt.clear();
        latestMlModel.clear();
        loadedModels = List.of();
        started = false;
    }

    private static String abbreviate(String value) {
        return value == null ? "?" : (value.length() <= 160 ? value : value.substring(0, 160) + "…");
    }
}
