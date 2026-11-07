package ac.vigil.ai.assistant;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import ac.vigil.ai.evidence.AiAssessment;
import ac.vigil.ai.evidence.AiEvidenceCorrelator;
import ac.vigil.ai.report.PlayerProfile;
import ac.vigil.monitor.Evidence;
import ac.vigil.monitor.EvidenceType;

class AdminAssistantTest {

    private static PlayerProfile profile() {
        return PlayerProfile.builder()
                .targetName("Steve").snapshotAtMillis(1_000_000L)
                .cps1s(12.0D).cps5s(10.5D)
                .clickIntervalMean(80.0D).clickIntervalStddev(10.0D).clickIntervalCv(0.125D)
                .clickIntervalEntropyBits(3.0D)
                .rotationAngularVelocity1s(120.0D).rotationMaxDelta1s(15.0D)
                .reachEma(3.0D).reachSamples(90)
                .packetsPerSecond(18.0D)
                .breakIntervalMillisClipped(500L).impossibleBreakStreak(0)
                .pingMillis(33.0D)
                .deterministicEvidenceCount(1)
                .deterministicCheckNames(List.of("reach"))
                .totalViolationLevelChecks(1)
                .mlHistory(List.of(0.2D, 0.4D, 0.6D, 0.9D))
                .build();
    }

    private static AiAssessment assessment() {
        AiEvidenceCorrelator correlator = new AiEvidenceCorrelator(0.6D, 20);
        List<Evidence> evidence = List.of(
                new Evidence(999_000L, EvidenceType.CHECK_EVIDENCE, 0.9D, "server", "e", "reach"));
        return correlator.assess(evidence, 0.8D, "unit-test-model", 20.0D, 0L, 0L, 100, 1_000_000L);
    }

    private static AdminAssistant enabled() {
        return new AdminAssistant(true, false, new NoopLlmProvider(), true, true, "Steve");
    }

    @Test
    void disabledAssistantSaysSoAndSuggestsNothingActionable() {
        AdminAssistant assistant = new AdminAssistant(false, false, new NoopLlmProvider(), true, true, "Steve");
        assertTrue(assistant.summarizePlayer(profile(), assessment()).contains("disabled"));
        assertTrue(assistant.explainAssessment(assessment()).contains("disabled"));
        assertTrue(assistant.falsePositiveReading(assessment()).contains("disabled"));
        assertTrue(assistant.recommendedInvestigation(assessment()).contains("disabled"));
        assertTrue(assistant.ask("why?", "report text").contains("disabled"));
    }

    @Test
    void summaryContainsAdvisoryMarker() {
        assertTrue(enabled().summarizePlayer(profile(), assessment()).contains("ADVISORY"));
    }

    @Test
    void explanationCarriesTierReasonsAndDisclaimer() {
        String text = enabled().explainAssessment(assessment());
        assertTrue(text.contains("MEDIUM"), "tier must appear: " + text);
        assertTrue(text.contains("reach"), "reasons must appear: " + text);
        assertTrue(text.contains(AiAssessment.DISCLAIMER));
    }

    @Test
    void nullAssessmentIsHandledExplicitly() {
        AdminAssistant assistant = enabled();
        assertTrue(assistant.explainAssessment(null).contains("No assessment"));
        assertTrue(assistant.falsePositiveReading(null).contains("No assessment"));
        assertTrue(assistant.recommendedInvestigation(null).contains("No assessment"));
    }

    @Test
    void falsePositiveReadingNoSignals() {
        assertTrue(enabled().falsePositiveReading(assessment()).toLowerCase(java.util.Locale.ROOT).contains("no elevated"));
    }

    @Test
    void recommendedInvestigationListsActionsAndForbidsSoloPunishment() {
        String text = enabled().recommendedInvestigation(assessment());
        assertTrue(text.contains("Recommended investigation"));
        assertTrue(text.contains("deterministic evidence and server policy"));
    }

    @Test
    void askFallsBackToOfflineTemplatesWhenLlmDisabled() {
        String answer = enabled().ask("is Steve cheating?", "report text with Steve's data");
        assertTrue(answer.contains("LLM integration is disabled"), "offline fallback must be explicit: " + answer);
        assertFalse(answer.contains("null"), "fallback must never leak nulls");
    }

    @Test
    void noopProviderNeverReturnsText() {
        assertNull(new NoopLlmProvider().complete("anything", 1000L));
        assertEquals("none", new NoopLlmProvider().providerName());
    }
}
