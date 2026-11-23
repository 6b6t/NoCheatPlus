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
import fr.neatmonster.nocheatplus.checks.moving.model.LiftOffEnvelope;

/** Geometry for the narrow SurvivalFly workaround for standing inside full blocks. */
public final class InBlockStand {

    private static final double EDGE_EPSILON = 1.0E-7;
    private static final double JUMP_GAIN_EPSILON = 0.005;

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

    /** Whether a positive move is the first vanilla jump impulse or is capped by a real ceiling. */
    public static boolean isVanillaJumpStart(final PlayerLocation from, final double yDistance,
                                              final double jumpAmplifier,
                                              final LiftOffEnvelope envelope) {
        if (from == null || !Double.isFinite(yDistance) || yDistance <= 0.0 || envelope == null) {
            return false;
        }
        final double jumpGain = envelope.getMinJumpGain(jumpAmplifier);
        if (Math.abs(yDistance - jumpGain) <= JUMP_GAIN_EPSILON) {
            return true;
        }
        if (yDistance >= jumpGain) {
            return false;
        }
        final double clearance = ceilingClearance(from, jumpGain);
        return clearance > 0.0 && clearance < jumpGain
                && Math.abs(yDistance - clearance) <= JUMP_GAIN_EPSILON;
    }

    private static double ceilingClearance(final PlayerLocation from, final double jumpGain) {
        final BlockCache cache = from.getBlockCache();
        if (cache == null) return Double.POSITIVE_INFINITY;
        final double top = from.getMaxY();
        final int minX = Location.locToBlock(from.getMinX());
        final int maxX = Location.locToBlock(from.getMaxX() - EDGE_EPSILON);
        final int minZ = Location.locToBlock(from.getMinZ());
        final int maxZ = Location.locToBlock(from.getMaxZ() - EDGE_EPSILON);
        double clearance = Double.POSITIVE_INFINITY;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = (int) Math.floor(top); y <= (int) Math.floor(top + jumpGain); y++) {
                    final Material type = cache.getType(x, y, z);
                    if (type == null || (BlockFlags.getBlockFlags(type) & (BlockFlags.F_GROUND | BlockFlags.F_SOLID))
                            != (BlockFlags.F_GROUND | BlockFlags.F_SOLID)) continue;
                    final double[] bounds = cache.getBounds(x, y, z);
                    if (bounds != null && bounds.length != 6) continue;
                    final double minY = y + (bounds == null ? 0.0 : bounds[1]);
                    final double minLocalX = bounds == null ? 0.0 : bounds[0];
                    final double minLocalZ = bounds == null ? 0.0 : bounds[2];
                    final double maxLocalX = bounds == null ? 1.0 : bounds[3];
                    final double maxLocalZ = bounds == null ? 1.0 : bounds[5];
                    if (from.getMaxX() > x + minLocalX && from.getMinX() < x + maxLocalX
                            && from.getMaxZ() > z + minLocalZ && from.getMinZ() < z + maxLocalZ
                            && minY >= top - EDGE_EPSILON) {
                        clearance = Math.min(clearance, minY - top);
                    }
                }
            }
        }
        return clearance;
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
