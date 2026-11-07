package ac.vigil.ai.ml.onnx;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import ac.vigil.ai.ml.ModelException;
import ac.vigil.ai.ml.ModelManifest;
import ac.vigil.ai.telemetry.AiFeatureSchema;
import ac.vigil.ai.telemetry.AiFeatureSnapshot;

/**
 * onnxruntime is a TEST-ONLY dependency (testImplementation). These tests pin down the
 * degrade-gracefully contract: when the runtime is absent ⇒ UNAVAILABLE + reason, never
 * a throw. The happy path (runtime present + compatible model) IS tested with a real
 * ONNX Runtime in OnnxRuntimeEndToEndTest against a synthetic fixture model.
 */
class OnnxRuntimeAnomalyModelTest {

    private static ModelManifest onnxManifest(byte[] artifactBytes, String shaOverride) {
        String sha = shaOverride != null ? shaOverride : ModelManifest.sha256Hex(artifactBytes);
        String text = String.join("\n",
                ModelManifest.MAGIC,
                "algorithm=ONNX",
                "formatVersion=1",
                "modelId=unit-test-onnx",
                "artifact=unit-test-onnx.onnx",
                "sha256=" + sha,
                "featureSchemaHash=" + AiFeatureSchema.schemaHash(),
                "featureCount=" + AiFeatureSchema.FEATURE_COUNT,
                "normalization=none",
                "createdUtc=2026-11-07T00:00:00Z",
                "notes=unit-test; NOT a real model");
        try {
            return ModelManifest.parse(text.getBytes(StandardCharsets.UTF_8));
        } catch (ModelException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void absentRuntimeYieldsUnavailableNeverThrows() {
        byte[] fakeModel = "definitely-not-an-onnx-model".getBytes(StandardCharsets.UTF_8);
        ModelManifest manifest = onnxManifest(fakeModel, null);
        OnnxRuntimeAnomalyModel model = assertDoesNotThrow(() -> new OnnxRuntimeAnomalyModel(manifest, fakeModel));
        if (model.available()) {
            // onnxruntime IS present in this environment: the fake model must then fail closed instead.
            assertThrows(IllegalStateException.class,
                    () -> model.anomalyScore(new AiFeatureSnapshot(1L, fill(0.5D))));
        } else {
            assertNotNull(model.status());
            assertFalse(model.status().isEmpty(), "unavailability must carry a reason");
            assertThrows(IllegalStateException.class,
                    () -> model.anomalyScore(new AiFeatureSnapshot(1L, fill(0.5D))));
        }
    }

    @Test
    void checksumMismatchIsRejectedBeforeAnythingElse() {
        byte[] fakeModel = "bytes".getBytes(StandardCharsets.UTF_8);
        ModelManifest manifest = onnxManifest(fakeModel, "0".repeat(64));
        assertThrows(ModelException.class, () -> new OnnxRuntimeAnomalyModel(manifest, fakeModel));
    }

    @Test
    void wrongAlgorithmIsRejected() {
        byte[] fakeModel = "bytes".getBytes(StandardCharsets.UTF_8);
        String text = String.join("\n",
                ModelManifest.MAGIC,
                "algorithm=ISOLATION_FOREST",
                "formatVersion=1",
                "modelId=wrong-algo",
                "artifact=forest.vif",
                "sha256=" + ModelManifest.sha256Hex(fakeModel),
                "featureSchemaHash=" + AiFeatureSchema.schemaHash(),
                "featureCount=" + AiFeatureSchema.FEATURE_COUNT,
                "normalization=none");
        ModelException e;
        try {
            ModelManifest manifest = ModelManifest.parse(text.getBytes(StandardCharsets.UTF_8));
            e = assertThrows(ModelException.class, () -> new OnnxRuntimeAnomalyModel(manifest, fakeModel));
        } catch (ModelException parseFailure) {
            throw new IllegalStateException(parseFailure);
        }
        assertTrue(e.getMessage().contains("ONNX"));
    }

    private static double[] fill(double value) {
        double[] v = new double[AiFeatureSchema.FEATURE_COUNT];
        java.util.Arrays.fill(v, value);
        return v;
    }
}
