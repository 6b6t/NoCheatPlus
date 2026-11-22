/*
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package fr.neatmonster.nocheatplus.checks.moving.magic;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

import fr.neatmonster.nocheatplus.utilities.location.PlayerLocation;
import fr.neatmonster.nocheatplus.utilities.map.BlockCache;
import fr.neatmonster.nocheatplus.utilities.map.BlockFlags;

/** Geometry for the narrow SurvivalFly workaround for standing inside full blocks. */
public final class InBlockStand {

    private static final double EDGE_EPSILON = 1.0E-7;

    private InBlockStand() {}

    /**
     * Whether this location is standing at the bottom of full blocks while its body is inside them.
     * The optional {@code from} bounds a destination check to feet blocks the previous body already touched.
     */
    public static boolean isStanding(final PlayerLocation loc, final double yOnGround, final boolean upward,
                                     final boolean headBlockedAllowed, final PlayerLocation from) {
        if (loc == null || !Double.isFinite(yOnGround) || yOnGround < 0.0) {
            return false;
        }
        final double feetY = loc.getMinY();
        final double floorY = Math.floor(feetY);
        if (feetY - floorY > yOnGround) {
            return false;
        }
        final World world = loc.getWorld();
        if (world == null || world.getEnvironment() == World.Environment.NETHER && floorY >= 120.0) {
            return false;
        }
        final BlockCache cache = loc.getBlockCache();
        if (cache == null) {
            return false;
        }
        final int feetBlockY = (int) floorY;
        final int minX = Location.locToBlock(loc.getMinX());
        final int maxX = Location.locToBlock(loc.getMaxX() - EDGE_EPSILON);
        final int minZ = Location.locToBlock(loc.getMinZ());
        final int maxZ = Location.locToBlock(loc.getMaxZ() - EDGE_EPSILON);
        if (minX > maxX || minZ > maxZ) {
            return false;
        }

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!fullGround(cache, x, feetBlockY, z)
                        || !fullGround(cache, x, feetBlockY - 1, z)) {
                    return false;
                }
                if (from != null && !bodyTouchesBlock(from, x, feetBlockY, z)) {
                    return false;
                }
                if (cache.getType(x, feetBlockY + 1, z) != null
                        && !isEmpty(cache, x, feetBlockY + 1, z)
                        && (upward || !headBlockedAllowed)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean fullGround(final BlockCache cache, final int x, final int y, final int z) {
        final Material type = cache.getType(x, y, z);
        if (type == null) {
            return false;
        }
        final long flags = BlockFlags.getBlockFlags(type);
        if ((flags & (BlockFlags.F_GROUND | BlockFlags.F_SOLID)) != (BlockFlags.F_GROUND | BlockFlags.F_SOLID)
                || (flags & BlockFlags.F_LIQUID) != 0) {
            return false;
        }
        final double[] bounds = cache.getBounds(x, y, z);
        if (bounds == null) {
            return true; // Null means the ordinary full cube for blocks without a special shape.
        }
        if (bounds.length != 6) {
            return false;
        }
        return bounds[0] <= 0.0 && bounds[1] <= 0.0 && bounds[2] <= 0.0
                && bounds[3] >= 1.0 && bounds[4] >= 1.0 && bounds[5] >= 1.0;
    }

    private static boolean isEmpty(final BlockCache cache, final int x, final int y, final int z) {
        final Material type = cache.getType(x, y, z);
        if (type == null) {
            return true;
        }
        final double[] bounds = cache.getBounds(x, y, z);
        if ((BlockFlags.getBlockFlags(type) & (BlockFlags.F_SOLID | BlockFlags.F_GROUND)) == 0L) {
            return true;
        }
        return bounds != null && bounds.length == 0;
    }

    private static boolean bodyTouchesBlock(final PlayerLocation from, final int x, final int y, final int z) {
        return from.getMinX() < x + 1.0 - EDGE_EPSILON && from.getMaxX() > x + EDGE_EPSILON
                && from.getMinY() < y + 1.0 - EDGE_EPSILON && from.getMaxY() > y + EDGE_EPSILON
                && from.getMinZ() < z + 1.0 - EDGE_EPSILON && from.getMaxZ() > z + EDGE_EPSILON;
    }
}
