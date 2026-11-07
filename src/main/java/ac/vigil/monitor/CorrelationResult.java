package ac.vigil.monitor;

import java.util.List;

/**
 * Outcome of correlating evidence streams for one player at one instant.
 * Supporting and suppressing lists carry short human-readable reasons for
 * debug output; confidence is in [0, 1] and is informational only — gating
 * remains with the check that consumes the result.
 */
public final class CorrelationResult {

    private final double confidence;
    private final List<String> supporting;
    private final List<String> suppressing;
    private final CorrelationStatus status;

    public CorrelationResult(double confidence, List<String> supporting, List<String> suppressing) {
        this.confidence = Math.max(0.0D, Math.min(1.0D, confidence));
        this.supporting = List.copyOf(supporting);
        this.suppressing = List.copyOf(suppressing);
        this.status = classify(this.supporting, this.suppressing);
    }

    /**
     * Tri-state verdict from the same evidence lists: suppression always wins
     * (INCONCLUSIVE); otherwise &ge; 2 independent supporting signals are
     * CONFIRMED, exactly 1 is SUSPECTED, 0 is INCONCLUSIVE.
     */
    public static CorrelationStatus classify(List<String> supporting, List<String> suppressing) {
        if (suppressing != null && !suppressing.isEmpty()) {
            return CorrelationStatus.INCONCLUSIVE;
        }
        int support = supporting == null ? 0 : supporting.size();
        if (support >= 2) {
            return CorrelationStatus.CONFIRMED;
        }
        if (support == 1) {
            return CorrelationStatus.SUSPECTED;
        }
        return CorrelationStatus.INCONCLUSIVE;
    }

    public double confidence() { return confidence; }
    public List<String> supporting() { return supporting; }
    public List<String> suppressing() { return suppressing; }
    /** CONFIRMED / SUSPECTED / INCONCLUSIVE — never a probability. */
    public CorrelationStatus status() { return status; }

    @Override
    public String toString() {
        return String.format(java.util.Locale.US, "confidence=%.2f status=%s supporting=%s suppressing=%s",
                confidence, status, supporting, suppressing);
    }
}
