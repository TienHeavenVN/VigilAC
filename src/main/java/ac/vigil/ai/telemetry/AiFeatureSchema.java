package ac.vigil.ai.telemetry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Fixed, ordered schema of every feature the VigilAC AI module extracts. The
 * schema is part of the model contract: an {@code IsolationForestModel}
 * records the SHA-256 of the name list and refuses to load against a different
 * schema (see {@link #schemaHash()}).
 *
 * <p>All features are computed from existing, already-collected telemetry —
 * this module never collects personal information (no chat, no inventory
 * contents, no IP addresses, no world seeds). Values are clipped to the
 * documented ranges by the extractor; anything outside a range or NaN is
 * rejected by the validator so a bad sample can never reach a model.</p>
 */
public final class AiFeatureSchema {

    /** Feature names in extraction order. Do not reorder; append only with a schema hash bump. */
    public static final String[] NAMES = {
        // combat timing and cadence (source: CombatTracker, SwingTracker)
        "combat.cps_1s",
        "combat.cps_5s",
        "combat.click_interval_cv",
        "combat.click_interval_mean_ms",
        "combat.click_interval_stddev_ms",
        "combat.click_interval_entropy_bits",
        "combat.distinct_targets_10s",
        "combat.swing_rate_1s",
        // rotation dynamics (source: RotationTracker)
        "rotation.angular_velocity_dps_1s",
        "rotation.max_delta_deg_1s",
        "rotation.window_samples",
        // reach statistics (source: PlayerData reach EMA, ReachEvidenceLedger users)
        "reach.ema_blocks",
        "reach.sample_count",
        // packet timing (source: PlayerData packet counters, MovementTracker)
        "packet.pps",
        "packet.raw_move_pps",
        "packet.move_pps",
        "movement.airborne_ms",
        // mining (source: BlockActionTracker)
        "break.interval_ms_clipped",
        "break.impossible_streak",
        // server-side context (source: scheduler/Bukkit, passed in as AiContext)
        "context.ping_ms",
        "context.tps_1m",
        "context.on_ground"
    };

    /** Valid inclusive ranges [lo, hi] per feature index; used by {@code FeatureValidator}. */
    private static final double[][] RANGES = {
        {0.0D, 50.0D},   // combat.cps_1s
        {0.0D, 50.0D},   // combat.cps_5s
        {0.0D, 5.0D},    // combat.click_interval_cv
        {0.0D, 10_000.0D}, // combat.click_interval_mean_ms
        {0.0D, 10_000.0D}, // combat.click_interval_stddev_ms
        {0.0D, 16.0D},   // combat.click_interval_entropy_bits
        {0.0D, 32.0D},   // combat.distinct_targets_10s
        {0.0D, 50.0D},   // combat.swing_rate_1s
        {0.0D, 5_000.0D}, // rotation.angular_velocity_dps_1s
        {0.0D, 180.0D},  // rotation.max_delta_deg_1s
        {0.0D, 64.0D},   // rotation.window_samples
        {0.0D, 8.0D},    // reach.ema_blocks
        {0.0D, 100_000.0D}, // reach.sample_count
        {0.0D, 400.0D},  // packet.pps
        {0.0D, 400.0D},  // packet.raw_move_pps
        {0.0D, 400.0D},  // packet.move_pps
        {0.0D, 3_600_000.0D}, // movement.airborne_ms
        {0.0D, 60_000.0D},  // break.interval_ms_clipped (unknown => 0)
        {0.0D, 100.0D},  // break.impossible_streak
        {0.0D, 5_000.0D}, // context.ping_ms
        {0.0D, 20.0D},   // context.tps_1m
        {0.0D, 1.0D},    // context.on_ground
    };

    public static final int FEATURE_COUNT = NAMES.length;

    private static final String SCHEMA_HASH;

    static {
        SCHEMA_HASH = sha256Hex(String.join(",", NAMES));
    }

    private AiFeatureSchema() {
    }

    public static int size() {
        return FEATURE_COUNT;
    }

    public static String nameAt(int index) {
        return NAMES[index];
    }

    /** Inclusive valid range for feature {@code index}: {@code {lo, hi}}. */
    public static double[] rangeAt(int index) {
        return RANGES[index].clone();
    }

    /** Full name list, defensive copy. */
    public static String[] names() {
        return NAMES.clone();
    }

    /** SHA-256 of the ordered, comma-joined feature names (hex). Stable across runs and platforms. */
    public static String schemaHash() {
        return SCHEMA_HASH;
    }

    public static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
