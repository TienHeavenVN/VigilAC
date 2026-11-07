package ac.vigil.ai.evidence;

/**
 * Estimated risk that an observation would be a false positive if treated as
 * a cheat signal. Advisory only; surfaced to administrators.
 */
public enum FalsePositiveRisk {
    /** No elevated false-positive signals. */
    NONE,
    /** One elevated signal (high ping, fresh exemption, sparse samples). */
    ELEVATED
}
