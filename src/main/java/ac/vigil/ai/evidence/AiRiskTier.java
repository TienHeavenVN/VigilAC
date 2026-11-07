package ac.vigil.ai.evidence;

/**
 * AI advisory risk tiers. These are <b>not</b> violation levels and carry no
 * punishment power anywhere in VigilAC. Ordering: NONE &lt; LOW &lt; MEDIUM &lt; HIGH.
 */
public enum AiRiskTier {
    NONE,
    LOW,
    MEDIUM,
    HIGH;

    public AiRiskTier atMost(AiRiskTier cap) {
        return ordinal() <= cap.ordinal() ? this : cap;
    }

    public AiRiskTier downgradeOne() {
        switch (this) {
            case HIGH: return MEDIUM;
            case MEDIUM: return LOW;
            case LOW: return NONE;
            default: return NONE;
        }
    }
}
