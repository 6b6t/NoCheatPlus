/*
 * This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package fr.neatmonster.nocheatplus.compat.bukkit.model;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.ShulkerBox;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.util.BoundingBox;

import fr.neatmonster.nocheatplus.compat.Bridge1_13;
import fr.neatmonster.nocheatplus.utilities.map.BlockCache;

public class BukkitShulkerBox implements BukkitShapeModel {

    private static final double[] FULL_BLOCK = {0.0, 0.0, 0.0, 1.0, 1.0, 1.0};

    /** A vanilla lid reaches at most half a block out of the box (0.5 * progress, 0.05 per tick). */
    static final double MAX_LID = 0.5;
    /** Below this the lid counts as closed (float noise of the server's AABB). */
    private static final double CLOSED_EPSILON = 0.0001;

    @Override
    public double[] getShape(final BlockCache blockCache, 
            final World world, final int x, final int y, final int z) {

        final Block block = world.getBlockAt(x, y, z);
        BlockFace face = BlockFace.UP;
        final BlockData blockData = block.getBlockData();
        if (blockData instanceof Directional) {
            face = ((Directional) blockData).getFacing();
        }
        if (face != BlockFace.UP && Bridge1_13.hasBoundingBox()) {
            // Sideways and downward lids follow their physical opening/closing bounds.
            return lidShape(block.getBoundingBox(), x, y, z);
        }
        // Upward lids retain staged v4 viewer-based behavior, as does the legacy API fallback.
        final BlockState state = block.getState();
        if (!(state instanceof ShulkerBox)) {
            return FULL_BLOCK;
        }
        if (((ShulkerBox) state).getInventory().getViewers().isEmpty()) {
            return FULL_BLOCK;
        }
        switch (face) {
            case DOWN:
                return new double[] {0.0, -0.5, 0.0, 1.0, 1.0, 1.0};
            case NORTH:
                return new double[] {0.0, 0.0, -0.5, 1.0, 1.0, 1.0};
            case SOUTH:
                return new double[] {0.0, 0.0, 0.0, 1.0, 1.0, 1.5};
            case WEST:
                return new double[] {-0.5, 0.0, 0.0, 1.0, 1.0, 1.0};
            case EAST:
                return new double[] {0.0, 0.0, 0.0, 1.5, 1.0, 1.0};
            case UP:
            default:
                return new double[] {0.0, 0.0, 0.0, 1.0, 1.5, 1.0};
        }
    }

    /**
     * The box with its lid as far as it is out, from the block's bounding box in world coordinates. Each side may
     * reach out by at most {@link #MAX_LID}; anything else that isn't a box plus a lid (no shape, a box not around the
     * block) is the closed box.
     */
    public static double[] lidShape(final BoundingBox box, final int x, final int y, final int z) {
        if (box == null) {
            return FULL_BLOCK;
        }
        final double[] rel = {box.getMinX() - x, box.getMinY() - y, box.getMinZ() - z,
                box.getMaxX() - x, box.getMaxY() - y, box.getMaxZ() - z};
        boolean lidOut = false;
        for (int i = 0; i < 3; i++) {
            // The box must hold the whole block: a lid only adds to it.
            if (!(rel[i] <= CLOSED_EPSILON) || !(rel[i + 3] >= 1.0 - CLOSED_EPSILON)) {
                return FULL_BLOCK;
            }
            rel[i] = rel[i] < -CLOSED_EPSILON ? Math.max(-MAX_LID, rel[i]) : 0.0;
            rel[i + 3] = rel[i + 3] > 1.0 + CLOSED_EPSILON ? Math.min(1.0 + MAX_LID, rel[i + 3]) : 1.0;
            lidOut |= rel[i] != 0.0 || rel[i + 3] != 1.0;
        }
        return lidOut ? rel : FULL_BLOCK;
    }

    @Override
    public int getFakeData(final BlockCache blockCache, 
            final World world, final int x, final int y, final int z) {
        return 0;
    }

}
