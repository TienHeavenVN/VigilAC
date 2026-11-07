package ac.vigil.ai.ml.isolationforest;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A fully-trained isolation forest. This is the training artifact: it carries
 * the trees and (optionally) normalization statistics, and serializes to the
 * documented {@code VIGILIF1} text format. It is also what the runtime model
 * wraps — there is exactly one scoring implementation for both paths.
 *
 * <p>Format (UTF-8, strict, line-based):</p>
 * <pre>
 * VIGILIF1
 * nFeatures=&lt;int&gt;
 * subsample=&lt;int&gt;
 * nTrees=&lt;int&gt;
 * featureSchemaHash=&lt;64hex&gt;
 * normalization=zscore|none
 * means=&lt;n doubles csv&gt;   (zscore only)
 * stddevs=&lt;n doubles csv&gt; (zscore only)
 * tree nodes=&lt;m&gt;
 * node feature=&lt;i|-1&gt; threshold=&lt;d&gt; left=&lt;i&gt; right=&lt;i&gt; size=&lt;i&gt;
 * ... (m lines)
 * endtree
 * </pre>
 * feature=-1 marks a leaf; {@code size} is the subsample count at that node.
 */
public final class TrainedForest {

    public static final String MAGIC = "VIGILIF1";
    static final double EULER_GAMMA = 0.5772156649015329D;

    private final int nFeatures;
    private final int subsample;
    private final Node[][] trees;
    private final String featureSchemaHash;
    private final boolean zscore;
    private final double[] means;
    private final double[] stddevs;

    /** One flat node; feature == -1 marks a leaf. Package-private for the model wrapper. */
    static final class Node {
        final int feature;
        final double threshold;
        final int left;
        final int right;
        final int size;

        Node(int feature, double threshold, int left, int right, int size) {
            this.feature = feature;
            this.threshold = threshold;
            this.left = left;
            this.right = right;
            this.size = size;
        }
    }

    private TrainedForest(int nFeatures, int subsample, Node[][] trees, String featureSchemaHash,
                          boolean zscore, double[] means, double[] stddevs) {
        this.nFeatures = nFeatures;
        this.subsample = subsample;
        this.trees = trees;
        this.featureSchemaHash = featureSchemaHash;
        this.zscore = zscore;
        this.means = means;
        this.stddevs = stddevs;
    }

    public static TrainedForest of(int nFeatures, int subsample, List<Node[]> trees, String featureSchemaHash,
                                   double[] means, double[] stddevs) {
        return new TrainedForest(nFeatures, subsample, trees.toArray(new Node[0][]), featureSchemaHash,
                means != null, means, stddevs);
    }

    public int nFeatures() { return nFeatures; }
    public int subsampleSize() { return subsample; }
    public int treeCount() { return trees.length; }
    public String featureSchemaHash() { return featureSchemaHash; }
    public boolean isZscore() { return zscore; }
    public double[] means() { return means == null ? null : means.clone(); }
    public double[] stddevs() { return stddevs == null ? null : stddevs.clone(); }

    /** Anomaly score in [0, 1]; higher = more anomalous. Applies stored normalization. */
    public double anomalyScore(double[] rawValues) {
        if (rawValues == null || rawValues.length != nFeatures) {
            throw new IllegalArgumentException("expected " + nFeatures + " features, got " + (rawValues == null ? -1 : rawValues.length));
        }
        double[] x = rawValues;
        if (zscore) {
            x = new double[rawValues.length];
            for (int i = 0; i < x.length; i++) {
                double sd = stddevs[i];
                x[i] = sd == 0.0D ? 0.0D : (rawValues[i] - means[i]) / sd;
            }
        }
        double pathSum = 0.0D;
        for (Node[] tree : trees) {
            pathSum += pathLength(tree, x);
        }
        double averagePathLength = pathSum / trees.length;
        return Math.pow(2.0D, -averagePathLength / c(subsample));
    }

    private static double pathLength(Node[] tree, double[] x) {
        int index = 0;
        int depth = 0;
        while (true) {
            Node node = tree[index];
            if (node.feature < 0) {
                return depth + c(node.size);
            }
            depth++;
            index = x[node.feature] <= node.threshold ? node.left : node.right;
        }
    }

    /** c(n): average path length of an unsuccessful search in a BST of n points (Liu et al., 2008). */
    static double c(int n) {
        if (n <= 1) {
            return 1.0D;
        }
        return 2.0D * (Math.log(n - 1.0D) + EULER_GAMMA) - 2.0D * (n - 1.0D) / n;
    }

    /** Serializes to the VIGILIF1 format (exact doubles via Double.toString round-trip). */
    public byte[] write() {
        StringBuilder sb = new StringBuilder(4096);
        sb.append(MAGIC).append('\n');
        sb.append("nFeatures=").append(nFeatures).append('\n');
        sb.append("subsample=").append(subsample).append('\n');
        sb.append("nTrees=").append(trees.length).append('\n');
        sb.append("featureSchemaHash=").append(featureSchemaHash == null ? "" : featureSchemaHash).append('\n');
        sb.append("normalization=").append(zscore ? "zscore" : "none").append('\n');
        if (zscore) {
            sb.append("means=").append(csv(means)).append('\n');
            sb.append("stddevs=").append(csv(stddevs)).append('\n');
        }
        for (Node[] tree : trees) {
            sb.append("tree nodes=").append(tree.length).append('\n');
            for (Node node : tree) {
                sb.append("node feature=").append(node.feature)
                  .append(" threshold=").append(node.threshold)
                  .append(" left=").append(node.left)
                  .append(" right=").append(node.right)
                  .append(" size=").append(node.size).append('\n');
            }
            sb.append("endtree\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Strict parser; any deviation from the format throws. */
    public static TrainedForest read(byte[] bytes) throws java.io.IOException {
        if (bytes == null || bytes.length == 0) {
            throw new java.io.IOException("empty forest payload");
        }
        if (bytes.length > 64 * 1024 * 1024) {
            throw new java.io.IOException("forest payload exceeds 64 MiB");
        }
        String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\\r?\\n");
        int i = 0;
        if (lines.length == 0 || !MAGIC.equals(lines[0].trim())) {
            throw new java.io.IOException("missing magic '" + MAGIC + "'");
        }
        i++;
        int nFeatures = -1, subsample = -1, nTrees = -1;
        String schemaHash = "";
        String normalization = null;
        double[] means = null, stddevs = null;
        // header keys until first tree
        while (i < lines.length && !lines[i].startsWith("tree ")) {
            String line = lines[i].trim();
            if (!line.isEmpty()) {
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    throw new java.io.IOException("malformed header line " + (i + 1) + ": '" + abbreviate(line) + "'");
                }
                String key = line.substring(0, eq).trim().toLowerCase(java.util.Locale.ROOT);
                String value = line.substring(eq + 1).trim();
                switch (key) {
                    case "nfeatures": nFeatures = parseInt(value, "nFeatures"); break;
                    case "subsample": subsample = parseInt(value, "subsample"); break;
                    case "ntrees": nTrees = parseInt(value, "nTrees"); break;
                    case "featureschemahash": schemaHash = value; break;
                    case "normalization": normalization = value; break;
                    case "means": means = parseCsv(value, nFeatures, "means"); break;
                    case "stddevs": stddevs = parseCsv(value, nFeatures, "stddevs"); break;
                    default: throw new java.io.IOException("unknown header key '" + key + "'");
                }
            }
            i++;
        }
        if (nFeatures <= 0 || subsample <= 0 || nTrees <= 0 || normalization == null) {
            throw new java.io.IOException("forest header incomplete (nFeatures/subsample/nTrees/normalization)");
        }
        boolean zscore = "zscore".equalsIgnoreCase(normalization);
        if (zscore && (means == null || stddevs == null)) {
            throw new java.io.IOException("zscore normalization requires means= and stddevs=");
        }
        List<Node[]> trees = new ArrayList<>(nTrees);
        while (i < lines.length) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                i++;
                continue;
            }
            if (!line.startsWith("tree nodes=")) {
                throw new java.io.IOException("expected 'tree nodes=' at line " + (i + 1));
            }
            int expected = parseInt(line.substring("tree nodes=".length()).trim(), "tree nodes");
            List<Node> nodes = new ArrayList<>(expected);
            i++;
            while (i < lines.length && !"endtree".equals(lines[i].trim())) {
                nodes.add(parseNode(lines[i], i + 1));
                i++;
            }
            if (i >= lines.length) {
                throw new java.io.IOException("tree truncated before endtree");
            }
            if (nodes.size() != expected) {
                throw new java.io.IOException("tree declared " + expected + " nodes but carried " + nodes.size());
            }
            validateTree(nodes, nFeatures);
            trees.add(nodes.toArray(new Node[0]));
            i++; // skip endtree
        }
        if (trees.size() != nTrees) {
            throw new java.io.IOException("header promised nTrees=" + nTrees + " but " + trees.size() + " trees were read");
        }
        return new TrainedForest(nFeatures, subsample, trees.toArray(new Node[0][]), schemaHash, zscore, means, stddevs);
    }

    private static Node parseNode(String rawLine, int lineNo) throws java.io.IOException {
        String line = rawLine.trim();
        if (!line.startsWith("node ")) {
            throw new java.io.IOException("expected 'node ' at line " + lineNo + ": '" + abbreviate(line) + "'");
        }
        int feature = -2, left = -2, right = -2, size = -1;
        double threshold = Double.NaN;
        for (String part : line.substring(5).trim().split("\\s+")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                throw new java.io.IOException("malformed node attribute at line " + lineNo + ": '" + abbreviate(part) + "'");
            }
            String key = part.substring(0, eq).toLowerCase(java.util.Locale.ROOT);
            String value = part.substring(eq + 1);
            switch (key) {
                case "feature": feature = parseInt(value, "node.feature"); break;
                case "threshold": threshold = parseDouble(value, "node.threshold"); break;
                case "left": left = parseInt(value, "node.left"); break;
                case "right": right = parseInt(value, "node.right"); break;
                case "size": size = parseInt(value, "node.size"); break;
                default: throw new java.io.IOException("unknown node attribute '" + key + "' at line " + lineNo);
            }
        }
        if (feature == -2 || left == -2 || right == -2 || size < 0 || Double.isNaN(threshold) || Double.isInfinite(threshold)) {
            throw new java.io.IOException("node at line " + lineNo + " is missing/invalid attributes");
        }
        return new Node(feature, threshold, left, right, size);
    }

    private static void validateTree(List<Node> nodes, int nFeatures) throws java.io.IOException {
        if (nodes.isEmpty()) {
            throw new java.io.IOException("tree with zero nodes");
        }
        for (int i = 0; i < nodes.size(); i++) {
            Node node = nodes.get(i);
            if (node.feature == -1) {
                continue; // leaf
            }
            if (node.feature < 0 || node.feature >= nFeatures) {
                throw new java.io.IOException("node " + i + " feature index out of range: " + node.feature);
            }
            if (node.left < 0 || node.right < 0 || node.left >= nodes.size() || node.right >= nodes.size()) {
                throw new java.io.IOException("node " + i + " child index out of range");
            }
        }
    }

    private static String csv(double[] values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(values[i]);
        }
        return sb.toString();
    }

    private static double[] parseCsv(String value, int expected, String key) throws java.io.IOException {
        String[] parts = value.split(",");
        if (expected > 0 && parts.length != expected) {
            throw new java.io.IOException("key '" + key + "' needs " + expected + " entries, got " + parts.length);
        }
        double[] out = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = parseDouble(parts[i], key);
        }
        return out;
    }

    private static int parseInt(String value, String key) throws java.io.IOException {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new java.io.IOException("key '" + key + "' is not an int: '" + abbreviate(value) + "'");
        }
    }

    private static double parseDouble(String value, String key) throws java.io.IOException {
        try {
            double d = Double.parseDouble(value.trim());
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new java.io.IOException("key '" + key + "' is not finite");
            }
            return d;
        } catch (NumberFormatException e) {
            throw new java.io.IOException("key '" + key + "' is not a double: '" + abbreviate(value) + "'");
        }
    }

    private static String abbreviate(String value) {
        return value.length() <= 48 ? value : value.substring(0, 48) + "…";
    }

    /** Exposed for the runtime wrapper; never mutate. */
    Node[][] treesView() {
        return trees;
    }
}
