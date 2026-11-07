package ac.vigil.ai.ml.isolationforest;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Isolation Forest trainer (Liu, Ting &amp; Zhou, 2008) in pure Java — deterministic
 * for a given seed. Used by the training/evaluation pipeline (Stage 3); never
 * on a hot path.
 *
 * <p>Contract and honesty notes (read before using):</p>
 * <ul>
 *   <li>Training quality is entirely a function of the supplied samples.
 *       <b>This class does not ship, fetch or synthesize a "real" dataset</b>;
 *       callers must provide their own, and any model trained here must be
 *       evaluated on a held-out split by player and session before being
 *       loaded by the plugin.</li>
 *   <li>The same (samples, nTrees, maxSamples, seed) always produces a
 *       bit-identical artifact — mandatory for manifest checksums.</li>
 *   <li>Normalization stored in the artifact is population z-score; constant
 *       features get stddev 0 and map to 0 at inference time.</li>
 * </ul>
 */
public final class IsolationForestTrainer {

    public static final int DEFAULT_TREES = 100;
    public static final int DEFAULT_MAX_SAMPLES = 256;
    public static final int MIN_TRAINING_SAMPLES = 8;

    private IsolationForestTrainer() {
    }

    /**
     * @param samples   row-major feature matrix; rows = players/sessions, columns = features (uniform width)
     * @param nTrees    ensemble size (>= 1)
     * @param maxSamples subsample size per tree (>= 2); the effective subsample is min(maxSamples, n rows)
     * @param seed      PRNG seed for deterministic output
     * @return trained forest (also carries normalization stats)
     */
    public static TrainedForest train(double[][] samples, int nTrees, int maxSamples, long seed) {
        if (samples == null || samples.length < MIN_TRAINING_SAMPLES) {
            throw new IllegalArgumentException("need at least " + MIN_TRAINING_SAMPLES + " samples, got "
                    + (samples == null ? 0 : samples.length));
        }
        int nFeatures = samples[0].length;
        if (nFeatures == 0) {
            throw new IllegalArgumentException("samples must have >= 1 feature");
        }
        for (int i = 0; i < samples.length; i++) {
            if (samples[i].length != nFeatures) {
                throw new IllegalArgumentException("sample " + i + " has " + samples[i].length + " features; expected " + nFeatures);
            }
            for (double v : samples[i]) {
                if (Double.isNaN(v) || Double.isInfinite(v)) {
                    throw new IllegalArgumentException("sample " + i + " contains non-finite value");
                }
            }
        }
        if (nTrees < 1) {
            throw new IllegalArgumentException("nTrees must be >= 1");
        }
        int subsample = Math.min(Math.max(2, maxSamples), samples.length);

        // population statistics for z-score normalization
        double[] means = new double[nFeatures];
        for (double[] row : samples) {
            for (int f = 0; f < nFeatures; f++) {
                means[f] += row[f];
            }
        }
        for (int f = 0; f < nFeatures; f++) {
            means[f] /= samples.length;
        }
        double[] stddevs = new double[nFeatures];
        for (double[] row : samples) {
            for (int f = 0; f < nFeatures; f++) {
                double d = row[f] - means[f];
                stddevs[f] += d * d;
            }
        }
        for (int f = 0; f < nFeatures; f++) {
            stddevs[f] = Math.sqrt(stddevs[f] / samples.length);
        }

        int maxDepth = (int) Math.ceil(Math.log(subsample) / Math.log(2.0D));
        Random random = new Random(seed);
        List<TrainedForest.Node[]> trees = new ArrayList<>(nTrees);
        for (int t = 0; t < nTrees; t++) {
            int[] indices = subsampleIndices(samples.length, subsample, random);
            List<TrainedForest.Node> nodes = new ArrayList<>(subsample * 2);
            buildNode(nodes, samples, indices, 0, maxDepth, nFeatures, random);
            trees.add(nodes.toArray(new TrainedForest.Node[0]));
        }
        return TrainedForest.of(nFeatures, subsample, trees,
                ac.vigil.ai.telemetry.AiFeatureSchema.schemaHash(), means, stddevs);
    }

    /** Without-replacement subsample; full set when counts are equal. */
    private static int[] subsampleIndices(int n, int subsample, Random random) {
        int[] all = new int[n];
        for (int i = 0; i < n; i++) {
            all[i] = i;
        }
        for (int i = n - 1; i > 0; i--) { // Fisher-Yates partial
            int j = random.nextInt(i + 1);
            int tmp = all[i];
            all[i] = all[j];
            all[j] = tmp;
        }
        int[] out = new int[subsample];
        System.arraycopy(all, 0, out, 0, subsample);
        return out;
    }

    private static int buildNode(List<TrainedForest.Node> nodes, double[][] samples, int[] indices,
                                 int depth, int maxDepth, int nFeatures, Random random) {
        int nodeIndex = nodes.size();
        nodes.add(null); // reserve slot; filled after children are built
        if (depth >= maxDepth || indices.length <= 1) {
            nodes.set(nodeIndex, new TrainedForest.Node(-1, 0.0D, -1, -1, indices.length));
            return nodeIndex;
        }
        int feature = random.nextInt(nFeatures);
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (int idx : indices) {
            double value = samples[idx][feature];
            if (value < min) {
                min = value;
            }
            if (value > max) {
                max = value;
            }
        }
        if (!(min < max)) {
            nodes.set(nodeIndex, new TrainedForest.Node(-1, 0.0D, -1, -1, indices.length));
            return nodeIndex;
        }
        double split = min + random.nextDouble() * (max - min);
        int leftCount = 0;
        for (int idx : indices) {
            if (samples[idx][feature] <= split) {
                leftCount++;
            }
        }
        if (leftCount == 0 || leftCount == indices.length) {
            // degenerate split on a constant-valued boundary; isolate everything as a leaf
            nodes.set(nodeIndex, new TrainedForest.Node(-1, 0.0D, -1, -1, indices.length));
            return nodeIndex;
        }
        int[] left = new int[leftCount];
        int[] right = new int[indices.length - leftCount];
        int li = 0, ri = 0;
        for (int idx : indices) {
            if (samples[idx][feature] <= split) {
                left[li++] = idx;
            } else {
                right[ri++] = idx;
            }
        }
        int leftIndex = buildNode(nodes, samples, left, depth + 1, maxDepth, nFeatures, random);
        int rightIndex = buildNode(nodes, samples, right, depth + 1, maxDepth, nFeatures, random);
        nodes.set(nodeIndex, new TrainedForest.Node(feature, split, leftIndex, rightIndex, indices.length));
        return nodeIndex;
    }
}
