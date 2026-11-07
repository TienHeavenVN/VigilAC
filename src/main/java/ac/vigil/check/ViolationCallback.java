package ac.vigil.check;

/**
 * Sink passed to checks so they can report evidence. Implemented by the
 * violation pipeline ({@code ViolationManager}); checks must not talk to
 * Bukkit/Paper directly.
 */
@FunctionalInterface
public interface ViolationCallback {

    /**
     * @param check    the check that produced the evidence
     * @param vlAmount violation level to add (always &gt; 0)
     * @param debug    human-readable debug description containing measured values
     */
    void onViolation(Check<?> check, double vlAmount, String debug);
}
