package ac.vigil.violation;

public enum Verdict {
    /** Below the alert threshold: silently accumulate VL. */
    NONE,
    /** Staff-visible alert. */
    ALERT,
    /** Alert + in-game warning to the player. */
    WARN,
    /** Punishment threshold; kick (or equivalent configured action) if mode is not alert-only. */
    KICK
}
