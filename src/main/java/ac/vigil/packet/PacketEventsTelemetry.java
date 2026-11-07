package ac.vigil.packet;

import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.plugin.java.JavaPlugin;
import ac.vigil.data.PlayerData;
import ac.vigil.data.PlayerDataManager;
import ac.vigil.monitor.EvidenceType;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.event.UserConnectEvent;
import com.github.retrooper.packetevents.event.UserDisconnectEvent;
import com.github.retrooper.packetevents.event.UserLoginEvent;
import com.github.retrooper.packetevents.event.PacketEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.InteractAction;
import com.github.retrooper.packetevents.protocol.world.Location;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.util.Vector3i;

/**
 * Structured packet telemetry fed into the per-player trackers
 * ({@code attackAttemptLedger}, {@code swingTracker}, {@code rotationTracker},
 * {@code diggingTracker}). This class and its nested listener reference the
 * packetevents API directly, so it must only be loaded when the packetevents
 * plugin is present — {@link PacketEventsHook} guarantees that.
 *
 * <h2>Threading and cost model</h2>
 * <ul>
 *   <li>packetevents fires these callbacks on the netty event loop. The
 *       listener performs O(1) synchronized tracker updates and one monitor
 *       evidence call — no Bukkit API, no world access, no I/O, no allocation
 *       beyond small (immutable) value construction.</li>
 *   <li>All per-player structures are fixed-capacity; nothing grows without
 *       bound. Evidence entries flow into the existing 256-entry monitor ring.</li>
 * </ul>
 *
 * <h2>Lifecycle</h2>
 * Registered and unregistered together with {@link PacketEventsMetrics}
 * (see {@link PacketEventsHook}). Unregister on plugin disable so a reload
 * never double-counts attempts or corrupts dig bookkeeping.
 *
 * <p>API surface verified against packetevents 2.14.0 (the repository's pinned
 * version), not against master.</p>
 */
final class PacketEventsTelemetry {

    private PacketEventsTelemetry() {
    }

    static Object register(JavaPlugin plugin, PlayerDataManager dataManager, String version) {
        return PacketEvents.getAPI()
                .getEventManager()
                .registerListener(new TelemetryListener(dataManager, plugin.getLogger()), PacketListenerPriority.NORMAL);
    }

    static void unregister(Object registeredListener) {
        if (registeredListener == null) {
            return;
        }
        try {
            PacketEvents.getAPI().getEventManager()
                    .unregisterListener((com.github.retrooper.packetevents.event.PacketListenerCommon) registeredListener);
        } catch (Throwable throwable) {
            // packetevents itself is already unloading (or gone): nothing to unregister from.
        }
    }

    public static final class TelemetryListener implements PacketListener {

        private final PlayerDataManager dataManager;
        private final Logger logger;
        private boolean warned;

        TelemetryListener(PlayerDataManager dataManager, Logger logger) {
            this.dataManager = dataManager;
            this.logger = logger;
        }

        @Override
        public void onPacketReceive(PacketReceiveEvent event) {
            try {
                // Avoid unconditional allocation: compare by reference, per packet.
                Object rawType = event.getPacketType();
                if (rawType == PacketType.Play.Client.ATTACK
                        || rawType == PacketType.Play.Client.INTERACT_ENTITY
                        || rawType == PacketType.Play.Client.ANIMATION
                        || rawType == PacketType.Play.Client.PUNCH
                        || rawType == PacketType.Play.Client.PLAYER_DIGGING
                        || rawType == PacketType.Play.Client.PLAYER_FLYING
                        || rawType == PacketType.Play.Client.PLAYER_POSITION
                        || rawType == PacketType.Play.Client.PLAYER_ROTATION
                        || rawType == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION) {
                    var user = event.getUser();
                    UUID uuid = user == null ? null : user.getUUID();
                    if (uuid == null) {
                        return;
                    }
                    PlayerData data = dataManager.get(uuid);
                    if (data == null) {
                        return;
                    }
                    long now = System.currentTimeMillis();
                    if (rawType == PacketType.Play.Client.ATTACK) {
                        int entityId = new com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAttack(event).getEntityId();
                        data.attackAttemptLedger().noteAttempt(now, entityId);
                        data.monitor().noteAttackAttempt("entity=" + entityId + " src=ATTACK");
                    } else if (rawType == PacketType.Play.Client.INTERACT_ENTITY) {
                        WrapperPlayClientInteractEntity interact = new WrapperPlayClientInteractEntity(event);
                        if (interact.getAction() == InteractAction.ATTACK) {
                            data.attackAttemptLedger().noteAttempt(now, interact.getEntityId());
                            data.monitor().noteAttackAttempt("entity=" + interact.getEntityId() + " src=INTERACT_ENTITY");
                        }
                    } else if (rawType == PacketType.Play.Client.ANIMATION || rawType == PacketType.Play.Client.PUNCH) {
                        data.swingTracker().noteSwing(now);
                    } else if (rawType == PacketType.Play.Client.PLAYER_DIGGING) {
                        handleDigging(data, event, now);
                    } else {
                        handleFlying(data, event, now);
                    }
                }
            } catch (Throwable throwable) {
                if (!warned) {
                    warned = true;
                    logger.warning("packetevents telemetry failed once (further failures suppressed): " + throwable);
                }
            }
        }

        private void handleDigging(PlayerData data, PacketReceiveEvent event, long now) {
            WrapperPlayClientPlayerDigging digging = new WrapperPlayClientPlayerDigging(event);
            Vector3i pos = digging.getBlockPosition();
            switch (digging.getAction()) {
                case START_DIGGING:
                    data.diggingTracker().start(pos.getX(), pos.getY(), pos.getZ(), now);
                    break;
                case CANCELLED_DIGGING:
                    data.diggingTracker().abort(now);
                    break;
                case FINISHED_DIGGING:
                    data.diggingTracker().complete(now);
                    break;
                case CHANGE_DESTROY_DIRECTION:
                    data.diggingTracker().noteDirectionChange();
                    break;
                case STAB:
                default:
                    // No dig-lifecycle meaning for our purposes.
                    break;
            }
        }

        private void handleFlying(PlayerData data, PacketReceiveEvent event, long now) {
            // Only rotation-carrying packets feed the rotation stream; use the flying
            // base wrapper because PLAYER_FLYING/ROTATION/POSITION* differ in subtype,
            // not in the rotation accessors we need.
            WrapperPlayClientPlayerFlying flying = new WrapperPlayClientPlayerFlying(event);
            if (!flying.hasRotationChanged()) {
                return;
            }
            Location location = flying.getLocation();
            if (location == null) {
                return;
            }
            data.rotationTracker().sample(now, location.getYaw(), location.getPitch());
        }

        @Override
        public void onPacketSend(PacketSendEvent event) {
        }

        @Override
        public void onUserConnect(UserConnectEvent event) {
        }

        @Override
        public void onUserLogin(UserLoginEvent event) {
        }

        @Override
        public void onUserDisconnect(UserDisconnectEvent event) {
            // State lives in PlayerData and dies with it; nothing to clean here.
        }

        @Override
        public void onPacketEventExternal(PacketEvent event) {
        }
    }
}
