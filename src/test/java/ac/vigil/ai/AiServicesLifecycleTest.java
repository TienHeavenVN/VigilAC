package ac.vigil.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ac.vigil.ai.ml.SyntheticForests;
import ac.vigil.ai.runtime.AiStatus;
import ac.vigil.data.PlayerData;
import ac.vigil.data.PlayerDataManager;

/**
 * Pure-Java lifecycle tests. Deterministic engine is never involved here at all.
 * Uses SYNTHETIC fixtures — no real models exist and none are claimed.
 */
class AiServicesLifecycleTest {

    private static final class MapSource implements ConfigSource {
        private final Map<String, Object> values = new HashMap<>();

        MapSource with(String key, Object value) {
            values.put(key, value);
            return this;
        }

        @Override public String getString(String path, String defaultValue) {
            Object v = values.get(path);
            return v instanceof String s ? s : defaultValue;
        }
        @Override public int getInt(String path, int defaultValue) {
            Object v = values.get(path);
            return v instanceof Number n ? n.intValue() : defaultValue;
        }
        @Override public double getDouble(String path, double defaultValue) {
            Object v = values.get(path);
            return v instanceof Number n ? n.doubleValue() : defaultValue;
        }
        @Override public boolean getBoolean(String path, boolean defaultValue) {
            Object v = values.get(path);
            return v instanceof Boolean b ? b : defaultValue;
        }
        @Override public List<String> getStringList(String path, List<String> defaultValue) {
            Object v = values.get(path);
            @SuppressWarnings("unchecked")
            List<String> list = v instanceof List<?> l ? (List<String>) l : defaultValue;
            return list;
        }
    }

    @TempDir
    Path dir;

    private AiServices newServices(ConfigSource source, PlayerDataManager players, AtomicLong now) {
        return new AiServices(java.util.logging.Logger.getAnonymousLogger(),
                new AiConfig(source), players, () -> 20.0D, now::get);
    }

    private static MapSource modelsIn(Path dir) {
        return new MapSource()
                .with("ai.enabled", true)
                .with("ai.models-directory", dir.toAbsolutePath().toString());
    }

    @Test
    void disabledByDefaultMeansNoModelsAndClearReason() {
        AtomicLong now = new AtomicLong(10_000L);
        AiServices services = newServices(new MapSource(), new PlayerDataManager(), now);
        services.start(dir);
        AiStatus status = services.status();
        assertFalse(status.enabled());
        assertFalse(status.usable());
        assertTrue(status.unavailableReason().contains("disabled"));
        services.scanTick(); // must be a safe no-op
        services.stop();
    }

    @Test
    void enabledWithSyntheticModelsIsUsableAndStopsCleanly() throws Exception {
        double[][] samples = SyntheticForests.syntheticSamples(32, 0xFEED);
        SyntheticForests.writeForest(dir, "synthetic-a", "synthetic-a.vif", samples, 1L);
        SyntheticForests.writeForest(dir, "synthetic-b", "synthetic-b.vif", samples, 2L);
        AtomicLong now = new AtomicLong(10_000L);
        PlayerDataManager players = new PlayerDataManager();
        AiServices services = newServices(modelsIn(dir), players, now);
        services.start(dir);
        AiStatus status = services.status();
        assertTrue(status.enabled());
        assertTrue(status.usable(), () -> "reason: " + status.unavailableReason());
        assertEquals(2, status.modelStatuses().size());

        // scanTick with a tracked player must submit work without throwing
        PlayerData data = players.load(UUID.randomUUID());
        data.combatTracker().recordAttack(now.get());
        services.scanTick();
        long submittedBeforeStop = services.status().submitted();
        for (int i = 0; i < 100 && submittedBeforeStop == 0; i++) {
            Thread.sleep(10L);
            submittedBeforeStop = services.status().submitted();
        }
        assertTrue(submittedBeforeStop >= 1, "scanTick must enqueue the tracked player synchronously");
        services.stop();
        assertFalse(services.isStarted());
        services.stop(); // idempotent
    }

    @Test
    void rollbackFilterSelectsOnlyTheNamedModel() throws Exception {
        double[][] samples = SyntheticForests.syntheticSamples(32, 0xFEED);
        SyntheticForests.writeForest(dir, "synthetic-a", "synthetic-a.vif", samples, 1L);
        SyntheticForests.writeForest(dir, "synthetic-b", "synthetic-b.vif", samples, 2L);
        AtomicLong now = new AtomicLong(10_000L);
        AiServices services = newServices(modelsIn(dir).with("ai.active-model-id", "synthetic-b"),
                new PlayerDataManager(), now);
        services.start(dir);
        List<String> lines = services.status().modelStatuses();
        assertEquals(2, lines.size());
        assertTrue(lines.stream().anyMatch(l -> l.contains("synthetic-b") && l.contains("loaded")),
                () -> "synthetic-b must be the loaded one: " + lines);
        assertTrue(lines.stream().anyMatch(l -> l.contains("synthetic-a") && l.contains("skipped")),
                () -> "synthetic-a must be skipped by the rollback filter: " + lines);
        assertTrue(services.status().usable());
        services.stop();
    }

    @Test
    void rollbackFilterWithNoMatchIsUnusableWithExplicitReason() throws Exception {
        double[][] samples = SyntheticForests.syntheticSamples(32, 0xFEED);
        SyntheticForests.writeForest(dir, "synthetic-a", "synthetic-a.vif", samples, 1L);
        AtomicLong now = new AtomicLong(10_000L);
        AiServices services = newServices(modelsIn(dir).with("ai.active-model-id", "no-such-model"),
                new PlayerDataManager(), now);
        services.start(dir);
        AiStatus status = services.status();
        assertFalse(status.usable());
        assertTrue(status.unavailableReason().contains("rollback"),
                () -> "reason must explain the rollback filter: " + status.unavailableReason());
        services.stop();
    }
}
