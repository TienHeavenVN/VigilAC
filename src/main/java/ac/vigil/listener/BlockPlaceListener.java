package ac.vigil.listener;

import java.util.function.Supplier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import ac.vigil.check.CheckManager;
import ac.vigil.check.ViolationPipeline;
import ac.vigil.config.VigilACConfig;
import ac.vigil.data.PlayerData;
import ac.vigil.data.PlayerDataManager;
import ac.vigil.detection.PlaceContext;
import ac.vigil.exempt.ExemptionManager;

/** Feeds completed block placements to the place checks. */
public final class BlockPlaceListener implements Listener {

    private final Supplier<VigilACConfig> config;
    private final PlayerDataManager dataManager;
    private final CheckManager checkManager;
    private final ViolationPipeline pipeline;
    private final ExemptionManager exemptions;

    public BlockPlaceListener(Supplier<VigilACConfig> config,
                              PlayerDataManager dataManager,
                              CheckManager checkManager,
                              ViolationPipeline pipeline,
                              ExemptionManager exemptions) {
        this.config = config;
        this.dataManager = dataManager;
        this.checkManager = checkManager;
        this.pipeline = pipeline;
        this.exemptions = exemptions;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        VigilACConfig cfg = config.get();
        if (player.hasPermission(cfg.exemptPermission())) {
            return;
        }
        if (exemptions.isExempt(player.getUniqueId(), ExemptionManager.ALL)) {
            return;
        }

        org.bukkit.Location loc = player.getLocation();
        org.bukkit.block.Block block = event.getBlockPlaced();

        PlaceContext context = PlaceContext.builder()
                .player(player.getUniqueId())
                .blockType(block.getType().name())
                .block(block.getX(), block.getY(), block.getZ())
                .feet(loc.getX(), loc.getY(), loc.getZ())
                .yaw(loc.getYaw())
                .pitch(loc.getPitch())
                .sprinting(player.isSprinting())
                .sneaking(player.isSneaking())
                .onGround(player.isOnGround())
                .pingMillis(player.getPing())
                .timestamp(System.currentTimeMillis())
                .build();

        PlayerData data = dataManager.load(player.getUniqueId());
        checkManager.evaluatePlace(context, data, pipeline.forPlayer(player, data));
    }
}
