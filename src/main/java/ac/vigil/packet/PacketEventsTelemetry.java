package ac.vigil.packet;

import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.plugin.java.JavaPlugin;
import ac.vigil.data.PlayerData;
import ac.vigil.data.PlayerDataManager;
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

 * <p>Attack attempts (ATTACK / INTERACT_ENTITY with ATTACK action) additionally
 * produce an {@link AttackAttemptSnapshot} handed to the {@link AttackAttemptSink}
 * (bounded, non-blocking, netty-safe) when one was provided; geometry resolution
 * happens on the server thread.</p>
 *
 * <h2>Threading and cost model</h2>
 * <ul>
 *   <li>packetevents fires these callbacks on the netty event loop. The
 *       listener performs O(1) synchronized tracker updates and one monitor
 *       evidence call — no Bukkit API, no world access, no I/O. Only
 *       short-lived packetevents wrapper objects are allocated per packet
 *       (flying/dig/attack); trackers themselves store primitives in fixed
 *       rings.</li>
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
        return register(plugin, dataManager, version, null);
    }

    static Object register(JavaPlugin plugin, PlayerDataManager dataManager, String version, AttackAttemptSink attackAttemptSink) {
        return PacketEvents.getAPI()
                .getEventManager()
                .registerListener(new TelemetryListener(dataManager, plugin.getLogger(), attackAttemptSink), PacketListenerPriority.NORMAL);
    }

    /** Submits the attempt snapshot plus the latest known rotation; called on netty threads. */
    private static void submitAttackAttempt(AttackAttemptSink sink, PlayerData data,
                                            java.util.UUID attacker, int entityId,
                                            long now, String source) {
        if (sink == null) {
            return;
        }
        float[] rotation = data.rotationTracker().latestSample();
        AttackAttemptSnapshot snapshot;
        if (rotation != null) {
            snapshot = new AttackAttemptSnapshot(attacker, entityId, now, true, rotation[0], rotation[1], source);
        } else {
            snapshot = AttackAttemptSnapshot.of(attacker, entityId, now, source);
        }
        sink.submit(snapshot);
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
        private final AttackAttemptSink attackAttemptSink;
        private boolean warned;

        TelemetryListener(PlayerDataManager dataManager, Logger logger, AttackAttemptSink attackAttemptSink) {
            this.dataManager = dataManager;
            this.logger = logger;
            this.attackAttemptSink = attackAttemptSink;
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
                        submitAttackAttempt(attackAttemptSink, data, uuid, entityId, now, "ATTACK");
                    } else if (rawType == PacketType.Play.Client.INTERACT_ENTITY) {
                        WrapperPlayClientInteractEntity interact = new WrapperPlayClientInteractEntity(event);
                        if (interact.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                            data.attackAttemptLedger().noteAttempt(now, interact.getEntityId());
                            data.monitor().noteAttackAttempt("entity=" + interact.getEntityId() + " src=INTERACT_ENTITY");
                            submitAttackAttempt(attackAttemptSink, data, uuid, interact.getEntityId(), now, "INTERACT_ENTITY");
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
