package ac.vigil.environment;

/**
 * Server-side collision query used to verify a client's {@code onGround}
 * claim at landing. Implementations must be main-thread-only, exception-free
 * (callers treat any failure as "supported", the fail-safe direction).
 *
 * <p>Semantics: {@code true} = the server can see at least one solid block
 * spanning enough of the feet footprint that the player legitimately stands
 * there. {@code false} = the feet hover above air — the client claimed
 * ground in mid-air.</p>
 */
public interface BlockSupportProbe {

    /**
     * @param worldName world the player is in (as reported by the move event)
     * @param x,y,z     feet position (player eye-less origin: x/z center, y feet bottom)
     * @return true when support exists (or the situation cannot be judged)
     */
    boolean hasSupportBeneath(String worldName, double x, double y, double z);
}
