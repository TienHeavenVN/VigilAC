package ac.vigil.ai.ml;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import ac.vigil.ai.telemetry.AiFeatureSchema;

/**
 * Cross-implementation lock: tools/dataset/vigilac_dataset.py computes the same hash
 * from the documented NAMES list. Both sides must agree byte-for-byte or model
 * manifests become unreadable across the Java/Python boundary.
 */
class SchemaHashGoldenTest {

    @Test
    void schemaHashMatchesCrossImplementationConstant() {
        assertEquals("2f2b0a3eb4563552b5c995c285f7179ce34490c450c46b63e9dbc022916ee688",
                AiFeatureSchema.schemaHash(),
                "schema hash drift — update tools/dataset/vigilac_dataset.py and re-verify manifests");
    }
}
