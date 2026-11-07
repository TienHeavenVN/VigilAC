package ac.vigil.ai.ml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import ac.vigil.ai.ml.isolationforest.IsolationForestModel;
import ac.vigil.ai.ml.onnx.OnnxRuntimeAnomalyModel;

/**
 * Loads and verifies models from a directory of {@code *.manifest} files plus
 * their referenced artifacts. Per-model all-or-nothing: a broken manifest,
 * a missing artifact, a checksum mismatch or a schema mismatch marks that
 * model UNAVAILABLE with an explicit reason and NEVER affects the others,
 * the deterministic engine, or plugin startup.
 *
 * <p>Registry results are diagnostics for the admin assistant/status output —
 * they carry no verdicts of any kind.</p>
 */
public final class ModelRegistry {

    private ModelRegistry() {
    }

    /** Outcome for one manifest found on disk. */
    public static final class LoadedModel {
        private final String manifestFile;
        private final ModelManifest manifest; // null only when the manifest itself could not be parsed
        private final AnomalyModel model;     // null when unavailable
        private final String status;

        private LoadedModel(String manifestFile, ModelManifest manifest, AnomalyModel model, String status) {
            this.manifestFile = manifestFile;
            this.manifest = manifest;
            this.model = model;
            this.status = status;
        }

        public String manifestFile() { return manifestFile; }
        public ModelManifest manifest() { return manifest; }
        public AnomalyModel model() { return model; }
        public boolean loaded() { return model != null && model.available(); }
        /** One-line honest status, e.g. "loaded" or "unavailable: checksum mismatch". */
        public String status() { return status; }
        public String modelId() { return manifest == null ? manifestFile : manifest.modelId(); }
    }

    /**
     * Scans {@code directory} for {@code *.manifest} and loads each one.
     * Missing/unreadable directory → empty list (caller logs and continues;
     * AI simply has no models). Never throws.
     */
    public static List<LoadedModel> loadFromDirectory(Path directory) {
        return loadFromDirectory(directory, "");
    }

    /**
     * Same as {@link #loadFromDirectory(Path)} with an optional rollback filter:
     * when {@code activeModelId} is non-blank, only manifests whose modelId matches
     * are read and loaded; every other model is reported as
     * {@code skipped (ai.active-model-id)} and its artifact is never read.
     * This is the supported rollback path: point {@code ai.active-model-id} at a
     * previously known-good version to revert immediately without deleting files.
     * Never throws.
     */
    public static List<LoadedModel> loadFromDirectory(Path directory, String activeModelId) {
        List<LoadedModel> out = new ArrayList<>();
        if (directory == null || !Files.isDirectory(directory)) {
            return out;
        }
        List<Path> manifests;
        try (Stream<Path> stream = Files.list(directory)) {
            manifests = stream.filter(p -> p.getFileName().toString().endsWith(".manifest")).sorted().toList();
        } catch (IOException e) {
            return out;
        }
        String filter = activeModelId == null ? "" : activeModelId.trim();
        for (Path manifestPath : manifests) {
            if (!filter.isEmpty()) {
                // Peek at the modelId cheaply: full validation still happens in loadOne;
                // here we only decide whether to READ this model at all. Parse failures
                // on peek fall through to loadOne to surface the real reason.
                String peeked = peekModelId(manifestPath);
                if (peeked != null && !peeked.equals(filter)) {
                    out.add(new LoadedModel(manifestPath.getFileName().toString(), null, null,
                            "skipped (ai.active-model-id=" + filter + "; model id " + peeked + ")"));
                    continue;
                }
            }
            out.add(loadOne(manifestPath));
        }
        return out;
    }

    /** Lightweight modelId extraction for the rollback filter; null when unreadable (loadOne will report it). */
    private static String peekModelId(Path manifestPath) {
        try {
            byte[] bytes = Files.readAllBytes(manifestPath);
            String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            for (String line : text.split("\r?\n")) {
                String trimmed = line.trim();
                if (trimmed.regionMatches(true, 0, "modelId=", 0, "modelId=".length())) {
                    String value = trimmed.substring("modelId=".length()).trim();
                    return value.isEmpty() ? null : value;
                }
            }
            return null;
        } catch (java.io.IOException e) {
            return null;
        }
    }

    private static LoadedModel loadOne(Path manifestPath) {
        String fileName = manifestPath.getFileName().toString();
        ModelManifest manifest = null;
        try {
            byte[] manifestBytes = Files.readAllBytes(manifestPath);
            manifest = ModelManifest.parse(manifestBytes);
        } catch (IOException | ModelException e) {
            return new LoadedModel(fileName, null, null, "unavailable: manifest invalid (" + abbreviate(e.getMessage()) + ")");
        }
        Path artifactPath = manifestPath.resolveSibling(manifest.artifact());
        byte[] artifactBytes;
        try {
            if (!Files.isRegularFile(artifactPath)) {
                return new LoadedModel(fileName, manifest, null, "unavailable: artifact '" + manifest.artifact() + "' not found");
            }
            if (Files.size(artifactPath) > 256L * 1024 * 1024) {
                return new LoadedModel(fileName, manifest, null, "unavailable: artifact exceeds 256 MiB");
            }
            artifactBytes = Files.readAllBytes(artifactPath);
        } catch (IOException e) {
            return new LoadedModel(fileName, manifest, null, "unavailable: artifact unreadable (" + abbreviate(e.getMessage()) + ")");
        }
        try {
            switch (manifest.algorithm()) {
                case ISOLATION_FOREST:
                    return new LoadedModel(fileName, manifest, new IsolationForestModel(manifest, artifactBytes), "loaded");
                case ONNX:
                    OnnxRuntimeAnomalyModel onnx = new OnnxRuntimeAnomalyModel(manifest, artifactBytes);
                    return new LoadedModel(fileName, manifest, onnx.available() ? onnx : null, onnx.status());
                case XGBOOST:
                    return new LoadedModel(fileName, manifest, null,
                            "unavailable: XGBoost is a training-time algorithm; export to ONNX for runtime inference");
                default:
                    return new LoadedModel(fileName, manifest, null, "unavailable: unknown algorithm " + manifest.algorithm());
            }
        } catch (ModelException e) {
            return new LoadedModel(fileName, manifest, null, "unavailable: " + abbreviate(e.getMessage()));
        }
    }

    private static String abbreviate(String value) {
        return value == null ? "?" : (value.length() <= 160 ? value : value.substring(0, 160) + "…");
    }
}
