package ac.vigil.detection;

/**
 * Resolves an attack attempt into world facts on the appropriate (main)
 * thread. Implementations must never be called from netty threads and must
 * never throw — failures are reported as {@link AttackGeometryResolution}
 * with {@code found=false}.
 */
public interface AttackGeometryResolver {

    AttackGeometryResolution resolve(java.util.UUID attacker, int entityId);
}
