package ac.vigil.ai.evidence;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import ac.vigil.monitor.Evidence;
import ac.vigil.monitor.EvidenceType;

/**
 * Correlates deterministic check evidence with (optional) ML anomaly scores
 * and contextual exemption signals into an {@link AiAssessment}.
 *
 * <h2>Decision rules (exhaustive — no hidden branches)</h2>
 * <p>Let {@code det} = deterministic signal count (CHECK_EVIDENCE entries
 * plus FAST_BREAK_IMPOSSIBLE / NOFALL_SUSPICIOUS_LANDING entries in the
 * window), {@code detChecks} = distinct checks among CHECK_EVIDENCE entries,
 * {@code ml} = ML anomaly score (nullable), {@code T} = configured threshold.</p>
 * <ol>
 *   <li>{@code det == 0} and no ML score ≥ T → <b>NONE</b>.</li>
 *   <li>{@code det == 0} and ML ≥ T → <b>LOW</b>. A lone anomaly score NEVER
 *       rises above LOW and never implies a violation (hard rule).</li>
 *   <li>{@code det > 0} and (no ML score or ML &lt; T) → <b>LOW</b> — the deterministic
 *       engine is carrying the case; AI adds nothing.</li>
 *   <li>{@code det > 0} and ML ≥ T → <b>MEDIUM</b>; escalated to <b>HIGH</b>
 *       only when {@code detChecks >= 2} or {@code det >= 3}. Any elevated
 *       false-positive signal (high ping, fresh velocity/teleport exemption,
 *       sparse combat samples) downgrades the tier by one level, and the
 *       reason is recorded.</li>
 * </ol>
 *
 * <h2>Guarantees</h2>
 * <ul>
 *   <li>Every output includes ≥ 1 concrete, traceable reason with the actual
 *       numbers that drove the tier.</li>
 *   <li>The correlator is a pure function of its inputs — no VL, no alerts,
 *       no punishments, no side effects.</li>
 *   <li>Fresh exemptions are corroboration-suppressors, never punishment
 *       boosters; they only ever downgrade.</li>
 * </ul>
 */
public final class AiEvidenceCorrelator {

    public static final double DEFAULT_ML_THRESHOLD = 0.6D;
    public static final double HIGH_PING_MILLIS = 300.0D;
    public static final int MIN_COMBAT_SAMPLES = 20;
    /** Aligned with ac.vigil.monitor.PlayerMonitor.EXEMPTION_FRESHNESS_MILLIS (5 s). */
    private static final long FRESH_EXEMPTION_MILLIS = 5_000L;

    private final double mlThreshold;
    private final int minCombatSamples;

    public AiEvidenceCorrelator(double mlThreshold, int minCombatSamples) {
        this.mlThreshold = mlThreshold;
        this.minCombatSamples = minCombatSamples;
    }

    /**
     * @param evidence deterministic evidence window (typically EvidenceStore.snapshot())
     * @param mlScore ML anomaly score for the same player/window, or null when unavailable
     * @param mlModelId model that produced the score (for traceability), may be null
     * @param pingMillis server-reported ping
     * @param lastVelocityExemptionAt epoch ms of last velocity exemption (0 = none)
     * @param lastTeleportExemptionAt epoch ms of last teleport exemption (0 = none)
     * @param combatIntervalSamples number of click-interval samples backing cadence features
     * @param now current time (ms), injectable for tests
     */
    public AiAssessment assess(List<Evidence> evidence, Double mlScore, String mlModelId,
                               double pingMillis, long lastVelocityExemptionAt, long lastTeleportExemptionAt,
                               int combatIntervalSamples, long now) {
        List<Evidence> safeEvidence = evidence == null ? List.of() : evidence;
        int det = 0;
        TreeSet<String> detChecks = new TreeSet<>();
        for (Evidence e : safeEvidence) {
            if (e.type() == EvidenceType.CHECK_EVIDENCE) {
                String check = e.check();
                // Shadow-model outputs land in the same store under "ai-shadow"; they must
                // never count as deterministic corroboration (self-corroboration is not evidence).
                if (check != null && check.equalsIgnoreCase("ai-shadow")) {
                    continue;
                }
                det++;
                if (e.check() != null && !e.check().isEmpty()) {
                    detChecks.add(e.check());
                }
            } else if (e.type() == EvidenceType.FAST_BREAK_IMPOSSIBLE || e.type() == EvidenceType.NOFALL_SUSPICIOUS_LANDING) {
                det++;
            }
        }
        boolean mlHigh = mlScore != null && mlScore >= mlThreshold;
        boolean mlPresent = mlScore != null;

        // ---- false-positive signals (only ever downgrade)
        List<String> fpReasons = new ArrayList<>();
        if (pingMillis > HIGH_PING_MILLIS) {
            fpReasons.add(String.format(Locale.US, "high ping %.0f ms (> %.0f ms)", pingMillis, HIGH_PING_MILLIS));
        }
        if (lastVelocityExemptionAt > 0 && now - lastVelocityExemptionAt >= 0 && now - lastVelocityExemptionAt <= FRESH_EXEMPTION_MILLIS) {
            fpReasons.add(String.format(Locale.US, "fresh knockback/velocity exemption %d ms ago", now - lastVelocityExemptionAt));
        }
        if (lastTeleportExemptionAt > 0 && now - lastTeleportExemptionAt >= 0 && now - lastTeleportExemptionAt <= FRESH_EXEMPTION_MILLIS) {
            fpReasons.add(String.format(Locale.US, "fresh teleport exemption %d ms ago", now - lastTeleportExemptionAt));
        }
        if (combatIntervalSamples < minCombatSamples) {
            fpReasons.add("sparse combat samples (" + combatIntervalSamples + " < " + minCombatSamples + ")");
        }
        FalsePositiveRisk fpRisk = fpReasons.isEmpty() ? FalsePositiveRisk.NONE : FalsePositiveRisk.ELEVATED;

        List<String> reasons = new ArrayList<>();
        if (det > 0) {
            reasons.add("deterministic evidence: " + det + " signal(s)"
                    + (detChecks.isEmpty()
                        ? " (no per-check names attached)"
                        : " across " + detChecks.size() + " check(s) [" + String.join(", ", detChecks) + "]"));
        } else {
            reasons.add("no deterministic check evidence in the window");
        }
        if (mlPresent) {
            reasons.add(String.format(Locale.US, "ML anomaly score %.3f (%s threshold %.2f, model %s)",
                    mlScore, mlHigh ? ">=" : "<", mlThreshold, mlModelId == null ? "unknown" : mlModelId));
        } else {
            reasons.add("ML score unavailable (no model loaded or AI in no-model mode)");
        }
        if (fpRisk == FalsePositiveRisk.ELEVATED) {
            for (String fp : fpReasons) {
                reasons.add("false-positive risk: " + fp);
            }
        }

        AiRiskTier tier;
        if (det == 0) {
            // ML alone — hard cap at LOW regardless of configuration
            tier = mlHigh ? AiRiskTier.LOW : AiRiskTier.NONE;
        } else if (!mlHigh) {
            tier = AiRiskTier.LOW;
        } else {
            tier = (detChecks.size() >= 2 || det >= 3) ? AiRiskTier.HIGH : AiRiskTier.MEDIUM;
            if (fpRisk == FalsePositiveRisk.ELEVATED) {
                AiRiskTier downgraded = tier.downgradeOne();
                reasons.add("tier downgraded from " + tier + " to " + downgraded + " by elevated false-positive risk");
                tier = downgraded;
            }
        }

        List<String> actions = suggestedActions(tier, detChecks, mlHigh);
        return new AiAssessment(now, tier, mlScore, mlModelId, fpRisk, reasons, actions);
    }

    private static List<String> suggestedActions(AiRiskTier tier, java.util.Set<String> detChecks, boolean mlHigh) {
        List<String> actions = new ArrayList<>();
        switch (tier) {
            case NONE:
                actions.add("no action; keep monitoring");
                break;
            case LOW:
                actions.add("log only; re-check in a few minutes of normal play");
                actions.add("verify client-side mods/ping settings if the pattern persists");
                break;
            case MEDIUM:
                actions.add("review /vigilac monitor output and packet logs for this player");
                actions.add("sample replays/POV if available; look for repeated impossible actions, not single events");
                actions.add("compare against known-legit high performers before any action");
                break;
            case HIGH:
                actions.add("prioritise human review; cross-check with /vigilac check and evidence timeline");
                actions.add("gather corroboration from at least one more independent signal (replays, packet capture, reports)");
                actions.add("do not punish from this report alone; escalate per server policy");
                break;
            default:
                actions.add("no action");
        }
        if (mlHigh && detChecks != null && !detChecks.isEmpty()) {
            actions.add("ML-flagged pattern overlaps deterministic checks: " + String.join(", ", detChecks) + " — treat as one story, not two tickets");
        }
        return actions;
    }
}
