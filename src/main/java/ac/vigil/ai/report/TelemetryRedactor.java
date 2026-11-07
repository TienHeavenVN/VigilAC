package ac.vigil.ai.report;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Strips identifiers from text before it leaves the trust boundary (admin
 * report with redaction enabled, or any future LLM provider). Redaction is
 * lossy by design and is applied to the whole rendered string, not just
 * structured fields.
 *
 * <p>Covered: UUIDs, IPv4 literals, and one caller-supplied player name
 * (case-insensitive). NOT covered (documented limitation): usernames of
 * <em>other</em> players mentioned in free text, world names, server brand —
 * callers who enable external egress must accept that (see
 * docs/AI_ARCHITECTURE.md).</p>
 */
public final class TelemetryRedactor {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern IPV4_PATTERN = Pattern.compile(
            "\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");

    private final boolean redact;
    private final String playerName; // current target, may be null

    public TelemetryRedactor(boolean redact, String playerName) {
        this.redact = redact;
        this.playerName = playerName;
    }

    public String redact(String input) {
        if (input == null || !redact) {
            return input;
        }
        String out = UUID_PATTERN.matcher(input).replaceAll("[uuid-redacted]");
        out = IPV4_PATTERN.matcher(out).replaceAll("[ip-redacted]");
        if (playerName != null && playerName.length() >= 3) {
            out = out.replaceAll("(?i)\\Q" + playerName + "\\E", "[player-redacted]");
        }
        return out;
    }

    /** Short stable fingerprint of an identifier (for correlating redacted reports without revealing it). */
    public static String fingerprint(String identifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(identifier.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 4 && i < hash.length; i++) {
                hex.append(String.format(Locale.US, "%02x", hash[i]));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return "00000000";
        }
    }
}
