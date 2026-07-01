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
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.PointedDripstone;

import fr.neatmonster.nocheatplus.utilities.location.LocUtil;
import fr.neatmonster.nocheatplus.utilities.map.BlockCache;

public class BukkitDripStone implements BukkitShapeModel {

    private static final double[] TIP_MERGE = {0.3125, 0.0, 0.3125, 0.6875, 1.0, 0.6875};
    private static final double[] TIP_DOWN = {0.3125, 0.3125, 0.3125, 0.6875, 1.0, 0.6875};
    private static final double[] TIP_UP = {0.3125, 0.0, 0.3125, 0.6875, 0.6875, 0.6875};
    private static final double[] FRUSTUM = {0.25, 0.0, 0.25, 0.75, 1.0, 0.75};
    private static final double[] MIDDLE = {0.1875, 0.0, 0.1875, 0.8125, 1.0, 0.8125};
    private static final double[] BASE = {0.125, 0.0, 0.125, 0.875, 1.0, 0.875};

    @Override
    public double[] getShape(BlockCache blockCache, World world, int x, int y, int z) {
        final Block block = world.getBlockAt(x, y, z);
        final BlockData blockData = block.getBlockData();
        if (blockData instanceof PointedDripstone) {
            boolean bedrock = blockCache.getPlayerData() != null && blockCache.getPlayerData().isBedrockPlayer();
            final long randomSeed = bedrock ? LocUtil.randomSeedBedrock(x, 0, z) : LocUtil.randomSeedJava(x, 0, z);
            final double xOffset = clamp(calcOffset(randomSeed, false, bedrock), -0.125, 0.125);
            final double zOffset = clamp(calcOffset(randomSeed, true, bedrock), -0.125, 0.125);
            PointedDripstone dripstone = (PointedDripstone) blockData;
            switch (dripstone.getThickness()) {
                case TIP_MERGE:
                    return offset(TIP_MERGE, xOffset, 0.0, zOffset);
                case TIP:
                    return dripstone.getVerticalDirection() == BlockFace.DOWN
                            ? offset(TIP_DOWN, xOffset, 0.0, zOffset)
                            : offset(TIP_UP, xOffset, 0.0, zOffset);
                case FRUSTUM:
                    return offset(FRUSTUM, xOffset, 0.0, zOffset);
                case MIDDLE:
                    return offset(MIDDLE, xOffset, 0.0, zOffset);
                default:
                    return offset(BASE, xOffset, 0.0, zOffset);
            }
        }
        return new double[] {0.0, 0.0, 0.0, 1.0, 1.0, 1.0};
    }

    @Override
    public int getFakeData(BlockCache blockCache, World world, int x, int y, int z) {
        return 0;
    }

    private float calcOffset(long value, boolean shift, boolean bedrock) {
        if (shift) {
            value >>= 8;
        }
        value &= 0xFL;
        float offset = value;
        if (bedrock) {
            offset *= 0.0333333f;
            offset -= 0.25f;
        } else {
            offset /= 15f;
            offset -= 0.5f;
            offset *= 0.5f;
        }
        return offset;
    }

    private double clamp(double input, double min, double max) {
        return input < min ? min : Math.min(input, max);
    }

    private double[] offset(double[] input, double x, double y, double z) {
        return new double[] {
                input[0] + x, input[1] + y, input[2] + z,
                input[3] + x, input[4] + y, input[5] + z
        };
    }
}
