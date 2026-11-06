package ac.vigil.check;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ac.vigil.data.PlayerData;
import ac.vigil.detection.AttackContext;
import ac.vigil.detection.BreakContext;
import ac.vigil.detection.DetectionContext;
import ac.vigil.detection.PlaceContext;

/**
 * Registry and dispatcher for all checks. Thread-safe for reads after
 * registration; registration happens once at plugin boot.
 */
public final class CheckManager {

    private final Map<String, Check<?>> checks = new LinkedHashMap<>();
    private final List<Check<DetectionContext>> movementChecks = new ArrayList<>();
    private final List<Check<AttackContext>> combatChecks = new ArrayList<>();
    private final List<Check<PlaceContext>> placeChecks = new ArrayList<>();
    private final List<Check<BreakContext>> breakChecks = new ArrayList<>();

    /** Registers a check; throws on duplicate names to fail fast at boot. */
    public <C> void register(Check<C> check) {
        if (checks.containsKey(check.getName())) {
            throw new IllegalStateException("Duplicate check name: " + check.getName());
        }
        checks.put(check.getName(), check);
        if (check.getCategory() == CheckCategory.MOVEMENT) {
            @SuppressWarnings("unchecked")
            Check<DetectionContext> movement = (Check<DetectionContext>) check;
            movementChecks.add(movement);
        } else if (check.getCategory() == CheckCategory.COMBAT) {
            @SuppressWarnings("unchecked")
            Check<AttackContext> combat = (Check<AttackContext>) check;
            combatChecks.add(combat);
        }
    }

    /** Registers a block-placement check (Player-category). */
    public void registerPlace(Check<PlaceContext> check) {
        if (checks.containsKey(check.getName())) {
            throw new IllegalStateException("Duplicate check name: " + check.getName());
        }
        checks.put(check.getName(), check);
        placeChecks.add(check);
    }

    /** Registers a block-break check (Player-category). */
    public void registerBreak(Check<BreakContext> check) {
        if (checks.containsKey(check.getName())) {
            throw new IllegalStateException("Duplicate check name: " + check.getName());
        }
        checks.put(check.getName(), check);
        breakChecks.add(check);
    }

    /** Runs all registered movement checks for one move observation. */
    public void evaluateMovement(DetectionContext context, PlayerData data, ViolationCallback callback) {
        for (Check<DetectionContext> check : movementChecks) {
            try {
                check.evaluate(context, data, callback);
            } catch (RuntimeException ex) {
                // A faulty check must never crash the movement pipeline.
                callback.onViolation(check, 0.0D, "error:" + ex.getClass().getSimpleName());
            }
        }
    }

    /** Runs all registered combat checks for one attack observation. */
    public void evaluateAttack(AttackContext context, PlayerData data, ViolationCallback callback) {
        for (Check<AttackContext> check : combatChecks) {
            try {
                check.evaluate(context, data, callback);
            } catch (RuntimeException ex) {
                callback.onViolation(check, 0.0D, "error:" + ex.getClass().getSimpleName());
            }
        }
    }

    /** Runs all registered block-placement checks for one observation. */
    public void evaluatePlace(PlaceContext context, PlayerData data, ViolationCallback callback) {
        for (Check<PlaceContext> check : placeChecks) {
            try {
                check.evaluate(context, data, callback);
            } catch (RuntimeException ex) {
                callback.onViolation(check, 0.0D, "error:" + ex.getClass().getSimpleName());
            }
        }
    }

    /** Runs all registered block-break checks for one observation. */
    public void evaluateBreak(BreakContext context, PlayerData data, ViolationCallback callback) {
        for (Check<BreakContext> check : breakChecks) {
            try {
                check.evaluate(context, data, callback);
            } catch (RuntimeException ex) {
                callback.onViolation(check, 0.0D, "error:" + ex.getClass().getSimpleName());
            }
        }
    }

    public List<Check<?>> getAll() {
        return Collections.unmodifiableList(new ArrayList<>(checks.values()));
    }

    public Check<?> getByName(String name) {
        return checks.get(name == null ? "" : name.toLowerCase(java.util.Locale.ROOT));
    }

    public int getCount() {
        return checks.size();
    }
}
