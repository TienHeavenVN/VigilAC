package ac.vigil.check;

/**
 * Base contract for every detection check.
 *
 * @param <C> the context type this check consumes ({@code DetectionContext}
 *            for movement checks, {@code AttackContext} for combat checks)
 *
 * <p>Implementations must be stateless with respect to individual calls:
 * all per-player state lives in {@link ac.vigil.data.PlayerData}. Checks are
 * expected to avoid expensive computation and to return quickly; they
 * accumulate violation level (VL) through the provided callback only when
 * evidence actually exceeds the configured threshold.</p>
 */
public interface Check<C> {

    /** Stable, lower-case, hyphen-free identifier used in config keys and storage. */
    String getName();

    CheckCategory getCategory();

    /**
     * Evaluate one observation.
     *
     * @param context  immutable observation snapshot
     * @param data     per-player state (thread-safe)
     * @param callback invoked when this observation contributes violation level
     */
    void evaluate(C context, ac.vigil.data.PlayerData data, ViolationCallback callback);
}
