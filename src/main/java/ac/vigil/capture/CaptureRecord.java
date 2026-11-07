package ac.vigil.capture;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One capture row: exactly the Stage 3 corpus contract (docs/DATASET.md §1).
 * Built by {@link CaptureService} from a validated feature snapshot; the writer
 * serializes it with {@link CaptureJson}. Key ORDER is fixed; consumers must not
 * rely on it, but the pipeline does not care either way.
 */
public final class CaptureRecord {

    private static final DateTimeFormatter COLLECTED = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(ZoneOffset.UTC);

    /** The exact key set the Stage 3 pipeline requires, plus optional provenance_note. */
    public static final List<String> KEYS = List.of(
            "player_pseudonym", "session_pseudonym", "mc_version", "client_category", "input_category",
            "tps_1m", "ping_ms", "feature_schema_hash", "label", "label_source", "label_confidence",
            "collected_utc", "scenario", "features", "provenance_note");

    final String playerPseudonym;
    final String sessionPseudonym;
    final String mcVersion;
    final String clientCategory;
    final String inputCategory;
    final double tps1m;
    final double pingMs;
    final String featureSchemaHash;
    final String label;
    final String labelSource;
    final double labelConfidence;
    final Instant collectedAt;
    final String scenario;
    final double[] features;
    final String provenanceNote;

    public CaptureRecord(String playerPseudonym, String sessionPseudonym, String mcVersion,
                         String clientCategory, String inputCategory,
                         double tps1m, double pingMs, String featureSchemaHash,
                         String label, String labelSource, double labelConfidence,
                         Instant collectedAt, String scenario, double[] features, String provenanceNote) {
        this.playerPseudonym = playerPseudonym;
        this.sessionPseudonym = sessionPseudonym;
        this.mcVersion = mcVersion;
        this.clientCategory = clientCategory;
        this.inputCategory = inputCategory;
        this.tps1m = tps1m;
        this.pingMs = pingMs;
        this.featureSchemaHash = featureSchemaHash;
        this.label = label;
        this.labelSource = labelSource;
        this.labelConfidence = labelConfidence;
        this.collectedAt = collectedAt;
        this.scenario = scenario;
        this.features = features.clone();
        this.provenanceNote = provenanceNote;
    }

    /** Serialized single line (no trailing newline). */
    public String toJsonLine() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("player_pseudonym", playerPseudonym);
        fields.put("session_pseudonym", sessionPseudonym);
        fields.put("mc_version", mcVersion);
        fields.put("client_category", clientCategory);
        fields.put("input_category", inputCategory);
        fields.put("tps_1m", tps1m);
        fields.put("ping_ms", pingMs);
        fields.put("feature_schema_hash", featureSchemaHash);
        fields.put("label", label);
        fields.put("label_source", labelSource);
        fields.put("label_confidence", labelConfidence);
        fields.put("collected_utc", COLLECTED.format(collectedAt));
        fields.put("scenario", scenario);
        List<Object> feats = new ArrayList<>(features.length);
        for (double v : features) {
            feats.add(v);
        }
        fields.put("features", feats);
        if (provenanceNote != null && !provenanceNote.isBlank()) {
            fields.put("provenance_note", provenanceNote);
        }
        return CaptureJson.object(fields);
    }
}
