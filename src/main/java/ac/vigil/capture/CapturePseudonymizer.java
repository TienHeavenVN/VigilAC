package ac.vigil.capture;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;

/**
 * HMAC-SHA256 pseudonymization for the capture. Pure Java; holds the key.
 *
 * <ul>
 *   <li><b>player_pseudonym</b> = HMAC(key, "player/" + bucket + "/" + uuid)[:32hex],
 *       where bucket = floor(now / rotationMillis). Rotating the bucket makes
 *       pseudonyms unlinkable across rotation windows; within a bucket they are stable
 *       (needed for group-by-player train/test splits).</li>
 *   <li><b>session_pseudonym</b> = 128-bit CSPRNG hex, re-drawn on every join; kept
 *       in memory only. Never derived from the UUID, never persisted.</li>
 *   <li><b>consent token</b> = HMAC(key, "consent/" + uuid)[:32hex] — stable across
 *       buckets so revocation keeps working; used only by the consent store, never
 *       written into the corpus.</li>
 * </ul>
 *
 * No method exposes a UUID, a name or an IP to callers other than the consent store
 * path. Nothing here depends on Bukkit.
 */
public final class CapturePseudonymizer {

    private static final HexFormat HEX = HexFormat.of();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] key;
    private final long rotationMillis;

    public CapturePseudonymizer(byte[] key, int rotationHours) {
        if (key == null || key.length < 16) {
            throw new IllegalArgumentException("pseudonym key must be >= 16 bytes");
        }
        this.key = key.clone();
        this.rotationMillis = Math.max(0L, rotationHours) * 3_600_000L;
    }

    /** 32-char lowercase hex pseudonym for the current rotation bucket. */
    public String playerPseudonym(UUID uuid, long nowMillis) {
        long bucket = rotationMillis <= 0L ? 0L : nowMillis / rotationMillis;
        return hmacHex("player/" + bucket + "/" + uuid);
    }

    /** Fresh 32-char lowercase hex session id (CSPRNG). Call once per join. */
    public String newSessionPseudonym() {
        byte[] raw = new byte[16];
        RANDOM.nextBytes(raw);
        return HEX.formatHex(raw);
    }

    /** Stable 32-char lowercase hex consent token; not rotation-scoped. */
    public String consentToken(UUID uuid) {
        return hmacHex("consent/" + uuid);
    }

    public long rotationBucket(long nowMillis) {
        return rotationMillis <= 0L ? 0L : nowMillis / rotationMillis;
    }

    private String hmacHex(String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HEX.formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8))).substring(0, 32);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e); // never expected on any JVM
        }
    }
}
