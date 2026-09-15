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
package fr.neatmonster.nocheatplus.utilities.collision;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import fr.neatmonster.nocheatplus.checks.moving.util.MovingUtil;
import fr.neatmonster.nocheatplus.utilities.location.TrigUtil;
import fr.neatmonster.nocheatplus.utilities.map.BlockCache;
import fr.neatmonster.nocheatplus.utilities.map.BlockFlags;
import fr.neatmonster.nocheatplus.utilities.map.BlockProperties;
import fr.neatmonster.nocheatplus.utilities.map.MaterialUtil;

/**
 * Collision related static utility.
 * 
 * @author asofold
 *
 */
public class CollisionUtil {

    /** Temporary use, setWorld(null) once finished. */
    private static final Location useLoc = new Location(null, 0, 0, 0);

    /**
     * Check if a player looks at a target of a specific size, with a specific
     * precision value (roughly).
     *
     * @param player
     *            the player
     * @param targetX
     *            the target x
     * @param targetY
     *            the target y
     * @param targetZ
     *            the target z
     * @param targetWidth
     *            the target width
     * @param targetHeight
     *            the target height
     * @param precision
     *            the precision
     * @return the double
     */
    public static double directionCheck(final Player player, final double targetX, final double targetY, final double targetZ, final double targetWidth, final double targetHeight, final double precision)
    {
        final Location loc = player.getLocation(useLoc);
        final Vector dir = loc.getDirection();
        final double res = directionCheck(loc.getX(), loc.getY() + MovingUtil.getEyeHeight(player), loc.getZ(), dir.getX(), dir.getY(), dir.getZ(), targetX, targetY, targetZ, targetWidth, targetHeight, precision);
        useLoc.setWorld(null);
        return res;
    }

    /**
     * Convenience method.
     *
     * @param sourceFoot
     *            the source foot
     * @param eyeHeight
     *            the eye height
     * @param dir
     *            the dir
     * @param target
     *            the target
     * @param precision
     *            (width/height are set to 1)
     * @return the double
     */
    public static double directionCheck(final Location sourceFoot, final double eyeHeight, final Vector dir, final Block target, final double precision)
    {
        return directionCheck(sourceFoot.getX(), sourceFoot.getY() + eyeHeight, sourceFoot.getZ(), dir.getX(), dir.getY(), dir.getZ(), target.getX(), target.getY(), target.getZ(), 1, 1, precision);
    }

    /**
     * Convenience method.
     *
     * @param sourceFoot
     *            the source foot
     * @param eyeHeight
     *            the eye height
     * @param dir
     *            the dir
     * @param targetX
     *            the target x
     * @param targetY
     *            the target y
     * @param targetZ
     *            the target z
     * @param targetWidth
     *            the target width
     * @param targetHeight
     *            the target height
     * @param precision
     *            the precision
     * @return the double
     */
    public static double directionCheck(final Location sourceFoot, final double eyeHeight, final Vector dir, final double targetX, final double targetY, final double targetZ, final double targetWidth, final double targetHeight, final double precision)
    {
        return directionCheck(sourceFoot.getX(), sourceFoot.getY() + eyeHeight, sourceFoot.getZ(), dir.getX(), dir.getY(), dir.getZ(), targetX, targetY, targetZ, targetWidth, targetHeight, precision);
    }

    /**
     * Check how far the looking direction is off the target.
     *
     * @param sourceX
     *            Source location of looking direction.
     * @param sourceY
     *            the source y
     * @param sourceZ
     *            the source z
     * @param dirX
     *            Looking direction.
     * @param dirY
     *            the dir y
     * @param dirZ
     *            the dir z
     * @param targetX
     *            Location that should be looked towards.
     * @param targetY
     *            the target y
     * @param targetZ
     *            the target z
     * @param targetWidth
     *            xz extent
     * @param targetHeight
     *            y extent
     * @param precision
     *            the precision
     * @return Some offset.
     */
    public static double directionCheck(final double sourceX, final double sourceY, final double sourceZ, final double dirX, final double dirY, final double dirZ, final double targetX, final double targetY, final double targetZ, final double targetWidth, final double targetHeight, final double precision)
    {

        //        // TODO: Here we have 0.x vs. 2.x, sometimes !
        //        NCPAPIProvider.getNoCheatPlusAPI().getLogManager().debug(Streams.TRACE_FILE, "COMBINED: " + combinedDirectionCheck(sourceX, sourceY, sourceZ, dirX, dirY, dirZ, targetX, targetY, targetZ, targetWidth, targetHeight, precision, 60));

        // TODO: rework / standardize.

        double dirLength = Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
        if (dirLength == 0.0) dirLength = 1.0; // ...

        final double dX = targetX - sourceX;
        final double dY = targetY - sourceY;
        final double dZ = targetZ - sourceZ;

        final double targetDist = Math.sqrt(dX * dX + dY * dY + dZ * dZ);

        final double xPrediction = targetDist * dirX / dirLength;
        final double yPrediction = targetDist * dirY / dirLength;
        final double zPrediction = targetDist * dirZ / dirLength;

        double off = 0.0D;

        off += Math.max(Math.abs(dX - xPrediction) - (targetWidth / 2 + precision), 0.0D);
        off += Math.max(Math.abs(dZ - zPrediction) - (targetWidth / 2 + precision), 0.0D);
        off += Math.max(Math.abs(dY - yPrediction) - (targetHeight / 2 + precision), 0.0D);

        if (off > 1) off = Math.sqrt(off);

        return off;
    }

    /**
     * Combined direction check.
     *
     * @param sourceFoot
     *            the source foot
     * @param eyeHeight
     *            the eye height
     * @param dir
     *            the dir
     * @param targetX
     *            the target x
     * @param targetY
     *            the target y
     * @param targetZ
     *            the target z
     * @param targetWidth
     *            the target width
     * @param targetHeight
     *            the target height
     * @param precision
     *            the precision
     * @param anglePrecision
     *            the angle precision
     * @return the double
     */
    public static double combinedDirectionCheck(final Location sourceFoot, final double eyeHeight, final Vector dir, final double targetX, final double targetY, final double targetZ, final double targetWidth, final double targetHeight, final double precision, final double anglePrecision, boolean isPlayer)
    {
        return combinedDirectionCheck(sourceFoot.getX(), sourceFoot.getY() + eyeHeight, sourceFoot.getZ(), dir.getX(), dir.getY(), dir.getZ(), targetX, targetY, targetZ, targetWidth, targetHeight, precision, anglePrecision, isPlayer);
    }

    /**
     * Combined direction check.
     *
     * @param sourceFoot
     *            the source foot
     * @param eyeHeight
     *            the eye height
     * @param dir
     *            the dir
     * @param target
     *            the target
     * @param precision
     *            the precision
     * @param anglePrecision
     *            the angle precision
     * @return the double
     */
    public static double combinedDirectionCheck(final Location sourceFoot, final double eyeHeight, final Vector dir, final Block target, final double precision, final double anglePrecision)
    {
        return combinedDirectionCheck(sourceFoot.getX(), sourceFoot.getY() + eyeHeight, sourceFoot.getZ(), dir.getX(), dir.getY(), dir.getZ(), target.getX(), target.getY(), target.getZ(), 1, 1, precision, anglePrecision, true);
    }

    /**
     * Combine directionCheck with angle, in order to prevent low-distance
     * abuse.
     *
     * @param sourceX
     *            the source x
     * @param sourceY
     *            the source y
     * @param sourceZ
     *            the source z
     * @param dirX
     *            the dir x
     * @param dirY
     *            the dir y
     * @param dirZ
     *            the dir z
     * @param targetX
     *            the target x
     * @param targetY
     *            the target y
     * @param targetZ
     *            the target z
     * @param targetWidth
     *            the target width
     * @param targetHeight
     *            the target height
     * @param blockPrecision
     *            the block precision
     * @param anglePrecision
     *            Precision in grad.
     * @return the double
     */
    public static double combinedDirectionCheck(final double sourceX, final double sourceY, final double sourceZ, final double dirX, final double dirY, final double dirZ, final double targetX, final double targetY, final double targetZ, final double targetWidth, final double targetHeight, final double blockPrecision, final double anglePrecision, final boolean isPlayer)
    {
        double dirLength = Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
        if (dirLength == 0.0) dirLength = 1.0; // ...

        final double dX = targetX - sourceX;
        final double dY = targetY - sourceY;
        final double dZ = targetZ - sourceZ;

        final double targetDist = Math.sqrt(dX * dX + dY * dY + dZ * dZ);
        final double minDist = isPlayer ? Math.max(targetHeight, targetWidth) / 2.0 : Math.max(targetHeight, targetWidth);

        if (targetDist > minDist && TrigUtil.angle(sourceX, sourceY, sourceZ, dirX, dirY, dirZ, targetX, targetY, targetZ) * TrigUtil.fRadToGrad > anglePrecision){
            return targetDist - minDist;
        }

        final double xPrediction = targetDist * dirX / dirLength;
        final double yPrediction = targetDist * dirY / dirLength;
        final double zPrediction = targetDist * dirZ / dirLength;

        double off = 0.0D;

        off += Math.max(Math.abs(dX - xPrediction) - (targetWidth / 2 + blockPrecision), 0.0D);
        off += Math.max(Math.abs(dY - yPrediction) - (targetHeight / 2 + blockPrecision), 0.0D);
        off += Math.max(Math.abs(dZ - zPrediction) - (targetWidth / 2 + blockPrecision), 0.0D);

        if (off > 1) off = Math.sqrt(off);

        return off;
    }

    /**
     * Test if the block coordinate is intersecting with min+max bounds,
     * assuming the a full block. Excludes the case of only the edges
     * intersecting.
     *
     * @param min
     *            the min
     * @param max
     *            the max
     * @param block
     *            Block coordinate of the block.
     * @return true, if successful
     */
    public static boolean intersectsBlock(final double min, final double max, final int block) {
        final double db = (double) block;
        return db + 1.0 > min && db < max;
    }

    /**
     * Test if a point is inside an AABB, including the edges.
     * 
     * @param x
     *            Position of the point.
     * @param y
     * @param z
     * @param minX
     *            Minimum coordinates of the AABB.
     * @param minY
     * @param minZ
     * @param maxX
     *            Maximum coordinates of the AABB.
     * @param maxY
     * @param maxZ
     * @return
     */
    public static boolean isInsideAABBIncludeEdges(final double x, final double y, final double z,
            final double minX, final double minY, final double minZ,
            final double maxX, final double maxY, final double maxZ) {
        return !(x < minX || x > maxX || z < minZ || z > maxZ || y < minY || y > maxY);
    }

    /**
     * Get the earliest time a collision with the min-max coordinates can occur,
     * in multiples of dir, including edges.
     * 
     * @param pos
     * @param dir
     * @param minPos
     * @param maxPos
     * @return The multiple of dir to hit the min-max coordinates, or
     *         Double.POSITIVE_INFINITY if not possible to hit.
     */
    public static double getMinTimeIncludeEdges(final double pos, final double dir, 
            final double minPos, final double maxPos) {
        if (pos >= minPos && pos <= maxPos) {
            return 0.0;
        }
        else if (dir == 0.0) {
            return Double.POSITIVE_INFINITY;
        }
        else if (dir < 0.0) {
            return pos < minPos ? Double.POSITIVE_INFINITY : (Math.abs(pos - maxPos) / Math.abs(dir));
        }
        else {
            // dir > 0.0
            return pos > maxPos ? Double.POSITIVE_INFINITY : (Math.abs(pos - minPos) / dir);
        }
    }

    /**
     * Get the maximum time for which the min-max coordinates still are hit.
     * 
     * @param pos
     * @param dir
     * @param minPos
     * @param maxPos
     * @param minTime
     *            The earliest time of collision with the min-max coordinates,
     *            as returned by getMinTimeIncludeEdges.
     * @return The maximum time for which the min-max coordinates still are hit.
     *         If no hit is possible, Double.NaN is returned. If minTime is
     *         Double.POSITIVE_INFINITY, Double.NaN is returned directly.
     *         Double.POSITIVE_INFINITY may be returned, if coordinates are
     *         colliding always.
     */
    public static double getMaxTimeIncludeEdges(final double pos, final double dir, 
            final double minPos, final double maxPos, final double minTime) {
        if (Double.isInfinite(minTime)) {
            return Double.NaN;
        }
        else if (dir == 0.0) {
            return (pos < minPos || pos > maxPos) ? Double.NaN : Double.POSITIVE_INFINITY;
        }
        else if (dir < 0.0) {
            return pos < minPos ? Double.NaN : (Math.abs(pos - minPos) / Math.abs(dir));
        }
        else {
            // dir > 0.0
            return pos > maxPos ? Double.NaN : (Math.abs(pos - maxPos) / dir);
        }
    }

    /**
     * Get the maximum (closest) distance from the given position towards the
     * AABB regarding axes independently.
     * 
     * @param x
     *            Position of the point.
     * @param y
     * @param z
     * @param minX
     *            Minimum coordinates of the AABB.
     * @param minY
     * @param minZ
     * @param maxX
     *            Maximum coordinates of the AABB.
     * @param maxY
     * @param maxZ
     * @return
     */
    public static double getMaxAxisDistAABB(final double x, final double y, final double z,
            final double minX, final double minY, final double minZ,
            final double maxX, final double maxY, final double maxZ) {
        return Math.max(axisDistance(x,  minX, maxX), Math.max(axisDistance(y, minY, maxY), axisDistance(z, minZ, maxZ)));
    }

    /**
     * Get the maximum (closest) 'Manhattan' distance from the given position
     * towards the AABB regarding axes independently.
     * 
     * @param x
     *            Position of the point.
     * @param y
     * @param z
     * @param minX
     *            Minimum coordinates of the AABB.
     * @param minY
     * @param minZ
     * @param maxX
     *            Maximum coordinates of the AABB.
     * @param maxY
     * @param maxZ
     * @return
     */
    public static double getManhattanDistAABB(final double x, final double y, final double z,
            final double minX, final double minY, final double minZ,
            final double maxX, final double maxY, final double maxZ) {
        return axisDistance(x,  minX, maxX)+ axisDistance(y, minY, maxY) + axisDistance(z, minZ, maxZ);
    }

    /**
     * Get the squared (closest) distance from the given position towards the
     * AABB regarding axes independently.
     * 
     * @param x
     *            Position of the point.
     * @param y
     * @param z
     * @param minX
     *            Minimum coordinates of the AABB.
     * @param minY
     * @param minZ
     * @param maxX
     *            Maximum coordinates of the AABB.
     * @param maxY
     * @param maxZ
     * @return
     */
    public static double getSquaredDistAABB(final double x, final double y, final double z,
            final double minX, final double minY, final double minZ,
            final double maxX, final double maxY, final double maxZ) {
        final double dX = axisDistance(x,  minX, maxX);
        final double dY = axisDistance(y, minY, maxY);
        final double dZ = axisDistance(z, minZ, maxZ);
        return dX * dX + dY * dY + dZ * dZ;
    }

    /**
     * Get the distance towards a min-max interval (inside and edge count as 0.0
     * distance).
     * 
     * @param pos
     * @param minPos
     * @param maxPos
     * @return Positive distance always.
     */
    public static double axisDistance(final double pos, final double minPos, final double maxPos) {
        return pos < minPos ? Math.abs(pos - minPos) : (pos > maxPos ? Math.abs(pos - maxPos) : 0.0);
    }

    public static boolean isCollidingWithEntities(final Player p, final boolean onlyLivingEntities) {
        double xzRange = 0.15;
        double yRange = onlyLivingEntities ? 0.2 : 0.15;

        // Directly iterate over entities and check conditions to avoid unnecessary collection creation.
        for (Entity entity : p.getWorld().getNearbyEntities(p.getLocation(), xzRange, yRange, xzRange)) {
            if (!onlyLivingEntities || entity instanceof LivingEntity) {
                return true; // Collision detected, return early
            }
        }

        return false; // No collision detected
    }

    /** Longest line (in blocks passed) canSeeBox traces, anything longer counts as blocked. */
    private static final int MAX_LINE_BLOCKS = 64;

    /** Relative sample positions per axis, center first. Inset, so lines don't graze the neighbor blocks. */
    private static final double[] SAMPLES = {0.5, 0.02, 0.98};

    /**
     * Lines only touching a box (no length inside it) don't count as blocked. Boxes are not shrunk for this, a line
     * along the seam of two solid blocks is blocked.
     */
    private static final double TOUCH = 1.0E-9;

    /**
     * Test if a straight line from the eye reaches the box without passing
     * through the collision box of any block other than the ignored one. Lines
     * go to a 3x3x3 grid of points inside the box (center first), one clear
     * line counts as visible.
     * 
     * @param blockCache
     * @param eyeX
     * @param eyeY
     * @param eyeZ
     * @param minX
     *            Target box.
     * @param minY
     * @param minZ
     * @param maxX
     * @param maxY
     * @param maxZ
     * @param ignoreX
     *            Block to ignore, e.g. the target block. Integer.MAX_VALUE for
     *            none.
     * @param ignoreY
     * @param ignoreZ
     * @return true if visible.
     */
    public static boolean canSeeBox(final BlockCache blockCache, final double eyeX, final double eyeY, final double eyeZ, 
            final double minX, final double minY, final double minZ, final double maxX, final double maxY, final double maxZ, 
            final int ignoreX, final int ignoreY, final int ignoreZ) {
        // ponytail: 27 fixed points, a view through a gap narrower than ~half the box can be missed. Block interaction covers that with canSeeBlockPoint.
        for (final double sX : SAMPLES) {
            for (final double sY : SAMPLES) {
                for (final double sZ : SAMPLES) {
                    if (isLineClear(blockCache, eyeX, eyeY, eyeZ, 
                            minX + (maxX - minX) * sX, minY + (maxY - minY) * sY, minZ + (maxZ - minZ) * sZ, 
                            ignoreX, ignoreY, ignoreZ)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Test if a straight line from the eye reaches a point on a block, such as
     * where the player clicked it. This sees slivers of a block that the
     * sample points of canSeeBox miss. The point is clamped into the block, so
     * it can't be moved in front of an obstacle.
     *
     * @param relX
     *            Point relative to the block.
     * @return true if visible.
     */
    public static boolean canSeeBlockPoint(final BlockCache blockCache, final double eyeX, final double eyeY, final double eyeZ,
            final int blockX, final int blockY, final int blockZ, final double relX, final double relY, final double relZ) {
        return isLineClear(blockCache, eyeX, eyeY, eyeZ,
                blockX + Math.clamp(relX, 0.0, 1.0), blockY + Math.clamp(relY, 0.0, 1.0), blockZ + Math.clamp(relZ, 0.0, 1.0),
                blockX, blockY, blockZ);
    }

    /**
     * Where the look direction from the eye first hits a box, for actions
     * without a hit position (left clicks, attacks).
     *
     * @return The point, null if the look misses the box.
     */
    public static Vector getLookPoint(final double eyeX, final double eyeY, final double eyeZ, final Vector direction,
            final double minX, final double minY, final double minZ, final double maxX, final double maxY, final double maxZ) {
        final RayTraceResult hit = new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ)
                .rayTrace(new Vector(eyeX, eyeY, eyeZ), direction, MAX_LINE_BLOCKS);
        return hit == null ? null : hit.getHitPosition();
    }

    /**
     * Where the look direction from the eye hits the full box of a block.
     *
     * @return The point relative to the block, null if the look misses it.
     */
    public static Vector getLookPoint(final double eyeX, final double eyeY, final double eyeZ, final Vector direction,
            final int blockX, final int blockY, final int blockZ) {
        final Vector hit = getLookPoint(eyeX, eyeY, eyeZ, direction, blockX, blockY, blockZ, blockX + 1, blockY + 1, blockZ + 1);
        return hit == null ? null : hit.subtract(new Vector(blockX, blockY, blockZ));
    }

    /**
     * Test if a straight line from the eye reaches a point without passing
     * through the collision box of any block, e.g. where the look hits an
     * entity.
     */
    public static boolean canSeePoint(final BlockCache blockCache, final double eyeX, final double eyeY, final double eyeZ,
            final double x, final double y, final double z) {
        return isLineClear(blockCache, eyeX, eyeY, eyeZ, x, y, z, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    /**
     * Walk all blocks the line passes (voxel traversal) and test the line
     * against their collision boxes.
     */
    private static boolean isLineClear(final BlockCache blockCache, final double x0, final double y0, final double z0, 
            final double x1, final double y1, final double z1, final int ignoreX, final int ignoreY, final int ignoreZ) {
        final double dX = x1 - x0;
        final double dY = y1 - y0;
        final double dZ = z1 - z0;
        int x = Location.locToBlock(x0);
        int y = Location.locToBlock(y0);
        int z = Location.locToBlock(z0);
        final int stepX = dX > 0.0 ? 1 : -1;
        final int stepY = dY > 0.0 ? 1 : -1;
        final int stepZ = dZ > 0.0 ? 1 : -1;
        // Line "time" (0..1) to cross one block, and to reach the next block border, per axis.
        final double tDeltaX = dX == 0.0 ? Double.MAX_VALUE : 1.0 / Math.abs(dX);
        final double tDeltaY = dY == 0.0 ? Double.MAX_VALUE : 1.0 / Math.abs(dY);
        final double tDeltaZ = dZ == 0.0 ? Double.MAX_VALUE : 1.0 / Math.abs(dZ);
        double tMaxX = dX == 0.0 ? Double.MAX_VALUE : (dX > 0.0 ? x + 1 - x0 : x0 - x) * tDeltaX;
        double tMaxY = dY == 0.0 ? Double.MAX_VALUE : (dY > 0.0 ? y + 1 - y0 : y0 - y) * tDeltaY;
        double tMaxZ = dZ == 0.0 ? Double.MAX_VALUE : (dZ > 0.0 ? z + 1 - z0 : z0 - z) * tDeltaZ;
        for (int i = 0; i < MAX_LINE_BLOCKS; i++) {
            if ((x != ignoreX || y != ignoreY || z != ignoreZ) 
                    && blocksLine(blockCache, x, y, z, x0 - x, y0 - y, z0 - z, dX, dY, dZ)) {
                return false;
            }
            if (tMaxX > 1.0 && tMaxY > 1.0 && tMaxZ > 1.0) {
                // The line ends in this block.
                return true;
            }
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                x += stepX;
                tMaxX += tDeltaX;
            }
            else if (tMaxY < tMaxZ) {
                y += stepY;
                tMaxY += tDeltaY;
            }
            else {
                z += stepZ;
                tMaxZ += tDeltaZ;
            }
        }
        return false;
    }

    /**
     * Test the line against the collision boxes of one block.
     * 
     * @param oX
     *            Line start relative to the block (bounds are relative too).
     */
    private static boolean blocksLine(final BlockCache blockCache, final int x, final int y, final int z, 
            final double oX, final double oY, final double oZ, final double dX, final double dY, final double dZ) {
        final Material type = blockCache.getType(x, y, z);
        if (BlockProperties.isPassable(type)) {
            return false;
        }
        final double[] bounds = blockCache.getBounds(x, y, z);
        if (bounds == null) {
            return false;
        }
        final long flags = BlockFlags.getBlockFlags(type);
        // Snow bounds are kept full, the layers are in the data. It looks one layer higher than it collides.
        final double maxHeight = (flags & BlockFlags.F_HEIGHT_8_INC) != 0
                ? 0.125 * ((blockCache.getData(x, y, z) & 0xF) % 8 + 1) : 1.0;
        // Fences, walls and gates collide up to 1.5, but look lower: fences 1.0, low wall sides 0.875, gates in a wall 0.8125.
        final double tallHeight = (flags & BlockFlags.F_PASSABLE_X4) != 0 ? 0.8125
                : MaterialUtil.ALL_WALLS.contains(type) ? 0.875 : 1.0;
        for (int i = 0; i + 5 < bounds.length; i += 6) {
            final double maxY = bounds[i + 4] > 1.0 ? tallHeight : Math.min(bounds[i + 4], maxHeight);
            final double enter = Math.max(enterTime(oX, dX, bounds[i], bounds[i + 3]),
                    Math.max(enterTime(oY, dY, bounds[i + 1], maxY),
                            enterTime(oZ, dZ, bounds[i + 2], bounds[i + 5])));
            final double exit = Math.min(exitTime(oX, dX, bounds[i], bounds[i + 3]),
                    Math.min(exitTime(oY, dY, bounds[i + 1], maxY),
                            exitTime(oZ, dZ, bounds[i + 2], bounds[i + 5])));
            // Passing through the box within the line. Starting inside a box (eye in a block) doesn't count.
            if (exit - enter > TOUCH && enter >= 0.0 && enter < 1.0) {
                return true;
            }
        }
        return false;
    }

    /** Line time (o + t * d) of entering [min, max] on one axis. */
    private static double enterTime(final double o, final double d, final double min, final double max) {
        if (d == 0.0) {
            return o >= min && o <= max ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        }
        return ((d > 0.0 ? min : max) - o) / d;
    }

    /** Line time (o + t * d) of leaving [min, max] on one axis. */
    private static double exitTime(final double o, final double d, final double min, final double max) {
        if (d == 0.0) {
            return o >= min && o <= max ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
        }
        return ((d > 0.0 ? max : min) - o) / d;
    }
}
