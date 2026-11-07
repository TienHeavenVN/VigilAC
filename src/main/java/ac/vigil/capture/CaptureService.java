package ac.vigil.capture;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Logger;

import ac.vigil.ai.ConfigSource;
import ac.vigil.ai.telemetry.AiContext;
import ac.vigil.ai.telemetry.AiFeatureSchema;
import ac.vigil.ai.telemetry.AiFeatureSnapshot;
import ac.vigil.ai.telemetry.FeatureExtractor;
import ac.vigil.ai.telemetry.FeatureValidator;
import ac.vigil.data.PlayerData;
import ac.vigil.data.PlayerDataManager;

/**
 * Consent-based, owner-configured capture of gameplay telemetry into the Stage 3
 * corpus format. Disabled by default; when enabled but incompletely declared it
 * refuses to write a single row and reports why.
 *
 * <p>Design guarantees (all enforced here or in {@link CaptureConfig}):</p>
 * <ul>
 *   <li>only the 22-feature schema is ever written — nothing else about the
 *       player, ever;</li>
 *   <li>a row is written only for a tracked player who explicitly consented
 *       this session (consent can be revoked at any time; takes effect on the
 *       next capture tick);</li>
 *   <li>no username, UUID, IP or chat content is ever read, stored or logged;</li>
 *   <li>rows carry the OWNER-declared label of the configured scenario — a
 *       detection firing NEVER labels a row;</li>
 *   <li>bounded buffer → bounded writer → bounded disk; overflow and quota
 *       exhaustion are counted, never blocking;</li>
 *   <li>no network access anywhere in this module.</li>
 * </ul>
 *
 * Threading: {@link #tick()} runs on the scheduler; extraction is O(bounded
 * windows) per tracked player. Serialization happens on the writer thread.
 */
public final class CaptureService {

    private final Logger logger;
    private final CaptureConfig config;
    private final PlayerDataManager players;
    private final DoubleSupplier tpsSupplier;
    private final LongSupplier clock;
    private final Supplier<String> mcVersionSupplier;

    private CapturePseudonymizer pseudonymizer;
    private CaptureConsent consent;
    private CaptureWriter writer;
    private CaptureBuffer buffer;
    private final ConcurrentHashMap<UUID, String> sessionPseudonyms = new ConcurrentHashMap<>();

    private static final java.util.regex.Pattern MC_VERSION = java.util.regex.Pattern.compile("(\d+\.\d+(\.\d+)?)");

    private volatile boolean started;
    private volatile String mcVersion;
    private volatile long rowsSkippedInvalid;

    public CaptureService(Logger logger, ConfigSource source, PlayerDataManager players,
                          DoubleSupplier tpsSupplier, LongSupplier clock, Supplier<String> mcVersionSupplier) {
        this.logger = logger;
        this.config = new CaptureConfig(source);
        this.players = players;
        this.tpsSupplier = tpsSupplier;
        this.clock = clock;
        this.mcVersionSupplier = mcVersionSupplier;
    }

    /** Starts the capture. Fail safe: disabled or misconfigured ⇒ stays inert with an explicit reason. */
    public synchronized void start(Path dataDir) {
        if (started) {
            return;
        }
        started = true;
        if (!config.enabled()) {
            logger.info("[capture] disabled by config (capture.enabled=false) — nothing is recorded.");
            return;
        }
        List<String> problems = config.problems();
        String mc = normalizeMcVersion(safeVersion());
        if (mc == null) {
            problems = new java.util.ArrayList<>(problems);
            problems.add("Minecraft version could not be determined as \d+.\d+(.\d+)? from the server (got: "
                    + safeVersion() + ") — refusing to write rows the Stage 3 pipeline would reject");
        }
        if (!problems.isEmpty()) {
            for (String problem : problems) {
                logger.warning("[capture] refusing to start: " + problem);
            }
            logger.warning("[capture] INERT — fix the config and restart. Zero rows will be written.");
            return;
        }
        mcVersion = mc;
        this.pseudonymizer = new CapturePseudonymizer(config.pseudonymKey(), config.pseudonymRotationHours());
        this.consent = new CaptureConsent(dataDir, pseudonymizer);
        this.consent.load();
        Path outputDir = dataDir.resolve("capture").resolve("corpus");
        this.buffer = new CaptureBuffer(config.bufferCapacity());
        this.writer = new CaptureWriter(outputDir, config.maxTotalBytes(), buffer, clock);
        int purged = this.writer.purgeOlderThan(config.retentionDays(), clock.getAsLong());
        this.writer.start();
        for (String io : writer.ioProblems()) {
            logger.warning("[capture] " + io);
        }
        logger.info("[capture] enabled: scenario='" + config.scenario() + "' label=" + config.label()
                + " source=" + config.labelSource() + " confidence=" + config.labelConfidence()
                + " rotationHours=" + config.pseudonymRotationHours() + " retentionDays=" + config.retentionDays()
                + " maxTotalBytes=" + config.maxTotalBytes() + " purgedOldFiles=" + purged
                + " consented=" + consent.count() + ". Features only; pseudonyms are HMAC-SHA256; rotation bucket "
                + pseudonymizer.rotationBucket(clock.getAsLong()));
    }

    /** Stops the capture: stops the writer, drains and closes. Idempotent. */
    public synchronized void stop() {
        if (!started) {
            return;
        }
        sessionPseudonyms.clear();
        if (writer != null) {
            writer.close();
            for (String io : writer.ioProblems()) {
                logger.warning("[capture] " + io);
            }
            writer = null;
        }
        started = false;
    }

    /** Called on player join: draws the session pseudonym. */
    public void onLogin(UUID playerId) {
        if (!isReady()) {
            return;
        }
        sessionPseudonyms.put(playerId, pseudonymizer.newSessionPseudonym());
    }

    /** Called on player quit: the session pseudonym is forgotten; consent state persists. */
    public void onQuit(UUID playerId) {
        sessionPseudonyms.remove(playerId);
    }

    /** Grants consent (player-initiated). */
    public boolean consentGrant(UUID playerId) {
        if (consent == null) {
            return false;
        }
        consent.grant(playerId);
        return true;
    }

    /** Revokes consent (player-initiated); session pseudonym is dropped immediately. */
    public boolean consentRevoke(UUID playerId) {
        if (consent == null) {
            return false;
        }
        consent.revoke(playerId);
        sessionPseudonyms.remove(playerId);
        return true;
    }

    public boolean hasConsent(UUID playerId) {
        return consent != null && consent.hasConsent(playerId);
    }

    /** Whether the join notice should be shown (enabled, ready, not yet consented, flag on). */
    public boolean shouldNotifyOnJoin(UUID playerId) {
        return isReady() && config.notifyOnJoin() && consent != null && !consent.hasConsent(playerId);
    }

    /** Admin: purge day-files older than the retention window. Returns files purged. */
    public int purgeOldFiles() {
        if (writer == null) {
            return 0;
        }
        return writer.purgeOlderThan(config.retentionDays(), clock.getAsLong());
    }

    /** Admin: stop, delete the whole corpus and the consent store. Returns corpus files deleted. */
    public int deleteAll() {
        if (writer == null) {
            return 0;
        }
        int corpusFiles = writer.deleteAll();
        if (consent != null) {
            consent.deleteAll();
        }
        sessionPseudonyms.clear();
        return corpusFiles;
    }

    private String safeVersion() {
        try {
            return String.valueOf(mcVersionSupplier.get());
        } catch (RuntimeException e) {
            return "<supplier threw: " + e + ">";
        }
    }

    /** Extracts the first X.Y[.Z] token (e.g. "1.21.8" from "1.21.8-R0.1-SNAPSHOT" or "26.1.2" from "26.1.2.build.74-stable"). */
    static String normalizeMcVersion(String raw) {
        if (raw == null) {
            return null;
        }
        java.util.regex.Matcher matcher = MC_VERSION.matcher(raw);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** One capture tick: extract → validate → enqueue for every consented tracked player. */
    public void tick() {
        if (!isReady()) {
            return;
        }
        long now = clock.getAsLong();
        String mc = mcVersion;
        if (mc == null) {
            return;
        }
        for (PlayerData data : players.getAll()) {
            try {
                UUID playerId = data.getUuid();
                CaptureConsent currentConsent = consent;
                if (currentConsent == null || !currentConsent.hasConsent(playerId)) {
                    continue;
                }
                String session = sessionPseudonyms.get(playerId);
                if (session == null) {
                    session = pseudonymizer.newSessionPseudonym();
                    sessionPseudonyms.put(playerId, session);
                }
                var monitor = data.monitor();
                double ping = monitor == null ? 0.0D : monitor.pingEma();
                boolean onGround = monitor == null || monitor.isOnGround();
                long lastVelocityAt = monitor == null ? 0L : monitor.lastVelocityAt();
                long lastTeleportAt = monitor == null ? 0L : monitor.lastTeleportAt();
                boolean freshVelocity = lastVelocityAt > 0 && now - lastVelocityAt >= 0 && now - lastVelocityAt <= ac.vigil.monitor.PlayerMonitor.EXEMPTION_FRESHNESS_MILLIS;
                boolean freshTeleport = lastTeleportAt > 0 && now - lastTeleportAt >= 0 && now - lastTeleportAt <= ac.vigil.monitor.PlayerMonitor.EXEMPTION_FRESHNESS_MILLIS;
                // mirrors AiServices.buildContext: same windows, same semantics as the AI module
                AiContext context = new AiContext(now, ping, tpsSupplier.getAsDouble(), onGround,
                        freshVelocity, freshTeleport, data.combatTracker().getIntervalSampleCount());
                AiFeatureSnapshot snapshot = FeatureExtractor.extract(data, context);
                if (FeatureValidator.check(snapshot) != FeatureValidator.Result.OK) {
                    rowsSkippedInvalid++;
                    continue;
                }
                CaptureRecord record = new CaptureRecord(
                        pseudonymizer.playerPseudonym(playerId, now), session, mc,
                        config.clientCategory(), config.inputCategory(),
                        clipTps(tpsSupplier.getAsDouble()), clipPing(ping), AiFeatureSchema.schemaHash(),
                        config.label().name(), config.labelSource().name(), config.labelConfidence(),
                        Instant.ofEpochMilli(now), config.scenario(), snapshot.values(), config.provenanceNote());
                buffer.offer(record);
            } catch (RuntimeException e) {
                logger.info("[capture] skipped one tracked player this tick: " + e);
            }
        }
    }

    /** Current status for /vigilac capture status. Never contains identifiers. */
    public CaptureStatus status() {
        List<String> notes = problemsOrDefault();
        if (!config.enabled()) {
            notes = List.of("disabled (capture.enabled=false) — nothing is recorded");
        }
        return new CaptureStatus(config.enabled(), isReady(),
                writer != null && writer.haltedReason() != null,
                writer == null ? null : writer.haltedReason(),
                writer == null ? 0 : writer.rowsWritten(),
                rowsSkippedInvalid,
                buffer == null ? 0 : buffer.droppedFull(),
                writer == null ? 0 : writer.quotaDropped(),
                writer == null ? 0 : writer.bytesWritten(),
                config.maxTotalBytes(),
                buffer == null ? 0 : buffer.size(),
                consent == null ? 0 : consent.count(),
                sessionPseudonyms.size(),
                notes);
    }

    public CaptureConfig config() {
        return config;
    }

    public boolean isStarted() {
        return started;
    }

    private boolean isReady() {
        return pseudonymizer != null && consent != null && writer != null && config.ready();
    }

    private List<String> problemsOrDefault() {
        List<String> problems = config.problems();
        return problems.isEmpty() ? List.of("config OK: scenario='" + config.scenario()
                + "' label=" + config.label() + " source=" + config.labelSource()) : problems;
    }

    private static double clipTps(double tps) {
        return Double.isNaN(tps) || Double.isInfinite(tps) ? 0.0D : Math.min(20.0D, Math.max(0.0D, tps));
    }

    private static double clipPing(double ping) {
        return Double.isNaN(ping) || Double.isInfinite(ping) ? 0.0D : Math.min(5_000.0D, Math.max(0.0D, ping));
    }
}
