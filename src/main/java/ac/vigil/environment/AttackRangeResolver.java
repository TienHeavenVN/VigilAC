package ac.vigil.environment;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * Best-effort reader for the held item's interaction-range data component
 * (1.21.11+ spears override vanilla's 3.0-block default for
 * <em>entities</em> via the {@code attack_range} item component).
 *
 * <h2>Honesty contract</h2>
 * <ul>
 *   <li>This resolver <b>never invents a range</b>. It either reads a value
 *       off the item stack (via reflective access to the Paper data-component
 *       API) or reports {@link Source#UNKNOWN} — in which case the configured
 *       reach cap applies (conservative for false positives on servers ≤ 1.21.10
 *       and for spears on 1.21.11+ until the read path is verified).</li>
 *   <li>The Paper 26.x data-component API surface cannot be compiled against
 *       here (this plugin is compiled against the pinned 1.21.8 API), so all
 *       access is reflective and guarded. Any shape mismatch — wrong method
 *       names, missing class, unexpected component type — collapses to
 *       {@link Source#UNKNOWN} and is logged at most once per plugin run.</li>
 *   <li>Reads returning non-finite or nonsensical values (outside
 *       [1.0, 8.0] blocks) are discarded as UNKNOWN.</li>
 * </ul>
 *
 * <p>Status: the decision layer is unit-tested (Source.UNKNOWN vs COMPONENT
 * plumbing through ReachCheck); the reflective read itself is
 * <b>UNVERIFIED against a live 26.x server</b> — see docs/PHASE_7_BLOCKERS.md.</p>
 */
public final class AttackRangeResolver {

    public enum Source {
        /** A value was read from the item's data components. */
        COMPONENT,
        /** No reliable range available; configured cap applies. */
        UNKNOWN
    }

    /** Immutable resolution outcome. */
    public static final class Result {
        private final Double maxRange;
        private final Source source;

        private Result(Double maxRange, Source source) {
            this.maxRange = maxRange;
            this.source = source;
        }

        public Double getMaxRange() { return maxRange; }
        public Source getSource() { return source; }

        public static Result component(double maxRange) {
            return new Result(maxRange, Source.COMPONENT);
        }

        public static Result unknown() {
            return new Result(null, Source.UNKNOWN);
        }
    }

    private static final java.util.concurrent.atomic.AtomicBoolean warned = new java.util.concurrent.atomic.AtomicBoolean();

    /** Resolves the item's max interaction range, or UNKNOWN. Never throws. */
    public static Result resolve(ItemStack hand) {
        if (hand == null || hand.getType() == Material.AIR) {
            return Result.unknown();
        }
        try {
            Object components = tryNoArg(hand, "getComponents");
            if (components == null) {
                return Result.unknown();
            }
            Object component = readAttackRangeComponent(components);
            Double maxRange = asMaxRange(component);
            if (maxRange != null && maxRange >= 1.0D && maxRange <= 8.0D) {
                return Result.component(maxRange);
            }
            return Result.unknown();
        } catch (Throwable throwable) {
            if (warned.compareAndSet(false, true)) {
                try {
                    org.bukkit.Bukkit.getLogger().warning(
                            "[VigilAC] attack_range component read failed (reach falls back to configured cap; cause logged once): "
                                    + throwable);
                } catch (Throwable ignored) {
                    // logger itself unavailable: stay silent
                }
            }
            return Result.unknown();
        }
    }

    private static Object readAttackRangeComponent(Object components) {
        // Shape 1: components.get(<ComponentType) / components.get("attack_range").
        for (java.lang.reflect.Method m : components.getClass().getMethods()) {
            if (!m.getName().equals("get")) {
                continue;
            }
            Class<?>[] params = m.getParameterTypes();
            if (params.length != 1) {
                continue;
            }
            try {
                if (params[0] == String.class) {
                    Object result = m.invoke(components, "attack_range");
                    if (result != null) {
                        return result;
                    }
                    result = m.invoke(components, "ATTACK_RANGE");
                    if (result != null) {
                        return result;
                    }
                } else if (Enum.class.isAssignableFrom(params[0])) {
                    for (String enumName : new String[]{"ATTACK_RANGE", "attack_range"}) {
                        try {
                            @SuppressWarnings({"unchecked", "rawtypes"})
                            Object constant = Enum.valueOf((Class<Enum>) params[0], enumName);
                            Object result = m.invoke(components, constant);
                            if (result != null) {
                                return result;
                            }
                        } catch (Throwable ignored) {
                            // not this enum constant
                        }
                    }
                } else {
                    // Shape 2: components.get(ComponentType.ATTACK_RANGE) where the
                    // type is a token object, e.g. a static field on its class or an
                    // enum-like data-component singleton.
                    Object fieldValue = staticAttackRangeToken(params[0]);
                    if (fieldValue != null) {
                        Object result = m.invoke(components, fieldValue);
                        if (result != null) {
                            return result;
                        }
                    }
                }
            } catch (Throwable ignored) {
                // keep trying other candidates
            }
        }
        return null;
    }

    private static Object staticAttackRangeToken(Class<?> type) {
        for (String name : new String[]{"ATTACK_RANGE", "attackRange", "ATTACK_RANGE_COMPONENT"}) {
            try {
                java.lang.reflect.Field field = type.getField(name);
                Object value = field.get(null);
                if (value != null && type.isInstance(value)) {
                    return value;
                }
            } catch (Throwable ignored) {
                // try the next candidate
            }
        }
        return null;
    }

    private static Double asMaxRange(Object component) {
        if (component == null) {
            return null;
        }
        for (String accessor : new String[]{"getMaxRange", "getMaxReach", "maxRange", "maxReach"}) {
            Object value = tryNoArg(component, accessor);
            Double parsed = asDouble(value);
            if (parsed != null) {
                return parsed;
            }
        }
        for (String fieldName : new String[]{"max_range", "maxRange", "max_reach", "maxReach"}) {
            try {
                java.lang.reflect.Field field = component.getClass().getDeclaredField(fieldName);
                field.setAccessible(true);
                Double parsed = asDouble(field.get(component));
                if (parsed != null) {
                    return parsed;
                }
            } catch (Throwable ignored) {
                // try next field
            }
        }
        return null;
    }

    private static Object tryNoArg(Object target, String method) {
        try {
            return target.getClass().getMethod(method).invoke(target);
        } catch (Throwable throwable) {
            return null;
        }
    }

    private static Double asDouble(Object value) {
        return value instanceof Number ? ((Number) value).doubleValue() : null;
    }

    private AttackRangeResolver() {
    }
}
