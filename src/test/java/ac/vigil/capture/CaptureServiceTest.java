package ac.vigil.capture;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ac.vigil.ai.ConfigSource;
import ac.vigil.data.PlayerData;
import ac.vigil.data.PlayerDataManager;

/**
 * Pure-Java capture lifecycle tests. All fixtures are synthetic; no gameplay data
 * is fabricated or presented as real.
 */
class CaptureServiceTest {

    private static final class MapSource implements ConfigSource {
        final Map<String, Object> values = new HashMap<>();

        MapSource with(String key, Object value) { values.put(key, value); return this; }

        @Override public String getString(String path, String def) {
            Object v = values.get(path);
            return v instanceof String s ? s : def;
        }
        @Override public int getInt(String path, int def) {
            Object v = values.get(path);
            return v instanceof Number n ? n.intValue() : def;
        }
        @Override public double getDouble(String path, double def) {
            Object v = values.get(path);
            return v instanceof Number n ? n.doubleValue() : def;
        }
        @Override public boolean getBoolean(String path, boolean def) {
            Object v = values.get(path);
            return v instanceof Boolean b ? b : def;
        }
        @Override public List<String> getStringList(String path, List<String> def) {
            return def;
        }
    }

    private static final String BASE64_KEY =
            java.util.Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    private static MapSource validConfig() {
        return new MapSource()
                .with("capture.enabled", true)
                .with("capture.scenario", "unit-test-scenario")
                .with("capture.label", "LEGITIMATE")
                .with("capture.label-source", "SERVER_TELEMETRY_CURATION")
                .with("capture.label-confidence", 0.9)
                .with("capture.client-category", "VANILLA")
                .with("capture.input-category", "MOUSE")
                .with("capture.pseudonym-key", BASE64_KEY)
                .with("capture.interval-seconds", 1)
                .with("capture.buffer-capacity", 64)
                .with("capture.max-total-bytes", 10_000_000.0);
    }

    @TempDir
    Path dir;

    private CaptureService newService(ConfigSource source, PlayerDataManager players, AtomicLong now, String mcVersion) {
        return new CaptureService(java.util.logging.Logger.getAnonymousLogger(), source, players,
                () -> 20.0D, now::get, () -> mcVersion);
    }

    private PlayerData trackedWithAttacks(PlayerDataManager players, UUID uuid, long now) {
        PlayerData data = players.load(uuid);
        data.combatTracker().recordAttack(now - 3_000);
        data.combatTracker().recordAttack(now - 2_000);
        data.combatTracker().recordAttack(now - 1_000);
        return data;
    }

    @Test
    void disabledByDefaultRecordsNothing() {
        AtomicLong now = new AtomicLong(10_000L);
        PlayerDataManager players = new PlayerDataManager();
        CaptureService service = newService(new MapSource(), players, now, "1.21.8-R0.1-SNAPSHOT");
        service.start(dir);
        UUID player = UUID.randomUUID();
        trackedWithAttacks(players, player, now.get());
        service.consentGrant(player); // even with "yes", a disabled capture refuses
        service.onLogin(player);
        service.tick();
        service.stop();
        CaptureStatus status = service.status();
        assertEquals(0, status.rowsWritten());
        assertFalse(status.ready());
        assertFalse(Files.exists(dir.resolve("capture").resolve("corpus")));
    }

    @Test
    void enabledButUndeclaredIsInert() {
        AtomicLong now = new AtomicLong(10_000L);
        PlayerDataManager players = new PlayerDataManager();
        CaptureService service = newService(
                new MapSource().with("capture.enabled", true), players, now, "1.21.8");
        service.start(dir);
        UUID player = UUID.randomUUID();
        trackedWithAttacks(players, player, now.get());
        assertFalse(service.consentGrant(player), "consent must be refused when capture is inert");
        service.tick();
        service.stop();
        assertEquals(0, service.status().rowsWritten());
        assertFalse(service.status().ready());
        assertFalse(service.status().notes().isEmpty());
    }

    @Test
    void consentedTrackedPlayerProduceStage3CompatibleRow() throws Exception {
        AtomicLong now = new AtomicLong(10_000L);
        PlayerDataManager players = new PlayerDataManager();
        CaptureService service = newService(validConfig(), players, now, "1.21.8-R0.1-SNAPSHOT");
        service.start(dir);

        UUID player = UUID.randomUUID();
        trackedWithAttacks(players, player, now.get());
        service.onLogin(player);

        // not consented → nothing
        service.tick();
        assertEquals(0, service.status().rowsWritten());

        assertTrue(service.consentGrant(player));
        service.tick();
        service.stop();

        CaptureStatus status = service.status();
        assertEquals(1, status.rowsWritten(), () -> "rows: " + status.rowsWritten() + " skippedInvalid: " + status.rowsSkippedInvalid());

        Path corpus = dir.resolve("capture").resolve("corpus");
        Path file;
        try (var files = Files.list(corpus)) {
            file = files.filter(f -> f.getFileName().toString().matches("capture-\\d{8}\\.jsonl")).findFirst().orElseThrow();
        }
        String line = Files.readAllLines(file, StandardCharsets.UTF_8).get(0);
        assertTrue(line.contains("\"feature_schema_hash\":\"2f2b0a3eb4563552b5c995c285f7179ce34490c450c46b63e9dbc022916ee688\""));
        assertTrue(line.contains("\"mc_version\":\"1.21.8\""), "version must be normalised: " + line);
        assertTrue(line.contains("\"label\":\"LEGITIMATE\""));
        assertTrue(line.contains("\"label_source\":\"SERVER_TELEMETRY_CURATION\""));
        assertTrue(line.contains("\"scenario\":\"unit-test-scenario\""));
        assertTrue(line.contains("\"player_pseudonym\":\""));
        // privacy: the raw UUID never appears, in any form
        assertFalse(line.contains(player.toString()));
        assertFalse(line.contains(player.toString().replace("-", "")));
        assertFalse(line.contains("uuid"));
    }

    @Test
    void revokeStopsFurtherRowsImmediately() throws Exception {
        AtomicLong now = new AtomicLong(10_000L);
        PlayerDataManager players = new PlayerDataManager();
        CaptureService service = newService(validConfig(), players, now, "1.21.8");
        service.start(dir);
        UUID player = UUID.randomUUID();
        trackedWithAttacks(players, player, now.get());
        service.onLogin(player);
        service.consentGrant(player);
        service.tick();
        service.consentRevoke(player);
        now.set(11_000L);
        trackedWithAttacks(players, player, now.get());
        service.tick();
        service.stop();
        assertEquals(1, service.status().rowsWritten());
    }

    @Test
    void noIdentifiersInConsentStoreOrCorpus() throws Exception {
        AtomicLong now = new AtomicLong(10_000L);
        PlayerDataManager players = new PlayerDataManager();
        CaptureService service = newService(validConfig(), players, now, "1.21.8");
        service.start(dir);
        UUID leak = UUID.fromString("deadbeef-1234-5678-9abc-def012345678");
        trackedWithAttacks(players, leak, now.get());
        service.onLogin(leak);
        service.consentGrant(leak);
        service.tick();
        service.stop();

        Path consentFile = dir.resolve("capture").resolve("consent.json");
        String consentJson = Files.readString(consentFile, StandardCharsets.UTF_8);
        assertFalse(consentJson.contains("deadbeef"), consentJson);
        Path corpus = dir.resolve("capture").resolve("corpus");
        try (var files = Files.list(corpus)) {
            for (Path file : files.toList()) {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                assertFalse(content.contains("deadbeef-1234"), "corpus leaks UUID: " + file);
                assertFalse(content.contains("deadbeef1234"), "corpus leaks compact UUID: " + file);
            }
        }
    }

    @Test
    void statusNeverContainsIdentifiers() throws Exception {
        AtomicLong now = new AtomicLong(10_000L);
        PlayerDataManager players = new PlayerDataManager();
        CaptureService service = newService(validConfig(), players, now, "1.21.8");
        service.start(dir);
        UUID leak = UUID.fromString("deadbeef-1234-5678-9abc-def012345678");
        trackedWithAttacks(players, leak, now.get());
        service.onLogin(leak);
        service.consentGrant(leak);
        service.tick();
        CaptureStatus status = service.status();
        String joined = String.join("\n", status.lines());
        assertFalse(joined.contains("deadbeef"));
        service.stop();
    }

    @Test
    void undeterminableMcVersionRefusesRows() {
        AtomicLong now = new AtomicLong(10_000L);
        PlayerDataManager players = new PlayerDataManager();
        CaptureService service = newService(validConfig(), players, now, "not-a-version");
        service.start(dir);
        UUID player = UUID.randomUUID();
        trackedWithAttacks(players, player, now.get());
        service.onLogin(player);
        assertTrue(service.consentGrant(player), "consent API still answers, but no rows flow");
        service.tick();
        service.stop();
        assertEquals(0, service.status().rowsWritten());
        assertFalse(service.status().ready());
    }

    @Test
    void normalizeMcVersionExtractsFirstSemver() {
        assertEquals("1.21.8", CaptureService.normalizeMcVersion("1.21.8-R0.1-SNAPSHOT"));
        assertEquals("26.1.2", CaptureService.normalizeMcVersion("26.1.2.build.74-stable"));
        assertEquals("26.2", CaptureService.normalizeMcVersion("26.2"));
        assertNull(CaptureService.normalizeMcVersion("no version here"));
        assertNull(CaptureService.normalizeMcVersion(null));
    }

    @Test
    void mcVersionSupplierThrowingKeepsCaptureInert() {
        AtomicLong now = new AtomicLong(10_000L);
        PlayerDataManager players = new PlayerDataManager();
        CaptureService service = new CaptureService(java.util.logging.Logger.getAnonymousLogger(),
                validConfig(), players, () -> 20.0D, now::get, () -> { throw new IllegalStateException("boom"); });
        assertDoesNotThrow(() -> service.start(dir));
        service.tick();
        service.stop();
        assertEquals(0, service.status().rowsWritten());
    }
}
