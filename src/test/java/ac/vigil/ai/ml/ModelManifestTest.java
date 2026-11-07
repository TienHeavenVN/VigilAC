package ac.vigil.ai.ml;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.StringJoiner;
import org.junit.jupiter.api.Test;
import ac.vigil.ai.telemetry.AiFeatureSchema;

class ModelManifestTest {

    private static final byte[] ARTIFACT = "fake-artifact-bytes".getBytes(StandardCharsets.UTF_8);
    private static final String ARTIFACT_SHA = ModelManifest.sha256Hex(ARTIFACT);
    private static final String SCHEMA_SHA = AiFeatureSchema.schemaHash();

    private static String baseManifest() {
        return String.join("\n",
                ModelManifest.MAGIC,
                "algorithm=ISOLATION_FOREST",
                "formatVersion=1",
                "modelId=test-forest",
                "artifact=test-forest.vif",
                "sha256=" + ARTIFACT_SHA,
                "featureSchemaHash=" + SCHEMA_SHA,
                "featureCount=" + AiFeatureSchema.FEATURE_COUNT,
                "normalization=none",
                "createdUtc=2026-11-07T00:00:00Z",
                "notes=unit-test manifest; no dataset used");
    }

    private static ModelManifest parse(String text) throws ModelException {
        return ModelManifest.parse(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void parsesValidManifest() throws Exception {
        ModelManifest manifest = parse(baseManifest());
        assertEquals("test-forest", manifest.modelId());
        assertEquals(ModelAlgorithm.ISOLATION_FOREST, manifest.algorithm());
        assertEquals(1, manifest.formatVersion());
        assertEquals(ModelManifest.Normalization.NONE, manifest.normalization());
        assertTrue(manifest.matchesCurrentSchema());
        assertTrue(manifest.verifyArtifact(ARTIFACT));
        assertEquals(AiFeatureSchema.FEATURE_COUNT, manifest.featureCount());
    }

    @Test
    void rejectsEmptyAndOversizedManifests() {
        assertThrows(ModelException.class, () -> ModelManifest.parse(new byte[0]));
        byte[] huge = new byte[64 * 1024 + 1];
        assertThrows(ModelException.class, () -> ModelManifest.parse(huge));
    }

    @Test
    void rejectsMissingMagic() {
        String text = baseManifest().replace(ModelManifest.MAGIC, "VigilAC-ModelManifest/2");
        assertThrows(ModelException.class, () -> parse(text));
    }

    @Test
    void rejectsUnsupportedFormatVersion() {
        String text = baseManifest().replace("formatVersion=1", "formatVersion=2");
        ModelException e = assertThrows(ModelException.class, () -> parse(text));
        assertTrue(e.getMessage().contains("formatVersion"));
    }

    @Test
    void rejectsArtifactWithPathSeparatorsOrTraversal() {
        for (String bad : new String[] {"a/b.vif", "a\\b.vif", "..\\b.vif", "a..b.vif"}) {
            String text = baseManifest().replace("artifact=test-forest.vif", "artifact=" + bad);
            assertThrows(ModelException.class, () -> parse(text), "artifact must be a plain file name: " + bad);
        }
    }

    @Test
    void rejectsNonHexHashes() {
        String text = baseManifest().replace("sha256=" + ARTIFACT_SHA, "sha256=" + "g".repeat(64));
        assertThrows(ModelException.class, () -> parse(text));
        String text2 = baseManifest().replace("sha256=" + ARTIFACT_SHA, "sha256=abc");
        assertThrows(ModelException.class, () -> parse(text2));
    }

    @Test
    void rejectsWrongFeatureCount() {
        String text = baseManifest().replace("featureCount=" + AiFeatureSchema.FEATURE_COUNT, "featureCount=21");
        assertThrows(ModelException.class, () -> parse(text));
    }

    @Test
    void zscoreRequiresMeansAndStddevs() {
        String text = baseManifest().replace("normalization=none", "normalization=zscore");
        assertThrows(ModelException.class, () -> parse(text));
    }

    @Test
    void zscoreParsesMeansAndStddevs() throws Exception {
        StringJoiner means = new StringJoiner(",");
        StringJoiner stddevs = new StringJoiner(",");
        for (int i = 0; i < AiFeatureSchema.FEATURE_COUNT; i++) {
            means.add(Double.toString(i * 0.5D));
            stddevs.add(Double.toString(1.0D + i * 0.01D));
        }
        String text = baseManifest().replace("normalization=none",
                "normalization=zscore\nmeans=" + means + "\nstddevs=" + stddevs);
        ModelManifest manifest = parse(text);
        assertEquals(ModelManifest.Normalization.ZSCORE, manifest.normalization());
        assertEquals(AiFeatureSchema.FEATURE_COUNT, manifest.means().length);
        assertEquals(AiFeatureSchema.FEATURE_COUNT, manifest.stddevs().length);
    }

    @Test
    void schemaHashMismatchIsDetectedNotFatalAtParse() throws Exception {
        String foreignHash = "b".repeat(64);
        String text = baseManifest().replace("featureSchemaHash=" + SCHEMA_SHA, "featureSchemaHash=" + foreignHash);
        ModelManifest manifest = parse(text); // parse is fine; compatibility check is separate
        assertFalse(manifest.matchesCurrentSchema(), "wrong schema hash must be flagged by matchesCurrentSchema()");
    }

    @Test
    void duplicateKeysAreRejected() {
        String text = baseManifest() + "\nmodelId=other";
        assertThrows(ModelException.class, () -> parse(text));
    }

    @Test
    void unknownAlgorithmIsRejected() {
        String text = baseManifest().replace("algorithm=ISOLATION_FOREST", "algorithm=GRADIENT_BOOST");
        assertThrows(ModelException.class, () -> parse(text));
    }

    @Test
    void artifactChecksumIsEnforced() throws Exception {
        ModelManifest manifest = parse(baseManifest());
        assertFalse(manifest.verifyArtifact("tampered".getBytes(StandardCharsets.UTF_8)));
    }
}
