package ac.vigil.monitor;

/**
 * Immutable, per-check-per-player detection status view for debugging and
 * monitoring UIs (e.g. {@code /vigilac monitor <player>}).
 *
 * <p>VL itself is read live from the VL store by the renderer; this record
 * carries the monitor's own signals: evidence count, last detection time,
 * confidence and the latest exemption reason.</p>
 */
public final class DetectionStatus {

    private final String check;
    private final DetectionState state;
    private final double violationLevel;
    private final double confidence;
    private final long lastDetectionAt;
    private final long evidenceCount;
    private final String exemptionReason;
    private final long exemptionAt;

    public DetectionStatus(String check, DetectionState state, double violationLevel, double confidence,
                           long lastDetectionAt, long evidenceCount, String exemptionReason, long exemptionAt) {
        this.check = check;
        this.state = state;
        this.violationLevel = violationLevel;
        this.confidence = confidence;
        this.lastDetectionAt = lastDetectionAt;
        this.evidenceCount = evidenceCount;
        this.exemptionReason = exemptionReason == null ? "" : exemptionReason;
        this.exemptionAt = exemptionAt;
    }

    public String check() { return check; }
    public DetectionState state() { return state; }
    public double violationLevel() { return violationLevel; }
    public double confidence() { return confidence; }
    public long lastDetectionAt() { return lastDetectionAt; }
    public long evidenceCount() { return evidenceCount; }
    public String exemptionReason() { return exemptionReason; }
    public long exemptionAt() { return exemptionAt; }
}
