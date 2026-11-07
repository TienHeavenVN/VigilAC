package ac.vigil.ai.report;

import java.util.List;
import java.util.Locale;
import ac.vigil.ai.evidence.AiAssessment;
import ac.vigil.ai.evidence.AiRiskTier;

/**
 * Renders the admin-facing AI report. Pure string assembly — no I/O, no
 * network, no side effects. Every report embeds its own limitations and the
 * advisory disclaimer; a report rendered without them would be a bug.
 */
public final class AiReportBuilder {

    private AiReportBuilder() {
    }

    /**
     * @param assessment correlator output (nullable — "no scan yet / AI disabled" renders an explicit note)
     * @param modelStatuses per-model status lines (may be empty — "no models loaded")
     * @param mlThreshold configured shadow threshold (display only)
     * @param counters rendered counter lines (may be empty)
     */
    public static String render(PlayerProfile profile, AiAssessment assessment,
                                List<String> modelStatuses, double mlThreshold,
                                List<String> counterLines) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("=== AI-Assist (advisory; offline templates; no telemetry leaves this server) ===\n");
        sb.append("Target: ").append(profile.targetName()).append('\n');
        sb.append("-- Behavioral profile --\n");
        sb.append(String.format(Locale.US, "CPS (1s/5s): %.2f / %.2f | click mean %.0f ms, stddev %.0f ms, CV %.2f, entropy %.2f bits%n",
                profile.cps1s(), profile.cps5s(), profile.clickIntervalMean(), profile.clickIntervalStddev(),
                profile.clickIntervalCv(), profile.clickIntervalEntropyBits()));
        sb.append(String.format(Locale.US, "Rotation: angular velocity %.1f deg/s (1s) | max delta %.1f deg (1s)%n",
                profile.rotationAngularVelocity1s(), profile.rotationMaxDelta1s()));
        sb.append(String.format(Locale.US, "Reach: EMA %.3f blocks (%d samples) | packets/s %.1f | ping %.0f ms%n",
                profile.reachEma(), profile.reachSamples(), profile.packetsPerSecond(), profile.pingMillis()));
        sb.append(String.format(Locale.US, "Mining: break interval %d ms | impossible-break streak %d%n",
                profile.breakIntervalMillisClipped(), profile.impossibleBreakStreak()));
        sb.append("-- Deterministic evidence (window) --\n");
        sb.append("signals: ").append(profile.deterministicEvidenceCount());
        if (!profile.deterministicCheckNames().isEmpty()) {
            sb.append(" across checks [").append(String.join(", ", profile.deterministicCheckNames())).append(']');
        }
        sb.append('\n');
        sb.append("checks with VL > 0: ").append(profile.totalViolationLevelChecks()).append('\n');
        sb.append("-- ML (shadow mode; never convicts alone) --\n");
        if (modelStatuses == null || modelStatuses.isEmpty()) {
            sb.append("no models loaded (models-directory is empty or every model is unavailable)\n");
        } else {
            for (String status : modelStatuses) {
                sb.append("  ").append(status).append('\n');
            }
        }
        sb.append("ML history (oldest→newest): ").append(formatHistory(profile.mlHistory()))
          .append(" | trend ").append(profile.mlTrend()).append('\n');
        sb.append("-- Assessment --\n");
        if (assessment == null) {
            sb.append("no assessment available (AI disabled, or no scan has run yet)\n");
        } else {
            sb.append(assessment.tier()).append(" — ").append(TierGloss.explain(assessment.tier())).append('\n');
            if (assessment.mlScore() != null) {
                sb.append(String.format(Locale.US, "ML anomaly score: %.3f (threshold %.2f, model %s)%n",
                        assessment.mlScore(), mlThreshold, assessment.mlModelId() == null ? "unknown" : assessment.mlModelId()));
            } else {
                sb.append("ML anomaly score: unavailable\n");
            }
            sb.append("False-positive risk: ").append(assessment.falsePositiveRisk()).append('\n');
            sb.append("Why:\n");
            for (String reason : assessment.reasons()) {
                sb.append("  - ").append(reason).append('\n');
            }
            sb.append("Suggested:\n");
            for (String action : assessment.suggestedActions()) {
                sb.append("  - ").append(action).append('\n');
            }
            sb.append(AiAssessment.DISCLAIMER).append('\n');
        }
        if (counterLines != null && !counterLines.isEmpty()) {
            sb.append("-- Module counters --\n");
            for (String line : counterLines) {
                sb.append("  ").append(line).append('\n');
            }
        }
        sb.append("-- Limitations --\n");
        sb.append("- Shadow mode only: the AI module never writes violation levels, alerts, kicks or bans.\n");
        sb.append("- No trained model ships with VigilAC; any loaded model was trained elsewhere and its quality is UNVERIFIED here.\n");
        sb.append("- REAL_CLIENT_VALIDATION is BLOCKED; this report is not evidence of cheating and is not evidence of innocence.\n");
        sb.append("- Deterministic checks remain authoritative; treat this report as one signal to start an investigation.\n");
        return sb.toString();
    }

    private static String formatHistory(List<Double> history) {
        if (history == null || history.isEmpty()) {
            return "(none)";
        }
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(history.size(), 8);
        int from = history.size() - shown;
        for (int i = from; i < history.size(); i++) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(String.format(Locale.US, "%.2f", history.get(i)));
        }
        return sb.toString();
    }

    /** Tier glosses, kept separate so they cannot drift from the enum. */
    private static final class TierGloss {
        static String explain(AiRiskTier tier) {
            switch (tier) {
                case NONE: return "nothing unusual observed";
                case LOW: return "minor signals; log-only, keep observing";
                case MEDIUM: return "meaningful corroborated signals; human review warranted";
                case HIGH: return "strong corroborated signals; priority human review — never punish from this alone";
                default: return "unknown";
            }
        }
        private TierGloss() {
        }
    }
}
