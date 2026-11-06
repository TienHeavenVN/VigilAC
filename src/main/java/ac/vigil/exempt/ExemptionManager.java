package ac.vigil.exempt;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player, per-check exemptions granted via {@code /vigilac exempt}.
 * A {@code null}-vs-empty distinction is kept: an entry with
 * {@code ExemptionManager.ALL} exempts the player from every check.
 */
public final class ExemptionManager {

    public static final String ALL = "*";

    private final Map<UUID, Set<String>> exemptions = new ConcurrentHashMap<>();

    /** Toggles an exemption; returns true if the player is now exempt. */
    public boolean toggle(UUID uuid, String checkName) {
        Set<String> set = exemptions.computeIfAbsent(uuid, id -> ConcurrentHashMap.newKeySet());
        boolean added;
        if (set.contains(checkName)) {
            set.remove(checkName);
            added = false;
        } else {
            set.add(checkName);
            added = true;
        }
        if (set.isEmpty()) {
            exemptions.remove(uuid, set);
        }
        return added;
    }

    public boolean isExempt(UUID uuid, String checkName) {
        Set<String> set = exemptions.get(uuid);
        if (set == null) {
            return false;
        }
        return set.contains(ALL) || set.contains(checkName);
    }

    public Set<String> getExemptions(UUID uuid) {
        Set<String> set = exemptions.get(uuid);
        return set == null ? Collections.emptySet() : Collections.unmodifiableSet(set);
    }

    public void clear(UUID uuid) {
        exemptions.remove(uuid);
    }
}
