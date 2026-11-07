package ac.vigil.listener;

import java.util.function.Supplier;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import ac.vigil.check.CheckManager;
import ac.vigil.check.ViolationPipeline;
import ac.vigil.config.VigilACConfig;
import ac.vigil.data.PlayerData;
import ac.vigil.data.PlayerDataManager;
import ac.vigil.detection.BreakContext;
import ac.vigil.exempt.ExemptionManager;

/**
 * Feeds completed block breaks to the break checks with the exact tool
 * facts vanilla uses: block hardness, correct-tool classification, the
 * held item's Efficiency level and the haste amplifier.
 */
public final class BlockBreakListener implements Listener {

    private final Supplier<VigilACConfig> config;
    private final PlayerDataManager dataManager;
    private final CheckManager checkManager;
    private final ViolationPipeline pipeline;
    private final ExemptionManager exemptions;

    public BlockBreakListener(Supplier<VigilACConfig> config,
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
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        VigilACConfig cfg = config.get();
        if (player.hasPermission(cfg.exemptPermission())) {
            return;
        }
        if (exemptions.isExempt(player.getUniqueId(), ExemptionManager.ALL)) {
            return;
        }

        Block block = event.getBlock();
        String blockName = block.getType().name();
        float hardness = block.getType().getHardness();

        String toolName = "AIR";
        int efficiency = 0;
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand != null && hand.getType() != Material.AIR) {
            toolName = hand.getType().name();
            efficiency = hand.getEnchantmentLevel(Enchantment.EFFICIENCY);
        }

        int haste = 0;
        for (PotionEffect effect : player.getActivePotionEffects()) {
            if (effect.getType() == PotionEffectType.HASTE) {
                haste = effect.getAmplifier() + 1;
                break;
            }
        }

        BreakContext context = BreakContext.builder()
                .player(player.getUniqueId())
                .blockType(blockName)
                .block(block.getX(), block.getY(), block.getZ())
                .hardness(hardness)
                .correctTool(isCorrectTool(blockName, toolName))
                .efficiency(efficiency)
                .hasteLevel(haste)
                .pingMillis(player.getPing())
                .timestamp(System.currentTimeMillis())
                .build();

        PlayerData data = dataManager.load(player.getUniqueId());
        data.monitor().noteBreak(blockName);
        checkManager.evaluateBreak(context, data, pipeline.forPlayer(player, data));
    }

    /**
     * Heuristic tool-family classification. Unknown blocks and unknown tools
     * default to {@code true} ("correct"), which only makes the computed
     * lower bound shorter — i.e. the check gets quieter, never louder.
     */
    static boolean isCorrectTool(String blockName, String toolName) {
        String block = blockName == null ? "" : blockName;
        String tool = toolName == null ? "" : toolName;
        if ("AIR".equals(tool)) {
            return false; // breaking hard blocks with bare hands is never fast
        }
        boolean pickaxe = tool.contains("PICKAXE");
        boolean axe = tool.contains("_AXE"); // excludes pickaxe/hoe via suffix shape
        boolean shovel = tool.contains("SHOVEL");
        boolean hoe = tool.contains("HOE");

        if (containsAny(block, PICKAXE_BLOCKS)) {
            return pickaxe;
        }
        if (containsAny(block, AXE_BLOCKS)) {
            return axe;
        }
        if (containsAny(block, SHOVEL_BLOCKS)) {
            return shovel;
        }
        if (containsAny(block, HOE_BLOCKS)) {
            return hoe;
        }
        return true; // unknown family: assume correct (quieter, not louder)
    }

    private static boolean containsAny(String block, String[] markers) {
        for (String marker : markers) {
            if (block.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static final String[] PICKAXE_BLOCKS = {
        "STONE", "DEEPSLATE", "BLACKSTONE", "BASALT", "ANDESITE", "DIORITE", "GRANITE",
        "TUFF", "ORE", "ANCIENT_DEBRIS", "TERRACOTTA", "CONCRETE", "BRICK", "OBSIDIAN",
        "NETHERRACK", "PRISMARINE", "PURPUR", "END_STONE", "AMETHYST", "GLOWSTONE",
        "SANDSTONE", "RED_SANDSTONE", "MOSSY_COBBLESTONE", "COBBLED_DEEPSLATE", "SCULK"
    };

    private static final String[] AXE_BLOCKS = {
        "LOG", "WOOD", "STEM", "HYPHAE", "PLANK", "BAMBOO", "FENCE", "DOOR", "TRAPDOOR",
        "SIGN", "CHEST", "BARREL", "CRAFTING_TABLE", "LADDER", "BUTTON", "PRESSURE_PLATE"
    };

    private static final String[] SHOVEL_BLOCKS = {
        "DIRT", "GRASS_BLOCK", "PODZOL", "MYCELIUM", "ROOTED_DIRT", "COARSE_DIRT",
        "SAND", "GRAVEL", "CLAY", "SOUL_SAND", "SOUL_SOIL", "SNOW", "MUD", "SOD"
    };

    private static final String[] HOE_BLOCKS = {
        "LEAVES", "HAY_BLOCK", "WART_BLOCK", "MOSS_BLOCK", "DRIED_KELP", "SHROOMLIGHT"
    };
}
