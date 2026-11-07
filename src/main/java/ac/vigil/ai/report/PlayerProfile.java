package ac.vigil.ai.report;

import java.util.List;

/**
 * Immutable behavioral profile snapshot for a player, built on demand for
 * the admin assistant. Aggregated from existing trackers — no new telemetry
 * is collected, and no field identifies the player beyond what the admin
 * already sees in-game.
 */
public final class PlayerProfile {

    /** Trend of shadow ML anomaly scores over the retained history window. */
    public enum Trend { INSUFFICIENT_DATA, RISING, FALLING, FLAT }

    private final String targetName;
    private final long snapshotAtMillis;
    private final double cps1s;
    private final double cps5s;
    private final double clickIntervalMean;
    private final double clickIntervalStddev;
    private final double clickIntervalCv;
    private final double clickIntervalEntropyBits;
    private final double rotationAngularVelocity1s;
    private final double rotationMaxDelta1s;
    private final double reachEma;
    private final int reachSamples;
    private final double packetsPerSecond;
    private final long breakIntervalMillisClipped;
    private final int impossibleBreakStreak;
    private final double pingMillis;
    private final int deterministicEvidenceCount;
    private final List<String> deterministicCheckNames;
    private final int totalViolationLevelChecks; // checks with VL > 0
    private final List<Double> mlHistory;          // oldest → newest
    private final Trend mlTrend;

    private PlayerProfile(String targetName, long snapshotAtMillis,
                          double cps1s, double cps5s,
                          double clickIntervalMean, double clickIntervalStddev, double clickIntervalCv,
                          double clickIntervalEntropyBits,
                          double rotationAngularVelocity1s, double rotationMaxDelta1s,
                          double reachEma, int reachSamples, double packetsPerSecond,
                          long breakIntervalMillisClipped, int impossibleBreakStreak,
                          double pingMillis, int deterministicEvidenceCount,
                          List<String> deterministicCheckNames, int totalViolationLevelChecks,
                          List<Double> mlHistory, Trend mlTrend) {
        this.targetName = targetName;
        this.snapshotAtMillis = snapshotAtMillis;
        this.cps1s = cps1s;
        this.cps5s = cps5s;
        this.clickIntervalMean = clickIntervalMean;
        this.clickIntervalStddev = clickIntervalStddev;
        this.clickIntervalCv = clickIntervalCv;
        this.clickIntervalEntropyBits = clickIntervalEntropyBits;
        this.rotationAngularVelocity1s = rotationAngularVelocity1s;
        this.rotationMaxDelta1s = rotationMaxDelta1s;
        this.reachEma = reachEma;
        this.reachSamples = reachSamples;
        this.packetsPerSecond = packetsPerSecond;
        this.breakIntervalMillisClipped = breakIntervalMillisClipped;
        this.impossibleBreakStreak = impossibleBreakStreak;
        this.pingMillis = pingMillis;
        this.deterministicEvidenceCount = deterministicEvidenceCount;
        this.deterministicCheckNames = List.copyOf(deterministicCheckNames);
        this.totalViolationLevelChecks = totalViolationLevelChecks;
        this.mlHistory = List.copyOf(mlHistory);
        this.mlTrend = mlTrend;
    }

    public static Builder builder() { return new Builder(); }

    public String targetName() { return targetName; }
    public long snapshotAtMillis() { return snapshotAtMillis; }
    public double cps1s() { return cps1s; }
    public double cps5s() { return cps5s; }
    public double clickIntervalMean() { return clickIntervalMean; }
    public double clickIntervalStddev() { return clickIntervalStddev; }
    public double clickIntervalCv() { return clickIntervalCv; }
    public double clickIntervalEntropyBits() { return clickIntervalEntropyBits; }
    public double rotationAngularVelocity1s() { return rotationAngularVelocity1s; }
    public double rotationMaxDelta1s() { return rotationMaxDelta1s; }
    public double reachEma() { return reachEma; }
    public int reachSamples() { return reachSamples; }
    public double packetsPerSecond() { return packetsPerSecond; }
    public long breakIntervalMillisClipped() { return breakIntervalMillisClipped; }
    public int impossibleBreakStreak() { return impossibleBreakStreak; }
    public double pingMillis() { return pingMillis; }
    public int deterministicEvidenceCount() { return deterministicEvidenceCount; }
    public List<String> deterministicCheckNames() { return deterministicCheckNames; }
    public int totalViolationLevelChecks() { return totalViolationLevelChecks; }
    public List<Double> mlHistory() { return mlHistory; }
    public Trend mlTrend() { return mlTrend; }

    /** Naive linear trend over the retained ML history window (oldest→newest). */
    public static Trend trendOf(List<Double> history) {
        if (history == null || history.size() < 4) {
            return Trend.INSUFFICIENT_DATA;
        }
        double half = history.size() / 2.0D;
        double firstMean = 0.0D, secondMean = 0.0D;
        int first = (int) half, second = history.size() - first;
        for (int i = 0; i < first; i++) {
            firstMean += history.get(i);
        }
        for (int i = first; i < history.size(); i++) {
            secondMean += history.get(i);
        }
        firstMean /= first;
        secondMean /= second;
        double delta = secondMean - firstMean;
        if (delta > 0.05D) {
            return Trend.RISING;
        }
        if (delta < -0.05D) {
            return Trend.FALLING;
        }
        return Trend.FLAT;
    }

    public static final class Builder {
        private String targetName = "?";
        private long snapshotAtMillis;
        private double cps1s, cps5s, clickIntervalMean, clickIntervalStddev, clickIntervalCv, clickIntervalEntropyBits;
        private double rotationAngularVelocity1s, rotationMaxDelta1s, reachEma;
        private int reachSamples;
        private double packetsPerSecond, pingMillis;
        private long breakIntervalMillisClipped;
        private int impossibleBreakStreak, deterministicEvidenceCount, totalViolationLevelChecks;
        private List<String> deterministicCheckNames = List.of();
        private List<Double> mlHistory = List.of();

        public Builder targetName(String targetName) { this.targetName = targetName; return this; }
        public Builder snapshotAtMillis(long snapshotAtMillis) { this.snapshotAtMillis = snapshotAtMillis; return this; }
        public Builder cps1s(double v) { this.cps1s = v; return this; }
        public Builder cps5s(double v) { this.cps5s = v; return this; }
        public Builder clickIntervalMean(double v) { this.clickIntervalMean = v; return this; }
        public Builder clickIntervalStddev(double v) { this.clickIntervalStddev = v; return this; }
        public Builder clickIntervalCv(double v) { this.clickIntervalCv = v; return this; }
        public Builder clickIntervalEntropyBits(double v) { this.clickIntervalEntropyBits = v; return this; }
        public Builder rotationAngularVelocity1s(double v) { this.rotationAngularVelocity1s = v; return this; }
        public Builder rotationMaxDelta1s(double v) { this.rotationMaxDelta1s = v; return this; }
        public Builder reachEma(double v) { this.reachEma = v; return this; }
        public Builder reachSamples(int v) { this.reachSamples = v; return this; }
        public Builder packetsPerSecond(double v) { this.packetsPerSecond = v; return this; }
        public Builder breakIntervalMillisClipped(long v) { this.breakIntervalMillisClipped = v; return this; }
        public Builder impossibleBreakStreak(int v) { this.impossibleBreakStreak = v; return this; }
        public Builder pingMillis(double v) { this.pingMillis = v; return this; }
        public Builder deterministicEvidenceCount(int v) { this.deterministicEvidenceCount = v; return this; }
        public Builder deterministicCheckNames(List<String> v) { this.deterministicCheckNames = v; return this; }
        public Builder totalViolationLevelChecks(int v) { this.totalViolationLevelChecks = v; return this; }
        public Builder mlHistory(List<Double> v) { this.mlHistory = v; return this; }

        public PlayerProfile build() {
            Trend trend = trendOf(mlHistory);
            return new PlayerProfile(targetName, snapshotAtMillis,
                    cps1s, cps5s,
                    clickIntervalMean, clickIntervalStddev, clickIntervalCv, clickIntervalEntropyBits,
                    rotationAngularVelocity1s, rotationMaxDelta1s,
                    reachEma, reachSamples, packetsPerSecond,
                    breakIntervalMillisClipped, impossibleBreakStreak,
                    pingMillis, deterministicEvidenceCount, deterministicCheckNames, totalViolationLevelChecks,
                    mlHistory, trend);
        }
    }
}
