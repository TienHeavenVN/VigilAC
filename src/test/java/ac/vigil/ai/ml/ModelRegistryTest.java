package ac.vigil.ai.ml;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModelRegistryTest {

    @TempDir
    Path dir;

    private static final double[][] SAMPLES = SyntheticForests.syntheticSamples(48, 0xDECAF);

    @Test
    void emptyDirectoryLoadsNothing() {
        assertTrue(ModelRegistry.loadFromDirectory(dir).isEmpty());
        assertTrue(ModelRegistry.loadFromDirectory(dir.resolve("nonexistent")).isEmpty());
    }

    @Test
    void loadsBothSyntheticModels() throws Exception {
        SyntheticForests.writeForest(dir, "model-a", "model-a.vif", SAMPLES, 1L);
        SyntheticForests.writeForest(dir, "model-b", "model-b.vif", SAMPLES, 2L);
        List<ModelRegistry.LoadedModel> loaded = ModelRegistry.loadFromDirectory(dir);
        assertEquals(2, loaded.size());
        assertTrue(loaded.stream().allMatch(ModelRegistry.LoadedModel::loaded),
                () -> "all synthetic models must load: " + loaded.stream().map(ModelRegistry.LoadedModel::status).toList());
    }

    @Test
    void corruptArtifactFailsOnlyThatModel() throws Exception {
        SyntheticForests.writeForest(dir, "model-a", "model-a.vif", SAMPLES, 1L);
        byte[] good = SyntheticForests.writeForest(dir, "model-b", "model-b.vif", SAMPLES, 2L);
        // corrupt model-b's artifact AFTER writing its valid manifest
        byte[] tampered = Files.readAllBytes(dir.resolve("model-b.vif"));
        tampered[tampered.length / 2] ^= 0x5A;
        Files.write(dir.resolve("model-b.vif"), tampered);
        List<ModelRegistry.LoadedModel> loaded = ModelRegistry.loadFromDirectory(dir);
        ModelRegistry.LoadedModel a = loaded.stream().filter(m -> m.modelId().equals("model-a")).findFirst().orElseThrow();
        ModelRegistry.LoadedModel b = loaded.stream().filter(m -> m.modelId().equals("model-b")).findFirst().orElseThrow();
        assertTrue(a.loaded(), "healthy model must keep loading: " + a.status());
        assertFalse(b.loaded(), "corrupted model must be unavailable");
        assertTrue(b.status().toLowerCase(java.util.Locale.ROOT).contains("unavailable"));
        assertNotNull(good);
    }

    @Test
    void rollbackFilterLoadsOnlyTheNamedModelAndSkipsOthersWithoutReading() throws Exception {
        SyntheticForests.writeForest(dir, "model-a", "model-a.vif", SAMPLES, 1L);
        SyntheticForests.writeForest(dir, "model-b", "model-b.vif", SAMPLES, 2L);
        List<ModelRegistry.LoadedModel> loaded = ModelRegistry.loadFromDirectory(dir, "model-a");
        assertEquals(2, loaded.size(), "every manifest is reported on, even when skipped");
        ModelRegistry.LoadedModel a = loaded.stream().filter(m -> m.modelId().equals("model-a")).findFirst().orElseThrow();
        ModelRegistry.LoadedModel b = loaded.stream().filter(m -> m.modelId().equals("model-b")).findFirst().orElseThrow();
        assertTrue(a.loaded());
        assertFalse(b.loaded());
        assertTrue(b.status().contains("skipped"), "rollback filter must mark non-active models skipped: " + b.status());
        assertTrue(b.status().contains("ai.active-model-id"));
    }

    @Test
    void rollbackFilterWithNoMatchLoadsNothingButKeepsReporting() throws Exception {
        SyntheticForests.writeForest(dir, "model-a", "model-a.vif", SAMPLES, 1L);
        List<ModelRegistry.LoadedModel> loaded = ModelRegistry.loadFromDirectory(dir, "does-not-exist");
        assertEquals(1, loaded.size());
        assertFalse(loaded.get(0).loaded());
        assertTrue(loaded.get(0).status().contains("skipped"));
    }

    @Test
    void missingArtifactIsReportedNotThrown() throws Exception {
        SyntheticForests.writeForest(dir, "model-a", "model-a.vif", SAMPLES, 1L);
        Files.delete(dir.resolve("model-a.vif"));
        List<ModelRegistry.LoadedModel> loaded = ModelRegistry.loadFromDirectory(dir);
        assertEquals(1, loaded.size());
        assertFalse(loaded.get(0).loaded());
        assertTrue(loaded.get(0).status().contains("not found"));
    }
}
