package ac.vigil.monitor;

/**
 * One timestamped, confidence-weighted piece of evidence about a player.
 * Immutable. Confidence is in [0, 1]; sources are short stable strings
 * (e.g. "server", "client", "monitor"). Client-reported fields are always
 * recorded with SOURCE_CLIENT and treated as untrusted observations.
 */
public final class Evidence {

    public static final String SOURCE_SERVER = "server";
    public static final String SOURCE_CLIENT = "client";
    public static final String SOURCE_MONITOR = "monitor";
    /** Packet-level observation via packetevents (off the main thread). */
    public static final String SOURCE_PACKET = "packet";

    private final long timeMillis;
    private final EvidenceType type;
    private final double confidence;
    private final String source;
    private final String detail;
    private final String check;

    public Evidence(long timeMillis, EvidenceType type, double confidence, String source, String detail) {
        this(timeMillis, type, confidence, source, detail, "");
    }

    public Evidence(long timeMillis, EvidenceType type, double confidence, String source, String detail,
                    String check) {
        this.timeMillis = timeMillis;
        this.type = type;
        this.confidence = Math.max(0.0D, Math.min(1.0D, confidence));
        this.source = source == null ? SOURCE_MONITOR : source;
        this.detail = detail == null ? "" : detail;
        this.check = check == null ? "" : check;
    }

    public long timeMillis() { return timeMillis; }
    public EvidenceType type() { return type; }
    public double confidence() { return confidence; }
    public String source() { return source; }
    public String detail() { return detail; }

    /** Check that produced this evidence entry, or "" when not check-scoped. */
    public String check() { return check; }

    @Override
    public String toString() {
        return String.format(java.util.Locale.US,
                "%d %s conf=%.2f src=%s %s", timeMillis, type, confidence, source, detail);
    }
}
