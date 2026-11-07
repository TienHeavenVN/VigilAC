package ac.vigil.capture;

import java.util.ArrayList;
import java.util.List;

import ac.vigil.ai.ConfigSource;

/**
 * Owner-controlled configuration for the consent-based capture. Pure Java; the
 * production source is {@code BukkitConfigSource} (same shape as the AI module).
 *
 * <p><b>Default is OFF and fail-safe.</b> When {@code capture.enabled} is false, or
 * any required declaration is missing, the capture stays completely inert and reports
 * why via {@link #problems()}. It never guesses a scenario, a label or a key.</p>
 *
 * <p>Ground-truth honesty: {@code capture.label} is the owner's explicit attestation
 * about what this capture contains (LEGITIMATE, SUSPICIOUS or CHEAT_VERIFIED). CHEAT_VERIFIED
 * additionally requires a lab/moderator source and confidence &ge; 0.8 — the same gate the
 * Stage 3 pipeline enforces, checked here as well so a misconfigured lab run fails at
 * startup instead of producing an unusable corpus. Detection firing NEVER sets this value.</p>
 */
public final class CaptureConfig {

    public enum CaptureLabel { LEGITIMATE, SUSPICIOUS, CHEAT_VERIFIED }

    public enum LabelSource { SERVER_TELEMETRY_CURATION, CONTROLLED_LAB, MODERATOR_REVIEW, HEURISTIC_FLAG }

    public static final int INPUT_CATEGORY_MAX = 32;
    private static final long MIN_PSEUDONYM_KEY_BYTES = 16;

    private final ConfigSource source;

    public CaptureConfig(ConfigSource source) {
        this.source = source;
    }

    public boolean enabled() {
        return source.getBoolean("capture.enabled", false);
    }

    /** Scenario tag written into every row (e.g. "lab-autoclicker-20cps"). Required when enabled. */
    public String scenario() {
        return source.getString("capture.scenario", "").trim();
    }

    /** Owner-declared ground truth for rows written by this capture. Required when enabled. */
    public CaptureLabel label() {
        String raw = source.getString("capture.label", "").trim().toUpperCase(java.util.Locale.ROOT);
        try {
            return raw.isEmpty() ? null : CaptureLabel.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Provenance of the declared label. Required when enabled. */
    public LabelSource labelSource() {
        String raw = source.getString("capture.label-source", "").trim().toUpperCase(java.util.Locale.ROOT);
        try {
            return raw.isEmpty() ? null : LabelSource.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Confidence the OWNER attests for the declared label. Required when enabled; -1 when unset. */
    public double labelConfidence() {
        return source.getDouble("capture.label-confidence", -1.0D);
    }

    /** Client category tag (free text <= 64 chars; e.g. VANILLA, LAB_CHEAT_AUTOCLICK). Defaults to UNKNOWN. */
    public String clientCategory() {
        String value = source.getString("capture.client-category", "UNKNOWN").trim();
        return value.length() > 64 ? value.substring(0, 64) : value;
    }

    /** MOUSE|KEYBOARD|MIXED|CONTROLLER|UNKNOWN — never inferred. */
    public String inputCategory() {
        return source.getString("capture.input-category", "UNKNOWN").trim().toUpperCase(java.util.Locale.ROOT);
    }

    /** Base64 HMAC key for pseudonymization. Required when enabled (>= 16 decoded bytes). */
    public String pseudonymKeyBase64() {
        return source.getString("capture.pseudonym-key", "").trim();
    }

    /** Player-pseudonym rotation bucket, in hours. 0 disables rotation (not recommended). */
    public int pseudonymRotationHours() {
        return source.getInt("capture.pseudonym-rotation-hours", 24);
    }

    /** Capture cadence in seconds. */
    public int intervalSeconds() {
        return source.getInt("capture.interval-seconds", 10);
    }

    /** Bounded buffer between the scheduler and the writer thread. */
    public int bufferCapacity() {
        return source.getInt("capture.buffer-capacity", 1024);
    }

    /** Hard cap on total corpus bytes. Capture halts (fail safe) when exceeded. */
    public long maxTotalBytes() {
        return (long) source.getDouble("capture.max-total-bytes", 256.0D * 1024 * 1024);
    }

    /** Files whose embedded UTC date is older than this are purged at startup and on demand. */
    public int retentionDays() {
        return source.getInt("capture.retention-days", 90);
    }

    /** Whether consented players receive a one-line notice on join. */
    public boolean notifyOnJoin() {
        return source.getBoolean("capture.notify-on-join", true);
    }

    /** Provenance note written into every row. Keep short; it is not a license field. */
    public String provenanceNote() {
        return source.getString("capture.provenance-note", "consent-capture/1").trim();
    }

    /** Decoded pseudonym key; null when unset/undecodable (see problems()). */
    public byte[] pseudonymKey() {
        String raw = pseudonymKeyBase64();
        if (raw.isEmpty()) {
            return null;
        }
        try {
            byte[] decoded = java.util.Base64.getDecoder().decode(raw);
            return decoded.length >= MIN_PSEUDONYM_KEY_BYTES ? decoded : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Problems that prevent the capture from writing a single row. Empty when ready. */
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (!enabled()) {
            return problems; // disabled is a valid state; nothing to check
        }
        if (scenario().isEmpty()) {
            problems.add("capture.scenario is required when capture.enabled=true (e.g. 'lab-autoclicker-20cps' or 'ranked-legit-farm')");
        } else if (scenario().length() > 64) {
            problems.add("capture.scenario must be <= 64 chars");
        }
        if (label() == null) {
            problems.add("capture.label must be LEGITIMATE|SUSPICIOUS|CHEAT_VERIFIED — the owner must attest what this capture contains; capture never guesses ground truth");
        }
        if (labelSource() == null) {
            problems.add("capture.label-source must be SERVER_TELEMETRY_CURATION|CONTROLLED_LAB|MODERATOR_REVIEW|HEURISTIC_FLAG");
        }
        double confidence = labelConfidence();
        if (Double.isNaN(confidence) || confidence < 0.0D || confidence > 1.0D) {
            problems.add("capture.label-confidence must be in [0,1] — it is the owner's attestation, not a measurement");
        }
        if (label() == CaptureLabel.CHEAT_VERIFIED) {
            if (labelSource() != LabelSource.CONTROLLED_LAB && labelSource() != LabelSource.MODERATOR_REVIEW) {
                problems.add("CHEAT_VERIFIED requires capture.label-source CONTROLLED_LAB or MODERATOR_REVIEW (a detection firing is never a verified label)");
            }
            if (confidence < 0.8D) {
                problems.add("CHEAT_VERIFIED requires capture.label-confidence >= 0.8");
            }
        }
        String input = inputCategory();
        if (!input.equals("MOUSE") && !input.equals("KEYBOARD") && !input.equals("MIXED")
                && !input.equals("CONTROLLER") && !input.equals("UNKNOWN")) {
            problems.add("capture.input-category must be MOUSE|KEYBOARD|MIXED|CONTROLLER|UNKNOWN");
        }
        if (clientCategory().isEmpty()) {
            problems.add("capture.client-category must not be blank (use UNKNOWN when unknown)");
        }
        if (pseudonymKey() == null) {
            problems.add("capture.pseudonym-key must be base64, decoded length >= 16 bytes (openssl rand -base64 32). Without a key, rows would carry unrecoverable pseudonyms or none at all — capture stays off.");
        }
        if (pseudonymRotationHours() < 0) {
            problems.add("capture.pseudonym-rotation-hours must be >= 0");
        }
        if (intervalSeconds() < 1) {
            problems.add("capture.interval-seconds must be >= 1");
        }
        if (bufferCapacity() < 16) {
            problems.add("capture.buffer-capacity must be >= 16");
        }
        if (maxTotalBytes() < 1024 * 1024) {
            problems.add("capture.max-total-bytes must be >= 1 MiB");
        }
        if (retentionDays() < 1) {
            problems.add("capture.retention-days must be >= 1");
        }
        return problems;
    }

    /** True when the capture is enabled AND fully declared. */
    public boolean ready() {
        return enabled() && problems().isEmpty();
    }
}
