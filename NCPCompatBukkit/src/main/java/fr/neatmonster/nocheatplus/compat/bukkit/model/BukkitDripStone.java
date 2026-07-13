/*
 * This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 */
package fr.neatmonster.nocheatplus.compat.bukkit.model;

import org.bukkit.World;
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
    public double[] getShape(final BlockCache blockCache, final World world,
            final int x, final int y, final int z) {
        final BlockData blockData = world.getBlockAt(x, y, z).getBlockData();
        if (!(blockData instanceof PointedDripstone)) {
            return new double[] {0.0, 0.0, 0.0, 1.0, 1.0, 1.0};
        }
        final boolean bedrock = blockCache.isBedrockCache();
        final long seed = bedrock
                ? LocUtil.randomSeedBedrock(x, 0, z)
                : LocUtil.randomSeedJava(x, 0, z);
        final double xOffset = clamp(calculateOffset(seed, false, bedrock), -0.125, 0.125);
        final double zOffset = clamp(calculateOffset(seed, true, bedrock), -0.125, 0.125);
        final PointedDripstone dripstone = (PointedDripstone) blockData;
        switch (dripstone.getThickness()) {
            case TIP_MERGE:
                return offset(TIP_MERGE, xOffset, zOffset);
            case TIP:
                return offset(dripstone.getVerticalDirection() == BlockFace.DOWN ? TIP_DOWN : TIP_UP,
                        xOffset, zOffset);
            case FRUSTUM:
                return offset(FRUSTUM, xOffset, zOffset);
            case MIDDLE:
                return offset(MIDDLE, xOffset, zOffset);
            default:
                return offset(BASE, xOffset, zOffset);
        }
    }

    @Override
    public int getFakeData(final BlockCache blockCache, final World world,
            final int x, final int y, final int z) {
        return 0;
    }

    private static float calculateOffset(long value, final boolean shift, final boolean bedrock) {
        if (shift) {
            value >>= 8;
        }
        float offset = value & 0xFL;
        if (bedrock) {
            return offset * 0.0333333f - 0.25f;
        }
        return (offset / 15f - 0.5f) * 0.5f;
    }

    private static double clamp(final double value, final double min, final double max) {
        return value < min ? min : Math.min(value, max);
    }

    private static double[] offset(final double[] bounds, final double x, final double z) {
        return new double[] {
                bounds[0] + x, bounds[1], bounds[2] + z,
                bounds[3] + x, bounds[4], bounds[5] + z
        };
    }
}
