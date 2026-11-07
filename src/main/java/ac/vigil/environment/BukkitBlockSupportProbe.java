package ac.vigil.environment;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Main-thread Bukkit implementation of {@link BlockSupportProbe}.
 *
 * <p>Probes the four corner columns of the feet box at the two block layers
 * touching the feet ({@code floor(y-1)} and {@code floor(y)}). Counting the
 * feet layer itself as support makes the probe conservative: it can only
 * exonerate, never convict, a player lodged half a block into a block.
 * Chunk-unload bailout: if any probed chunk is not loaded, support is
 * assumed (the check must never flag what it cannot see).</p>
 */
public final class BukkitBlockSupportProbe implements BlockSupportProbe {

    private static final double[][] CORNERS = {
            {-0.3D, -0.3D},
            {-0.3D, 0.3D},
            {0.3D, -0.3D},
            {0.3D, 0.3D}
    };

    @Override
    public boolean hasSupportBeneath(String worldName, double x, double y, double z) {
        try {
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                return true; // cannot judge: never accuse
            }
            int lowerY = (int) Math.floor(y) - 1;
            int upperY = (int) Math.floor(y);
            for (double[] corner : CORNERS) {
                int bx = (int) Math.floor(x + corner[0]);
                int bz = (int) Math.floor(z + corner[1]);
                if (!world.isChunkLoaded(bx >> 4, bz >> 4)) {
                    return true; // chunk unloaded: cannot judge, assumed supported
                }
                if (isSolidSafe(world, bx, lowerY, bz) || isSolidSafe(world, bx, upperY, bz)) {
                    return true;
                }
            }
            return false;
        } catch (Throwable throwable) {
            // Any world/edge failure: assume supported. A missed detection is
            // a false negative; a false accusation is worse.
            return true;
        }
    }

    private static boolean isSolidSafe(World world, int x, int y, int z) {
        Block block = world.getBlockAt(x, y, z);
        try {
            return block.getType().isSolid();
        } catch (Throwable throwable) {
            return true; // unreadable block: treat as support
        }
    }
}
