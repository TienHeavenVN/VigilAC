package ac.vigil.monitor;

import java.util.ArrayList;
import java.util.List;

/**
 * Correlates independent monitor evidence streams into a confidence-weighted
 * assessment. Stateless and side-effect free; all reads go through
 * {@link PlayerMonitor}'s public, bounded APIs, so calls are O(1)-ish
 * (ring scans bounded by the store capacity).
 *
 * <h2>Design rules</h2>
 * <ul>
 *   <li>Server-authoritative facts (fall-damage events, teleports, velocity
 *       impulses) outweigh client-reported observations.</li>
 *   <li>Unrelated damage NEVER suppresses NoFall evidence. Only FALL-cause
 *       damage is exculpatory for NoFall.</li>
 *   <li>Correlation <em>informs</em> (confidence, debug output); it does not
 *       override a check's configured gates.</li>
 * </ul>
 */
public final class CorrelationEngine {

    /** Window for the suspicious-landing support counter. */
    public static final long RECENT_WINDOW_MILLIS = 2_000L;
    /** Per-factor suppression windows — a stale impulse must NOT excuse a landing. */
    public static final long TELEPORT_SUPPRESS_MILLIS = 2_000L;
    public static final long VELOCITY_SUPPRESS_MILLIS = 1_500L;
    public static final long FALL_DAMAGE_SUPPRESS_MILLIS = 1_500L;
    public static final long WORLD_CHANGE_SUPPRESS_MILLIS = 2_000L;

    /**
     * NoFall corroboration at the moment of a suspicious landing.
     *
     * @param monitor per-player monitor state (never null)
     * @param descent accumulated descent of the landing (blocks)
     * @param minFallDistance configured NoFall threshold (blocks)
     */
    public CorrelationResult correlateNoFall(PlayerMonitor monitor, double descent, double minFallDistance) {
        List<String> supporting = new ArrayList<>();
        List<String> suppressing = new ArrayList<>();

        double descentRatio = minFallDistance > 0.0D ? descent / minFallDistance : 0.0D;
        if (descentRatio >= 1.0D) {
            supporting.add(String.format(java.util.Locale.US, "descent=%.2f exceeds threshold (x%.2f)", descent, descentRatio));
        }

        long teleportAge = monitor.ageOfLatest(EvidenceType.TELEPORT);
        if (teleportAge >= 0L && teleportAge <= TELEPORT_SUPPRESS_MILLIS) {
            suppressing.add(String.format(java.util.Locale.US, "teleport %dms ago", teleportAge));
        }
        long velocityAge = monitor.ageOfLatest(EvidenceType.VELOCITY_IMPULSE);
        if (velocityAge >= 0L && velocityAge <= VELOCITY_SUPPRESS_MILLIS) {
            suppressing.add(String.format(java.util.Locale.US, "velocity impulse %dms ago", velocityAge));
        }
        long fallDamageAge = monitor.ageOfLatest(EvidenceType.DAMAGE_FALL);
        if (fallDamageAge >= 0L && fallDamageAge <= FALL_DAMAGE_SUPPRESS_MILLIS) {
            suppressing.add(String.format(java.util.Locale.US, "fall damage %dms ago", fallDamageAge));
        }
        long worldAge = monitor.ageOfLatest(EvidenceType.WORLD_CHANGE);
        if (worldAge >= 0L && worldAge <= WORLD_CHANGE_SUPPRESS_MILLIS) {
            suppressing.add(String.format(java.util.Locale.US, "world change %dms ago", worldAge));
        }

        int suspicious = monitor.countEvidenceSince(EvidenceType.NOFALL_SUSPICIOUS_LANDING,
                monitor.nowMillis() - RECENT_WINDOW_MILLIS);
        if (suspicious > 0) {
            supporting.add(String.format(java.util.Locale.US,
                    "%d suspicious landing(s) in the last %ds", suspicious, RECENT_WINDOW_MILLIS / 1000L));
        }

        double confidence;
        if (!suppressing.isEmpty()) {
            confidence = 0.0D; // exculpatory evidence: no confidence in a violation
        } else {
            confidence = Math.min(1.0D, 0.35D * Math.min(descentRatio, 2.0D) + 0.15D * Math.min(suspicious, 4));
        }
        return new CorrelationResult(confidence, supporting, suppressing);
    }
}
