package ac.vigil.ai.report;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import ac.vigil.ai.evidence.AiAssessment;
import ac.vigil.ai.evidence.AiEvidenceCorrelator;
import ac.vigil.ai.evidence.AiRiskTier;
import ac.vigil.monitor.Evidence;
import ac.vigil.monitor.EvidenceType;

class AdminAiReportTest {

    private static PlayerProfile sampleProfile(List<Double> mlHistory) {
        return PlayerProfile.builder()
                .targetName("Steve")
                .snapshotAtMillis(1_000_000L)
                .cps1s(12.5D).cps5s(11.0D)
                .clickIntervalMean(83.0D).clickIntervalStddev(12.0D).clickIntervalCv(0.14D)
                .clickIntervalEntropyBits(3.4D)
                .rotationAngularVelocity1s(140.0D).rotationMaxDelta1s(22.0D)
                .reachEma(3.1D).reachSamples(140)
                .packetsPerSecond(19.5D)
                .breakIntervalMillisClipped(0L).impossibleBreakStreak(0)
                .pingMillis(42.0D)
                .deterministicEvidenceCount(2)
                .deterministicCheckNames(List.of("reach", "killaura"))
                .totalViolationLevelChecks(2)
                .mlHistory(mlHistory)
                .build();
    }

    private static AiAssessment mediumAssessment() {
        AiEvidenceCorrelator correlator = new AiEvidenceCorrelator(0.6D, 20);
        List<Evidence> evidence = List.of(
                new Evidence(999_000L, EvidenceType.CHECK_EVIDENCE, 0.9D, "server", "e", "reach"));
        return correlator.assess(evidence, 0.8D, "unit-test-model", 20.0D, 0L, 0L, 100, 1_000_000L);
    }

    @Test
    void reportContainsLimitationsAndAdvisoryDisclaimer() {
        String report = AiReportBuilder.render(sampleProfile(List.of(0.2D, 0.3D, 0.5D, 0.7D)),
                mediumAssessment(), List.of("unit-test-model: loaded"), 0.6D, List.of("pending=0", "completed=4"));
        assertTrue(report.contains("Limitations"), "report must embed its limitations footer");
        assertTrue(report.contains("never writes violation levels"), "limitations must state the no-VL rule");
        assertTrue(report.contains("REAL_CLIENT_VALIDATION"), "report must state real-client validation status");
        assertTrue(report.contains(AiAssessment.DISCLAIMER), "report must carry the advisory disclaimer");
        assertTrue(report.contains("Shadow mode only"));
    }

    @Test
    void reportContainsProfileAndAssessmentSections() {
        String report = AiReportBuilder.render(sampleProfile(List.of(0.2D, 0.3D, 0.5D, 0.7D)),
                mediumAssessment(), List.of("unit-test-model: loaded"), 0.6D, List.of());
        assertTrue(report.contains("Steve"));
        assertTrue(report.contains("Behavioral profile"));
        assertTrue(report.contains("Deterministic evidence"));
        assertTrue(report.contains("MEDIUM"));
        assertTrue(report.contains("reach"));
        assertTrue(report.contains("trend RISING"), "ML history trending up must render as RISING, got:\n" + report);
    }

    @Test
    void reportWithNoAssessmentRendersExplicitNote() {
        String report = AiReportBuilder.render(sampleProfile(List.of()), null, List.of(), 0.6D, List.of());
        assertTrue(report.contains("no assessment available"));
        assertTrue(report.contains("no models loaded"));
    }

    @Test
    void trendComputationIsHonest() {
        assertEquals(PlayerProfile.Trend.INSUFFICIENT_DATA, PlayerProfile.trendOf(List.of(0.1D)));
        assertEquals(PlayerProfile.Trend.RISING, PlayerProfile.trendOf(List.of(0.1D, 0.1D, 0.3D, 0.4D)));
        assertEquals(PlayerProfile.Trend.FALLING, PlayerProfile.trendOf(List.of(0.8D, 0.7D, 0.3D, 0.2D)));
        assertEquals(PlayerProfile.Trend.FLAT, PlayerProfile.trendOf(List.of(0.5D, 0.5D, 0.5D, 0.5D)));
    }

    @Test
    void mlHistoryIsRenderedOldestToNewest() {
        String report = AiReportBuilder.render(sampleProfile(List.of(0.11D, 0.22D, 0.33D, 0.44D, 0.55D)),
                null, List.of(), 0.6D, List.of());
        int a = report.indexOf("0.11");
        int b = report.indexOf("0.55");
        assertTrue(a >= 0 && b > a, "history must render oldest first");
    }
}
