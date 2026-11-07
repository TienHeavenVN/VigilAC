package ac.vigil.ai.ml.onnx;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ac.vigil.ai.ml.AnomalyModel;
import ac.vigil.ai.ml.ModelRegistry;
import ac.vigil.ai.telemetry.AiFeatureSchema;
import ac.vigil.ai.telemetry.AiFeatureSnapshot;

/**
 * End-to-end contract test for the ONNX adapter with a REAL ONNX Runtime on the
 * test classpath (testImplementation only — never shipped, never required at runtime).
 *
 * <p><b>The fixture model is SYNTHETIC.</b> It was trained by
 * {@code tools/dataset/train_supervised.py} on the synthetic test fixture corpus
 * ({@code tools/dataset/tests/fixtures/synthetic_corpus}), which is generated test
 * data — NOT gameplay. The asserted scores prove the runtime contract (input tensor
 * {@code input}, float[1,22]; a single output float whose first value is in [0,1];
 * SHA-256 artifact verification; feature-schema compatibility), matching the
 * reference scores computed with Python onnxruntime (max |delta| was &lt; 3e-8).
 * They say NOTHING about detection of real cheats, and this model MUST NOT be
 * loaded in production.</p>
 */
class OnnxRuntimeEndToEndTest {

    private static final String FIXTURE_DIR = "onnx/synthetic-fixture-xgb-tiny-0001";
    private static final String FIXTURE_MODEL_ID = "vigilac-xgb-fixture-tiny-0001";

    /** LEGIT row features from the synthetic fixture corpus (float32-relevant precision). */
    private static final double[] LEGIT = {9.387619, 7.281814, 0.411547, 87.494254, 37.160447, 3.816999,
            0.000000, 9.440130, 217.891839, 13.294172, 5.000000, 2.727927, 12.000000, 18.414173, 20.936498,
            18.656167, 495.473516, 0.000000, 0.000000, 57.537811, 19.842849, 1.000000};

    /** CHEAT_VERIFIED row features from the synthetic fixture corpus. */
    private static final double[] CHEAT = {21.269450, 18.755047, 0.029369, 43.292602, 1.493012, 1.363000,
            3.000000, 17.960038, 578.426970, 68.568369, 15.000000, 4.627655, 50.000000, 21.081164, 23.277603,
            23.606871, 8.096129, 0.000000, 0.000000, 53.753412, 19.732901, 1.000000};

    /** Reference scores from Python onnxruntime 1.31.1 (CPU) on the same artifact. */
    private static final double LEGIT_REFERENCE = 0.12896090745925903D;
    private static final double CHEAT_REFERENCE = 0.855519711971283D;

    @TempDir
    Path modelsDir;

    @Test
    void fixtureModelLoadsAndScoreMatchesPythonReference() throws Exception {
        assertEquals(AiFeatureSchema.FEATURE_COUNT, LEGIT.length);
        assertEquals(AiFeatureSchema.FEATURE_COUNT, CHEAT.length);
        for (String name : List.of("model.onnx", "model.manifest")) {
            String resource = FIXTURE_DIR + "/" + name;
            try (InputStream in = OnnxRuntimeEndToEndTest.class.getClassLoader().getResourceAsStream(resource)) {
                assertNotNull(in, "missing test resource " + resource);
                Files.copy(in, modelsDir.resolve(name));
            }
        }

        List<ModelRegistry.LoadedModel> loaded = ModelRegistry.loadFromDirectory(modelsDir, "");
        assertEquals(1, loaded.size());
        ModelRegistry.LoadedModel entry = loaded.get(0);
        assertEquals(FIXTURE_MODEL_ID, entry.modelId());
        assertTrue(entry.loaded(),
                "fixture ONNX model must load with onnxruntime on the test classpath; status=" + entry.status());

        AnomalyModel model = entry.model();
        double legit = model.anomalyScore(new AiFeatureSnapshot(1_000L, LEGIT));
        double cheat = model.anomalyScore(new AiFeatureSnapshot(2_000L, CHEAT));

        assertEquals(LEGIT_REFERENCE, legit, 1e-4D, "Java onnxruntime score must match Python onnxruntime reference");
        assertEquals(CHEAT_REFERENCE, cheat, 1e-4D, "Java onnxruntime score must match Python onnxruntime reference");
        assertTrue(cheat > legit, "cheat-like fixture row must score higher than legit-like fixture row");

        // deterministic: same input ⇒ same score
        assertEquals(legit, model.anomalyScore(new AiFeatureSnapshot(3_000L, LEGIT)), 0.0D);
    }
}
