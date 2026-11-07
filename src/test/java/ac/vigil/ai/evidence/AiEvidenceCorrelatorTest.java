package ac.vigil.ai.evidence;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import ac.vigil.monitor.Evidence;
import ac.vigil.monitor.EvidenceType;

class AiEvidenceCorrelatorTest {

    private static final long NOW = 1_000_000L;
    private final AiEvidenceCorrelator correlator = new AiEvidenceCorrelator(AiEvidenceCorrelator.DEFAULT_ML_THRESHOLD, 20);

    private static Evidence checkEvidence(String check) {
        return new Evidence(NOW - 5_000L, EvidenceType.CHECK_EVIDENCE, 0.9D, "server", "test detail", check);
    }

    private AiAssessment assess(List<Evidence> evidence, Double ml, double ping,
                                long lastVelocityExemptAt, long lastTeleportExemptAt, int samples) {
        return correlator.assess(evidence, ml, ml == null ? null : "test-model", ping,
                lastVelocityExemptAt, lastTeleportExemptAt, samples, NOW);
    }

    @Test
    void noEvidenceNoMlYieldsNone() {
        AiAssessment assessment = assess(List.of(), null, 20.0D, 0L, 0L, 100);
        assertEquals(AiRiskTier.NONE, assessment.tier());
        assertFalse(assessment.reasons().isEmpty(), "every assessment must carry concrete reasons");
        assertEquals(FalsePositiveRisk.NONE, assessment.falsePositiveRisk());
        assertTrue(assessment.suggestedActions().size() >= 1);
    }

    @Test
    void mlAloneNeverExceedsLow() {
        AiAssessment assessment = assess(List.of(), 0.99D, 20.0D, 0L, 0L, 100);
        assertEquals(AiRiskTier.LOW, assessment.tier(), "ML without deterministic corroboration must cap at LOW");
    }

    @Test
    void mlBelowThresholdWithNoEvidenceYieldsNone() {
        AiAssessment assessment = assess(List.of(), 0.59D, 20.0D, 0L, 0L, 100);
        assertEquals(AiRiskTier.NONE, assessment.tier());
    }

    @Test
    void deterministicEvidenceWithoutMlYieldsLow() {
        AiAssessment assessment = assess(List.of(checkEvidence("reach")), null, 20.0D, 0L, 0L, 100);
        assertEquals(AiRiskTier.LOW, assessment.tier());
    }

    @Test
    void mlBelowThresholdWithEvidenceYieldsLow() {
        AiAssessment assessment = assess(List.of(checkEvidence("reach")), 0.10D, 20.0D, 0L, 0L, 100);
        assertEquals(AiRiskTier.LOW, assessment.tier());
    }

    @Test
    void singleCheckPlusMlYieldsMedium() {
        AiAssessment assessment = assess(List.of(checkEvidence("killaura")), 0.8D, 20.0D, 0L, 0L, 100);
        assertEquals(AiRiskTier.MEDIUM, assessment.tier());
        assertEquals(FalsePositiveRisk.NONE, assessment.falsePositiveRisk());
    }

    @Test
    void twoDistinctChecksPlusMlYieldsHigh() {
        AiAssessment assessment = assess(List.of(checkEvidence("reach"), checkEvidence("killaura")),
                0.8D, 20.0D, 0L, 0L, 100);
        assertEquals(AiRiskTier.HIGH, assessment.tier());
    }

    @Test
    void threeSignalsOfOneCheckPlusMlYieldsHigh() {
        AiAssessment assessment = assess(List.of(checkEvidence("reach"), checkEvidence("reach"), checkEvidence("reach")),
                0.8D, 20.0D, 0L, 0L, 100);
        assertEquals(AiRiskTier.HIGH, assessment.tier());
    }

    @Test
    void highPingDowngradesTierAndFlagsFalsePositiveRisk() {
        AiAssessment assessment = assess(List.of(checkEvidence("reach"), checkEvidence("killaura")),
                0.9D, 350.0D, 0L, 0L, 100);
        assertEquals(AiRiskTier.MEDIUM, assessment.tier(), "HIGH must downgrade one level under elevated FP risk");
        assertEquals(FalsePositiveRisk.ELEVATED, assessment.falsePositiveRisk());
        assertTrue(assessment.reasons().stream().anyMatch(r -> r.contains("high ping")));
        assertTrue(assessment.reasons().stream().anyMatch(r -> r.contains("downgraded")));
    }

    @Test
    void freshVelocityExemptionDowngradesTier() {
        AiAssessment assessment = assess(List.of(checkEvidence("reach"), checkEvidence("killaura")),
                0.9D, 20.0D, NOW - 1_000L, 0L, 100);
        assertEquals(AiRiskTier.MEDIUM, assessment.tier());
        assertEquals(FalsePositiveRisk.ELEVATED, assessment.falsePositiveRisk());
    }

    @Test
    void freshTeleportExemptionDowngradesTier() {
        AiAssessment assessment = assess(List.of(checkEvidence("reach"), checkEvidence("killaura")),
                0.9D, 20.0D, 0L, NOW - 2_000L, 100);
        assertEquals(AiRiskTier.MEDIUM, assessment.tier());
        assertEquals(FalsePositiveRisk.ELEVATED, assessment.falsePositiveRisk());
    }

    @Test
    void sparseCombatSamplesDowngradeTier() {
        AiAssessment assessment = assess(List.of(checkEvidence("reach"), checkEvidence("killaura")),
                0.9D, 20.0D, 0L, 0L, 3);
        assertEquals(AiRiskTier.MEDIUM, assessment.tier());
        assertEquals(FalsePositiveRisk.ELEVATED, assessment.falsePositiveRisk());
        assertTrue(assessment.reasons().stream().anyMatch(r -> r.contains("sparse combat samples")));
    }

    @Test
    void staleExemptionsDoNotDowngrade() {
        AiAssessment assessment = assess(List.of(checkEvidence("reach"), checkEvidence("killaura")),
                0.9D, 20.0D, NOW - 30_000L, NOW - 60_000L, 100);
        assertEquals(AiRiskTier.HIGH, assessment.tier());
        assertEquals(FalsePositiveRisk.NONE, assessment.falsePositiveRisk());
    }

    @Test
    void shadowEvidenceNeverCountsAsDeterministic() {
        // ai-shadow entries are the model's own outputs echoed into the store; they must not
        // corroborate ML again (self-corroboration is not evidence).
        Evidence shadow = new Evidence(NOW - 1_000L, EvidenceType.CHECK_EVIDENCE, 1.0D, "monitor",
                "model=x score=0.99", "ai-shadow");
        AiAssessment assessment = assess(List.of(shadow), 0.99D, 20.0D, 0L, 0L, 100);
        assertEquals(AiRiskTier.LOW, assessment.tier(), "shadow evidence must not raise the tier above LOW");
    }

    @Test
    void reasonsReferenceTheNumbersThatDroveTheTier() {
        AiAssessment assessment = assess(List.of(checkEvidence("reach")), 0.87D, 20.0D, 0L, 0L, 100);
        String joined = String.join(" | ", assessment.reasons());
        assertTrue(joined.contains("0.870"), "ML score must appear in reasons, got: " + joined);
        assertTrue(joined.contains("reach"), "deterministic check name must appear in reasons, got: " + joined);
    }

    @Test
    void everyAssessmentCarriesDisclaimerAndActions() {
        for (Double ml : new Double[] {null, 0.1D, 0.9D}) {
            AiAssessment assessment = assess(List.of(checkEvidence("reach")), ml, 20.0D, 0L, 0L, 100);
            assertFalse(assessment.suggestedActions().isEmpty());
            assertNotNull(AiAssessment.DISCLAIMER);
            assertFalse(AiAssessment.DISCLAIMER.isEmpty());
        }
    }

    @Test
    void tierOrderingIsStrict() {
        assertTrue(AiRiskTier.NONE.ordinal() < AiRiskTier.LOW.ordinal());
        assertTrue(AiRiskTier.LOW.ordinal() < AiRiskTier.MEDIUM.ordinal());
        assertTrue(AiRiskTier.MEDIUM.ordinal() < AiRiskTier.HIGH.ordinal());
        assertEquals(AiRiskTier.MEDIUM, AiRiskTier.HIGH.downgradeOne());
        assertEquals(AiRiskTier.LOW, AiRiskTier.MEDIUM.downgradeOne());
        assertEquals(AiRiskTier.NONE, AiRiskTier.LOW.downgradeOne());
        assertEquals(AiRiskTier.NONE, AiRiskTier.NONE.downgradeOne());
    }
}
