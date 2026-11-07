package ac.vigil.monitor;

/**
 * Three-level verdict strength for correlated evidence. This is a
 * <em>discipline label</em>, not a probability:
 *
 * <ul>
 *   <li>{@link #CONFIRMED} — two or more independent supporting signals, none suppressed.</li>
 *   <li>{@link #SUSPECTED} — exactly one supporting signal.</li>
 *   <li>{@link #INCONCLUSIVE} — no supporting signal, or one or more suppressing
 *       (exculpatory) signals present.</li>
 * </ul>
 */
public enum CorrelationStatus {
    CONFIRMED,
    SUSPECTED,
    INCONCLUSIVE
}
