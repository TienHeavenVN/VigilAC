package ac.vigil.ai.ml.onnx;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import ac.vigil.ai.ml.AnomalyModel;
import ac.vigil.ai.ml.ModelAlgorithm;
import ac.vigil.ai.ml.ModelException;
import ac.vigil.ai.ml.ModelManifest;
import ac.vigil.ai.telemetry.AiFeatureSnapshot;
import ac.vigil.ai.telemetry.AiFeatureSchema;

/**
 * Optional ONNX Runtime adapter, looked up reflectively — ONNX Runtime Java is
 * an OPTIONAL runtime dependency: never bundled, never required. When the
 * library is absent (the default), {@link #available()} is false and every
 * call site degrades to "no ML score"; the deterministic engine is unaffected.
 *
 * <p><b>VERIFICATION STATUS: the I/O contract has been executed end-to-end</b>
 * in CI against a SYNTHETIC fixture model (test-only onnxruntime dependency;
 * see OnnxRuntimeEndToEndTest). This proves tensor shapes, the named input
 * {@code input}, the single float output in [0,1] and checksum/schema gates —
 * and nothing else. No ONNX model ships with VigilAC; detection quality of any
 * model on any workload is entirely unverified and not claimed. The adapter
 * probes the installed library instead of assuming API shapes, so a mismatch
 * degrades to UNAVAILABLE with an explicit reason rather than a class-cast
 * surprise. Do not treat a loaded status as evidence of detection quality.</p>
 *
 * <p>Documented I/O contract for compatible models: one tensor input shaped
 * {@code [1, featureCount]} (AiFeatureSchema order) named {@code input}; one
 * tensor output whose first float is an anomaly score in [0, 1]. Models that
 * do not match this contract report UNAVAILABLE at load time or throw inside
 * {@link #anomalyScore(AiFeatureSnapshot)}, which the worker pool catches and
 * counts as inference failure — never as a violation.</p>
 */
public final class OnnxRuntimeAnomalyModel implements AnomalyModel {

    private static final String ENVIRONMENT_CLASS = "ai.onnxruntime.OrtEnvironment";
    private static final String SESSION_OPTIONS_CLASS = "ai.onnxruntime.OrtSessionOptions";
    private static final String ONNX_TENSOR_CLASS = "ai.onnxruntime.OnnxTensor";

    private final ModelManifest manifest;
    private final byte[] artifactBytes;
    private final Object environment; // OrtEnvironment, or null
    private final Object session;     // OrtSession, or null
    private final String unavailableReason; // null iff session != null

    public OnnxRuntimeAnomalyModel(ModelManifest manifest, byte[] artifactBytes) throws ModelException {
        if (manifest.algorithm() != ModelAlgorithm.ONNX) {
            throw new ModelException("manifest algorithm is " + manifest.algorithm() + ", expected ONNX");
        }
        if (!manifest.matchesCurrentSchema()) {
            throw new ModelException("model schema mismatch (manifest " + manifest.featureSchemaHash()
                    + ", current " + AiFeatureSchema.schemaHash() + ")");
        }
        if (!manifest.verifyArtifact(artifactBytes)) {
            throw new ModelException("artifact SHA-256 mismatch for model '" + manifest.modelId() + "'");
        }
        this.manifest = manifest;
        this.artifactBytes = artifactBytes;

        Object env = null;
        Object sess = null;
        String reason = null;
        try {
            Class<?> ortEnvironment = Class.forName(ENVIRONMENT_CLASS);
            env = ortEnvironment.getMethod("getEnvironment").invoke(null);
            sess = createSession(env, artifactBytes);
            if (sess == null) {
                reason = "no supported createSession(byte[] …) method on the installed ONNX Runtime";
                env = null;
            }
        } catch (ClassNotFoundException e) {
            reason = "ONNX Runtime not installed on this server";
        } catch (InvocationTargetException e) {
            reason = "ONNX Runtime failed to initialize: " + abbreviate(String.valueOf(e.getTargetException()));
            sess = null;
            env = null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            reason = "ONNX Runtime does not expose the expected API: " + abbreviate(e.toString());
            sess = null;
            env = null;
        }
        this.environment = env;
        this.session = sess;
        this.unavailableReason = reason;
    }

    /** Probes for a byte[]-taking createSession overload (with or without session options). */
    private static Object createSession(Object environment, byte[] artifactBytes) throws ReflectiveOperationException {
        Object options = null;
        try {
            Class<?> optionsClass = Class.forName(SESSION_OPTIONS_CLASS);
            options = optionsClass.getDeclaredConstructor().newInstance();
        } catch (Throwable ignored) {
            // options are optional; try without them
        }
        for (Method method : environment.getClass().getMethods()) {
            if (!"createSession".equals(method.getName())) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            try {
                if (options != null && params.length == 2 && params[0] == byte[].class && params[1].isInstance(options)) {
                    return method.invoke(environment, artifactBytes, options);
                }
                if (params.length == 1 && params[0] == byte[].class) {
                    return method.invoke(environment, artifactBytes);
                }
            } catch (InvocationTargetException ignored) {
                // try the next overload
            }
        }
        return null;
    }

    @Override
    public ModelManifest manifest() {
        return manifest;
    }

    @Override
    public String modelId() {
        return manifest.modelId();
    }

    @Override
    public boolean available() {
        return session != null;
    }

    @Override
    public String status() {
        return session != null ? "loaded (ONNX Runtime)" : unavailableReason;
    }

    @Override
    public double anomalyScore(AiFeatureSnapshot snapshot) {
        if (session == null) {
            throw new IllegalStateException("model is unavailable: " + unavailableReason);
        }
        if (snapshot == null || snapshot.size() != AiFeatureSchema.FEATURE_COUNT) {
            throw new IllegalArgumentException("snapshot does not match feature count");
        }
        double[] raw = snapshot.values();
        double[] x = raw;
        if (manifest.normalization() == ModelManifest.Normalization.ZSCORE) {
            double[] means = manifest.means();
            double[] stddevs = manifest.stddevs();
            x = new double[raw.length];
            for (int i = 0; i < raw.length; i++) {
                x[i] = stddevs[i] == 0.0D ? 0.0D : (raw[i] - means[i]) / stddevs[i];
            }
        }
        float[] input = new float[x.length];
        for (int i = 0; i < x.length; i++) {
            input[i] = (float) x[i];
        }
        try {
            Class<?> onnxTensor = Class.forName(ONNX_TENSOR_CLASS);
            // onnxruntime's public tensor factory is Buffer-based: createTensor(env, FloatBuffer, shape)
            Method create = findCreateTensorMethod(onnxTensor, environment);
            Object tensor = create.invoke(null, environment, java.nio.FloatBuffer.wrap(input), new long[] {1L, input.length});
            Map<String, Object> feeds = Map.of("input", tensor);
            Object result = session.getClass().getMethod("run", Map.class).invoke(session, feeds);
            Object firstOutput = null;
            if (result instanceof Map) {
                for (Object value : ((Map<?, ?>) result).values()) {
                    firstOutput = value;
                    break;
                }
            } else if (result != null) {
                try {
                    firstOutput = result.getClass().getMethod("get", Integer.TYPE).invoke(result, 0);
                } catch (NoSuchMethodException e) {
                    firstOutput = result.getClass().getMethod("get", String.class).invoke(result, "output");
                }
            }
            if (firstOutput == null) {
                throw new IllegalStateException("ONNX model returned no outputs");
            }
            Object buffer = firstOutput.getClass().getMethod("getFloatBuffer").invoke(firstOutput);
            if (!(buffer instanceof java.nio.FloatBuffer)) {
                throw new IllegalStateException("ONNX first output is not a FloatBuffer");
            }
            float score = ((java.nio.FloatBuffer) buffer).get(0);
            if (Float.isNaN(score) || score < 0.0F || score > 1.0F) {
                throw new IllegalStateException("ONNX anomaly score out of [0,1]: " + score);
            }
            return score;
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("unsupported ONNX model I/O (expected input tensor 'input', float output): "
                    + abbreviate(e.toString()), e);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("ONNX inference failed: " + abbreviate(String.valueOf(e.getTargetException())), e);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("ONNX inference failed: " + abbreviate(e.toString()), e);
        }
    }

    /**
     * Probe for the static {@code createTensor(env, FloatBuffer, long[])} overload.
     * onnxruntime publishes Buffer-based tensor factories (the float[] convenience
     * overload is not part of the public API on 1.31.x), and the first parameter's
     * declared type has changed between releases, so scan the methods instead of
     * trusting one exact signature.
     */
    private static Method findCreateTensorMethod(Class<?> onnxTensor, Object environment) throws NoSuchMethodException {
        for (Method method : onnxTensor.getMethods()) {
            if (!"createTensor".equals(method.getName())) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 3) {
                continue;
            }
            if (params[1] == java.nio.FloatBuffer.class && params[2] == long[].class
                    && params[0].isAssignableFrom(environment.getClass())) {
                return method;
            }
        }
        throw new NoSuchMethodException("ai.onnxruntime.OnnxTensor.createTensor(env, FloatBuffer, long[])");
    }

    /** Releases the native session/environment when present. Never throws. */
    public void close() {
        try {
            if (session != null) {
                session.getClass().getMethod("close").invoke(session);
            }
        } catch (Throwable ignored) {
            // best-effort cleanup only
        }
        try {
            if (environment != null) {
                environment.getClass().getMethod("close").invoke(environment);
            }
        } catch (Throwable ignored) {
            // best-effort cleanup only
        }
    }

    private static String abbreviate(String value) {
        return value == null ? "?" : (value.length() <= 160 ? value : value.substring(0, 160) + "…");
    }
}
