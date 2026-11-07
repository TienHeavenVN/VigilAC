package ac.vigil.ai.ml;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Versioned model manifest. Format (UTF-8, line-based, {@code key=value}):
 *
 * <pre>
 * VigilAC-ModelManifest/1
 * algorithm=ISOLATION_FOREST
 * formatVersion=1
 * modelId=my-model
 * artifact=my-model.vif
 * sha256=&lt;64 lowercase hex&gt;
 * featureSchemaHash=&lt;64 lowercase hex, see AiFeatureSchema.schemaHash()&gt;
 * featureCount=22
 * normalization=none|zscore
 * means=&lt;22 doubles, comma-separated&gt;   (zscore only)
 * stddevs=&lt;22 doubles&gt;                (zscore only)
 * createdUtc=2026-11-07T00:00:00Z
 * notes=&lt;free text; must state validation status&gt;
 * </pre>
 *
 * Loading is all-or-nothing: any malformed field rejects the manifest, and
 * the artifact's SHA-256 is verified against the file bytes before the model
 * is used. Unknown algorithms, unknown format major versions, or a schema
 * hash that does not match {@code AiFeatureSchema.schemaHash()} are fatal to
 * that model (it is registered as UNAVAILABLE with the reason) — never
 * "best-effort" loads.
 */
public final class ModelManifest {

    public enum Normalization { NONE, ZSCORE }

    public static final String MAGIC = "VigilAC-ModelManifest/1";
    public static final int SUPPORTED_FORMAT_VERSION = 1;

    private final ModelAlgorithm algorithm;
    private final int formatVersion;
    private final String modelId;
    private final String artifact;
    private final String sha256;
    private final String featureSchemaHash;
    private final int featureCount;
    private final Normalization normalization;
    private final double[] means;
    private final double[] stddevs;
    private final String createdUtc;
    private final String notes;

    private ModelManifest(ModelAlgorithm algorithm, int formatVersion, String modelId, String artifact,
                          String sha256, String featureSchemaHash, int featureCount,
                          Normalization normalization, double[] means, double[] stddevs,
                          String createdUtc, String notes) {
        this.algorithm = algorithm;
        this.formatVersion = formatVersion;
        this.modelId = modelId;
        this.artifact = artifact;
        this.sha256 = sha256;
        this.featureSchemaHash = featureSchemaHash;
        this.featureCount = featureCount;
        this.normalization = normalization;
        this.means = means;
        this.stddevs = stddevs;
        this.createdUtc = createdUtc;
        this.notes = notes;
    }

    public ModelAlgorithm algorithm() { return algorithm; }
    public int formatVersion() { return formatVersion; }
    public String modelId() { return modelId; }
    public String artifact() { return artifact; }
    public String sha256() { return sha256; }
    public String featureSchemaHash() { return featureSchemaHash; }
    public int featureCount() { return featureCount; }
    public Normalization normalization() { return normalization; }
    public double[] means() { return means == null ? null : means.clone(); }
    public double[] stddevs() { return stddevs == null ? null : stddevs.clone(); }
    public String createdUtc() { return createdUtc; }
    public String notes() { return notes; }

    /** Parses and fully validates the manifest text (artifact hash is verified separately by the registry). */
    public static ModelManifest parse(byte[] manifestBytes) throws ModelException {
        if (manifestBytes == null || manifestBytes.length == 0) {
            throw new ModelException("empty manifest");
        }
        if (manifestBytes.length > 64 * 1024) {
            throw new ModelException("manifest exceeds 64 KiB");
        }
        String text = new String(manifestBytes, StandardCharsets.UTF_8);
        String[] lines = text.split("\\r?\\n");
        if (lines.length == 0 || !MAGIC.equals(lines[0].trim())) {
            throw new ModelException("missing magic line '" + MAGIC + "'");
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                throw new ModelException("malformed manifest line " + (i + 1) + ": '" + abbreviate(line) + "'");
            }
            String key = line.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(eq + 1).trim();
            if (map.put(key, value) != null) {
                throw new ModelException("duplicate manifest key '" + key + "'");
            }
        }

        ModelAlgorithm algorithm = parseEnum(ModelAlgorithm.class, required(map, "algorithm"), "algorithm");
        int formatVersion = parseInt(required(map, "formatversion"), "formatVersion");
        if (formatVersion != SUPPORTED_FORMAT_VERSION) {
            throw new ModelException("unsupported formatVersion " + formatVersion + " (supported: " + SUPPORTED_FORMAT_VERSION + ")");
        }
        String modelId = required(map, "modelid");
        if (modelId.isEmpty() || modelId.length() > 128) {
            throw new ModelException("modelId must be 1..128 chars");
        }
        String artifact = required(map, "artifact");
        if (artifact.contains("/") || artifact.contains("\\") || artifact.contains("..")) {
            throw new ModelException("artifact must be a plain file name, got: " + artifact);
        }
        String sha256 = required(map, "sha256").toLowerCase(Locale.ROOT);
        requireHex(sha256, 64, "sha256");
        String schemaHash = required(map, "featureschemahash").toLowerCase(Locale.ROOT);
        requireHex(schemaHash, 64, "featureSchemaHash");
        int featureCount = parseInt(required(map, "featurecount"), "featureCount");
        if (featureCount != ac.vigil.ai.telemetry.AiFeatureSchema.FEATURE_COUNT) {
            throw new ModelException("featureCount " + featureCount + " != schema " + ac.vigil.ai.telemetry.AiFeatureSchema.FEATURE_COUNT);
        }
        Normalization normalization = parseEnum(Normalization.class, required(map, "normalization"), "normalization");
        double[] means = null;
        double[] stddevs = null;
        if (normalization == Normalization.ZSCORE) {
            means = parseDoubles(required(map, "means"), "means");
            stddevs = parseDoubles(required(map, "stddevs"), "stddevs");
            if (means.length != featureCount || stddevs.length != featureCount) {
                throw new ModelException("means/stddevs must have " + featureCount + " entries");
            }
            for (double sd : stddevs) {
                if (!(sd >= 0.0D) || Double.isNaN(sd)) {
                    throw new ModelException("stddevs must be >= 0 and finite");
                }
            }
        }
        String createdUtc = map.getOrDefault("createdutc", "unknown");
        String notes = map.getOrDefault("notes", "");

        return new ModelManifest(algorithm, formatVersion, modelId, artifact, sha256, schemaHash,
                featureCount, normalization, means, stddevs, createdUtc, notes);
    }

    /** Returns true when this manifest was trained against the current feature schema. */
    public boolean matchesCurrentSchema() {
        return featureSchemaHash.equalsIgnoreCase(ac.vigil.ai.telemetry.AiFeatureSchema.schemaHash());
    }

    /** Verifies the artifact bytes against the manifest's SHA-256. */
    public boolean verifyArtifact(byte[] artifactBytes) {
        return sha256Hex(artifactBytes).equalsIgnoreCase(sha256);
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String required(Map<String, String> map, String key) throws ModelException {
        String value = map.get(key);
        if (value == null) {
            throw new ModelException("missing required key '" + key + "'");
        }
        return value;
    }

    private static int parseInt(String value, String key) throws ModelException {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new ModelException("key '" + key + "' is not an integer: '" + abbreviate(value) + "'");
        }
    }

    private static double[] parseDoubles(String value, String key) throws ModelException {
        String[] parts = value.split(",");
        double[] out = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Double.parseDouble(parts[i].trim());
            } catch (NumberFormatException e) {
                throw new ModelException("key '" + key + "' entry " + i + " is not a double");
            }
            if (Double.isNaN(out[i]) || Double.isInfinite(out[i])) {
                throw new ModelException("key '" + key + "' entry " + i + " is not finite");
            }
        }
        return out;
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String key) throws ModelException {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ModelException("key '" + key + "' has unknown value '" + abbreviate(value) + "'");
        }
    }

    private static void requireHex(String value, int length, String key) throws ModelException {
        if (value.length() != length) {
            throw new ModelException("key '" + key + "' must be " + length + " hex chars, got " + value.length());
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                throw new ModelException("key '" + key + "' contains non-hex char '" + c + "'");
            }
        }
    }

    private static String abbreviate(String value) {
        return value.length() <= 48 ? value : value.substring(0, 48) + "…";
    }
}
