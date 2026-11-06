package ac.vigil.data;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns {@link PlayerData} instances. {@link #load(UUID)} is idempotent;
 * {@link #unload(UUID)} must be called on quit to avoid leaks.
 */
public final class PlayerDataManager {

    private final Map<UUID, PlayerData> data = new ConcurrentHashMap<>();

    public PlayerData load(UUID uuid) {
        return data.computeIfAbsent(uuid, PlayerData::new);
    }

    public PlayerData get(UUID uuid) {
        return data.get(uuid);
    }

    public PlayerData unload(UUID uuid) {
        return data.remove(uuid);
    }

    public Collection<PlayerData> getAll() {
        return Collections.unmodifiableCollection(data.values());
    }

    public int getTrackedCount() {
        return data.size();
    }
}
