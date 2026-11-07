package ac.vigil.monitor;

/** Lifecycle state of one check for one player, derived at query time. */
public enum DetectionState {
    /** VL == 0, no fresh exemption reason. */
    CLEAR,
    /** VL > 0 but below the alert threshold. */
    SUSPECT,
    /** VL >= alert threshold. */
    VIOLATION,
    /** The check observed the player but was excused for an environmental reason (reason retained). */
    EXEMPT,
    /** The check is disabled in config (or was never enabled). */
    DISABLED
}
