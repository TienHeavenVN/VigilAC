package ac.vigil.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Static sanity validation for typed check configuration. Server-agnostic
 * so it is unit-testable; findings are warnings — the plugin still boots
 * with a misconfigured file (it logs loudly instead of silently misbehaving).
 */
public final class ConfigValidator {

    private ConfigValidator() {
    }

    /**
     * Validates one check's VL ladder. Returns a list of human-readable
     * problems, or an empty list when the ladder is coherent
     * ({@code 0 < alert <= warn <= kick <= max}, with {@code kick <= 0}
     * meaning "kicks disabled for this check", which is legal).
     */
    public static List<String> validateLadder(String checkName, double alert, double warn, double kick, double max) {
        List<String> problems = new ArrayList<>();
        if (!Double.isFinite(alert) || alert <= 0.0D) {
            problems.add(checkName + ".alert-vl must be a positive finite number, got " + alert);
        }
        if (!Double.isFinite(warn) || warn < alert) {
            problems.add(checkName + ".warn-vl must be >= alert-vl (" + alert + "), got " + warn);
        }
        if (!Double.isFinite(kick)) {
            problems.add(checkName + ".kick-vl must be finite, got " + kick);
        } else if (kick > 0.0D && kick < warn) {
            problems.add(checkName + ".kick-vl must be >= warn-vl (" + warn + ") or <= 0 to disable kicks, got " + kick);
        }
        if (!Double.isFinite(max) || max < warn) {
            problems.add(checkName + ".max-vl must be >= warn-vl (" + warn + "), got " + max);
        }
        return problems;
    }

    /**
     * Validates every check ladder in {@code ladders} (check name &rarr;
     * {alert, warn, kick, max}). Returns all problems, empty when coherent.
     */
    public static List<String> validateAll(Map<String, double[]> ladders) {
        List<String> problems = new ArrayList<>();
        if (ladders == null) {
            return problems;
        }
        for (Map.Entry<String, double[]> entry : ladders.entrySet()) {
            double[] ladder = entry.getValue();
            if (ladder == null || ladder.length != 4) {
                problems.add(entry.getKey() + ": expected {alert, warn, kick, max}");
                continue;
            }
            problems.addAll(validateLadder(entry.getKey(), ladder[0], ladder[1], ladder[2], ladder[3]));
        }
        return problems;
    }
}
