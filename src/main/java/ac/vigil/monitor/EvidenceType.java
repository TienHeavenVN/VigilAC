package ac.vigil.monitor;

/**
 * Behavioral fact classes emitted by the monitor and consumed by the
 * correlation engine. These are <em>observations</em>, not violations: VL
 * accrues only in checks. Types are additive within a minor line — consumers
 * must tolerate unknown values (defensive switch defaults).
 */
public enum EvidenceType {
    /** Airborne period started (ground → air transition). */
    GROUND_EXIT,
    /** Landed (air → ground transition); detail carries accumulated descent. */
    LANDED,
    /** A landing whose estimated descent exceeded the damage threshold with no corroboration. */
    NOFALL_SUSPICIOUS_LANDING,
    /** Server-side knockback impulse applied to the player (PlayerVelocityEvent). */
    VELOCITY_IMPULSE,
    /** Server-side teleport (event, relative delta too large, or recorded by a listener). */
    TELEPORT,
    /** World/dimension name changed between consecutive observations. */
    WORLD_CHANGE,
    /** EntityDamageEvent with cause FALL on this player. */
    DAMAGE_FALL,
    /** EntityDamageEvent with any non-FALL cause on this player (never exculpatory for NoFall). */
    DAMAGE_GENERIC,
    /** Melee attack attempt observed (EntityDamageByEntityEvent). */
    ATTACK_MELEE,
    /** Completed block break (BlockBreakEvent). */
    BREAK_BLOCK,
    /** Block placement completed (BlockPlaceEvent). */
    PLACE_BLOCK,
    /** A break whose inter-completion interval was below the vanilla lower bound. */
    FAST_BREAK_IMPOSSIBLE,
    /** A check recorded evidence (delta > 0 VL, or diagnostic evidence at vl == 0). */
    CHECK_EVIDENCE,
    /** Inventory click observation (view type in detail); context for inventory-related analysis. */
    INVENTORY_CLICK,
    /** Attack <em>attempt</em> observed at packet level (may be a miss / no damage). */
    ATTACK_ATTEMPT
}
