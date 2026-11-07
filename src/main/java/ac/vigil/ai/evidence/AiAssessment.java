package ac.vigil.ai.evidence;

import java.util.List;

/**
 * Output of the AI evidence correlator. Immutable, fully explainable:
 * every recommendation carries the concrete inputs it was derived from.
 *
 * <p><b>Hard rule encoded here and in AiEvidenceCorrelator:</b> a high ML
 * anomaly score with no deterministic check corroboration can never produce
 * more than {@link AiRiskTier#LOW}. This class holds no reference to any
 * violation pipeline; converting an assessment into a punishment anywhere in
 * VigilAC would require new code, not configuration.</p>
 */
public final class AiAssessment {

    public static final String DISCLAIMER =
            "AI-assisted ADVISORY only — not a violation, not a ban, not evidence for punishment without human review.";

    private final long createdAtMillis;
    private final AiRiskTier tier;
    private final Double mlScore;          // null when no ML score was available
    private final String mlModelId;        // null when unavailable
    private final FalsePositiveRisk falsePositiveRisk;
    private final List<String> reasons;    // concrete, traceable facts; never empty
    private final List<String> suggestedActions;

    public AiAssessment(long createdAtMillis, AiRiskTier tier, Double mlScore, String mlModelId,
                        FalsePositiveRisk falsePositiveRisk, List<String> reasons, List<String> suggestedActions) {
        this.createdAtMillis = createdAtMillis;
        this.tier = tier;
        this.mlScore = mlScore;
        this.mlModelId = mlModelId;
        this.falsePositiveRisk = falsePositiveRisk;
        this.reasons = List.copyOf(reasons);
        this.suggestedActions = List.copyOf(suggestedActions);
    }

    public long createdAtMillis() { return createdAtMillis; }
    public AiRiskTier tier() { return tier; }
    /** ML anomaly score in [0,1], or null when no model scored this player. */
    public Double mlScore() { return mlScore; }
    public String mlModelId() { return mlModelId; }
    public FalsePositiveRisk falsePositiveRisk() { return falsePositiveRisk; }
    /** Concrete, traceable facts behind the tier — always non-empty. */
    public List<String> reasons() { return reasons; }
    /** Human next steps for an administrator — investigation hints, never punishments. */
    public List<String> suggestedActions() { return suggestedActions; }

    public boolean mlCorroborated() {
        return mlScore != null;
    }
}
