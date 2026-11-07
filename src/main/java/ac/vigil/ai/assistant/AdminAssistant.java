package ac.vigil.ai.assistant;

import java.util.List;
import ac.vigil.ai.evidence.AiAssessment;
import ac.vigil.ai.evidence.AiRiskTier;
import ac.vigil.ai.report.AiReportBuilder;
import ac.vigil.ai.report.PlayerProfile;
import ac.vigil.ai.report.TelemetryRedactor;

/**
 * Admin-facing assistant. Explains, summarizes, flags possible false positives,
 * and recommends investigation steps. It NEVER bans, kicks, or recommends a
 * punishment — its language is deliberately advisory.
 *
 * <p>Two layers:</p>
 * <ol>
 *   <li><b>Offline templates</b> (always available when enabled): deterministic,
 *       no network, no model. Renders from the same data as the report.</li>
 *   <li><b>Optional LLM</b> (disabled by default): when
 *       {@code ai.admin-assistant.llm.enabled} is true and a provider is wired,
 *       the (redacted) report is passed to the provider. VigilAC ships no
 *       provider; the no-op provider is used otherwise.</li>
 * </ol>
 *
 * <p>All text that will leave the process is redacted first when
 * redactIdentifiers is set (the default for the LLM path). The assistant holds
 * no authority of any kind over the deterministic engine.</p>
 */
public final class AdminAssistant {

    private final boolean enabled;
    private final boolean llmEnabled;
    private final LlmProvider provider;
    private final boolean redactForLlm;
    private final TelemetryRedactor redactor;

    public AdminAssistant(boolean enabled, boolean llmEnabled, LlmProvider provider,
                          boolean redactForLlm, boolean redactReport, String playerNameForRedaction) {
        this.enabled = enabled;
        this.llmEnabled = llmEnabled;
        this.provider = provider == null ? new NoopLlmProvider() : provider;
        this.redactForLlm = redactForLlm;
        this.redactor = new TelemetryRedactor(redactReport, playerNameForRedaction);
    }

    /** One-paragraph executive summary suitable for a busy admin. */
    public String summarizePlayer(PlayerProfile profile, AiAssessment assessment) {
        StringBuilder sb = new StringBuilder();
        if (!enabled) {
            sb.append("Admin assistant is disabled (ai.admin-assistant.enabled: false). ");
            sb.append("The raw report is still available in chat.");
            return sb.toString();
        }
        sb.append(profile.targetName()).append(" — profile snapshot: CPS ")
                .append(String.format(java.util.Locale.US, "%.1f/%.1f", profile.cps1s(), profile.cps5s()))
                .append(", ping ").append(String.format(java.util.Locale.US, "%.0f ms", profile.pingMillis()))
                .append(", ").append(profile.deterministicEvidenceCount()).append(" deterministic signal(s)")
                .append(", ").append(profile.totalViolationLevelChecks()).append(" check(s) with VL > 0.");
        if (assessment != null) {
            sb.append(" AI assessment: ").append(assessment.tier()).append(" (false-positive risk: ")
                    .append(assessment.falsePositiveRisk()).append(").");
        }
        sb.append(" This is an ADVISORY summary — not a verdict.");
        return sb.toString();
    }

    /** Explains WHY the correlator reached its tier, in plain language. */
    public String explainAssessment(AiAssessment assessment) {
        if (!enabled) {
            return "Admin assistant is disabled (ai.admin-assistant.enabled: false).";
        }
        if (assessment == null) {
            return "No assessment available yet (AI disabled, or no scan has run for this player).";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Assessment ").append(assessment.tier()).append(':')
                .append("\n  ").append(TierText.gloss(assessment.tier())).append('\n')
                .append("Grounds for the tier:\n");
        for (String reason : assessment.reasons()) {
            sb.append("  - ").append(reason).append('\n');
        }
        if (assessment.mlScore() != null && assessment.mlScore() >= 0.6D) {
            sb.append("Note: the ML score alone cannot exceed LOW — only deterministic corroboration raised it.\n");
        }
        sb.append(AiAssessment.DISCLAIMER);
        return sb.toString();
    }

    /** Flags possible false positives based on the assessment's FP signals. */
    public String falsePositiveReading(AiAssessment assessment) {
        if (!enabled) {
            return "Admin assistant is disabled (ai.admin-assistant.enabled: false).";
        }
        if (assessment == null) {
            return "No assessment available yet.";
        }
        StringBuilder sb = new StringBuilder();
        switch (assessment.falsePositiveRisk()) {
            case NONE:
                sb.append("No elevated false-positive signals detected at snapshot time.");
                break;
            case ELEVATED:
                sb.append("ELEVATED false-positive risk:\n");
                for (String reason : assessment.reasons()) {
                    if (reason.startsWith("false-positive risk:") || reason.contains("downgraded")) {
                        sb.append("  - ").append(reason).append('\n');
                    }
                }
                sb.append("Treat observations from players with these signals as unreliable until conditions normalize.");
                break;
            default:
                sb.append("Unknown false-positive risk state.");
        }
        return sb.toString();
    }

    /** Concrete investigation steps for an administrator. Never punishments. */
    public String recommendedInvestigation(AiAssessment assessment) {
        if (!enabled) {
            return "Admin assistant is disabled (ai.admin-assistant.enabled: false).";
        }
        if (assessment == null) {
            return "No assessment available yet.";
        }
        StringBuilder sb = new StringBuilder("Recommended investigation (human decision required):\n");
        for (String action : assessment.suggestedActions()) {
            sb.append("  - ").append(action).append('\n');
        }
        sb.append("Never act on this list alone; deterministic evidence and server policy govern.");
        return sb.toString();
    }

    /**
     * Asks the configured LLM (if any) a free-form question about a redacted
     * report. Falls back to an offline explanation when the LLM is disabled or
     * unavailable. The LLM path is OPTIONAL and OFF by default — enabling it
     * sends player telemetry to an external API and must be a conscious,
     * documented owner decision.
     */
    public String ask(String question, String reportText) {
        if (!enabled) {
            return "Admin assistant is disabled (ai.admin-assistant.enabled: false).";
        }
        String safeReport = redactForLlm ? redactor.redact(reportText) : reportText;
        if (llmEnabled) {
            String answer = provider.complete(
                    "You are an advisory anti-cheat assistant. Explain, summarize and flag false positives. "
                            + "Never recommend bans. Answer briefly.\n\nReport:\n" + safeReport + "\n\nQuestion: " + question,
                    10_000L);
            if (answer != null) {
                return answer + "\n(Note: generated by " + provider.providerName() + " with redacted telemetry; verify everything.)";
            }
        }
        // Offline fallback: deterministic, no network.
        return "LLM integration is disabled or unavailable (ai.admin-assistant.llm.enabled: false). "
                + "Offline answer to \"" + abbreviate(question) + "\": read the report sections top to bottom — "
                + "profile → deterministic evidence → ML shadow signal → assessment → limitations. "
                + "If the assessment is MEDIUM or higher AND false-positive risk is NONE, gather one more independent signal before any action. "
                + "If false-positive risk is ELEVATED, wait for conditions to normalize and re-check.";
    }

    public String providerLabel() {
        return provider.providerName();
    }

    private static String abbreviate(String value) {
        return value == null ? "?" : (value.length() <= 80 ? value : value.substring(0, 80) + "…");
    }

    private static final class TierText {
        static String gloss(AiRiskTier tier) {
            switch (tier) {
                case NONE: return "nothing unusual observed in this window";
                case LOW: return "minor signals — log only, keep observing";
                case MEDIUM: return "meaningful corroborated signals — human review warranted";
                case HIGH: return "strong corroborated signals — priority human review, never punishment from AI alone";
                default: return "unknown";
            }
        }
        private TierText() {
        }
    }
}
