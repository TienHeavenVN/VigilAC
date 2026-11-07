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

    public CorrelationResult(double confidence, List<String> supporting, List<String> suppressing) {
        this.confidence = Math.max(0.0D, Math.min(1.0D, confidence));
        this.supporting = List.copyOf(supporting);
        this.suppressing = List.copyOf(suppressing);
    }

    public double confidence() { return confidence; }
    public List<String> supporting() { return supporting; }
    public List<String> suppressing() { return suppressing; }

    @Override
    public String toString() {
        return String.format(java.util.Locale.US, "confidence=%.2f supporting=%s suppressing=%s",
                confidence, supporting, suppressing);
    }
}
