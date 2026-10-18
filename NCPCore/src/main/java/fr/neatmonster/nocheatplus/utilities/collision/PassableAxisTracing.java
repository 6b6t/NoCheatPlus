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

import java.util.UUID;

import fr.neatmonster.nocheatplus.compat.blocks.changetracker.BlockChangeReference;
import fr.neatmonster.nocheatplus.compat.blocks.changetracker.BlockChangeTracker;
import fr.neatmonster.nocheatplus.compat.blocks.changetracker.BlockChangeTracker.BlockChangeEntry;
import fr.neatmonster.nocheatplus.utilities.location.PlayerLocation;
import fr.neatmonster.nocheatplus.utilities.map.BlockCache;
import fr.neatmonster.nocheatplus.utilities.map.BlockProperties;

public class PassableAxisTracing extends AxisTracing implements ICollidePassable {

    private BlockCache blockCache;
    private BlockChangeTracker blockChangeTracker = null;
    private BlockChangeReference blockChangeRef = null;
    private int tick;
    private UUID worldId;

    private boolean preventCollisionGrowth;
    private double startMinX, startMinY, startMinZ, startMaxX, startMaxY, startMaxZ;
    private double deltaX, deltaY, deltaZ;

    /** Preserve escape from existing overlaps without allowing deeper penetration. */
    public void setPreventCollisionGrowth(final boolean preventCollisionGrowth) {
        this.preventCollisionGrowth = preventCollisionGrowth;
    }

    @Override
    public void set(final double x0, final double y0, final double z0,
            final double x1, final double y1, final double z1) {
        super.set(x0, y0, z0, x1, y1, z1);
        deltaX = x1 - x0;
        deltaY = y1 - y0;
        deltaZ = z1 - z0;
    }

    @Override
    protected boolean isBlockIgnored(final int x, final int y, final int z) {
        if (!super.isBlockIgnored(x, y, z)) return false;
        if (!preventCollisionGrowth) return true;
        final double[] bounds = blockCache.getBounds(x, y, z);
        if (bounds == null) return true;
        for (int i = 0; i + 5 < bounds.length; i += 6) {
            final double growth = overlapGrowth(startMinX, startMaxX, x + bounds[i], x + bounds[i + 3], deltaX)
                    + overlapGrowth(startMinY, startMaxY, y + bounds[i + 1], y + bounds[i + 4], deltaY)
                    + overlapGrowth(startMinZ, startMaxZ, z + bounds[i + 2], z + bounds[i + 5], deltaZ);
            if (growth > 1.0e-7) return false;
        }
        return true;
    }

    /** Initial relative growth of one overlap interval; contact must move inward to collide. */
    private static double overlapGrowth(final double min, final double max,
            final double blockMin, final double blockMax, final double delta) {
        final double overlap = Math.min(max, blockMax) - Math.max(min, blockMin);
        if (overlap <= 0.0) {
            return overlap == 0.0 && (max == blockMin && delta > 0.0 || min == blockMax && delta < 0.0)
                    ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
        }
        final double upper = max < blockMax || max == blockMax && delta < 0.0 ? delta : 0.0;
        final double lower = min > blockMin || min == blockMin && delta > 0.0 ? delta : 0.0;
        return (upper - lower) / overlap;
    }

    // TODO: Might need another option for margins (option to skip margin for the axis-start point, or alter ignoreFirst behavior).
    // TODO: Consider an iteration margin as well (0.5 below for fences).

    public BlockCache getBlockCache() {
        return blockCache;
    }

    public void setBlockCache(BlockCache blockCache) {
        this.blockCache = blockCache;
    }

    @Override
    public void setBlockChangeTracker(BlockChangeTracker blockChangeTracker, 
            BlockChangeReference blockChangeReference, int tick, UUID worldId) {
        this.blockChangeTracker = blockChangeTracker;
        this.blockChangeRef = blockChangeReference;
        this.tick = tick;
        this.worldId = worldId;
    }

    @Override
    protected void collectInitiallyCollidingBlocks(double minX, double minY, double minZ, double maxX, double maxY,
            double maxZ, BlockPositionContainer results) {
        startMinX = minX;
        startMinY = minY;
        startMinZ = minZ;
        startMaxX = maxX;
        startMaxY = maxY;
        startMaxZ = maxZ;
        BlockProperties.collectInitiallyCollidingBlocks(blockCache, minX, minY, minZ, maxX, maxY, maxZ, results);
    }

    @Override
    protected boolean step(final int blockX, final int blockY, final int blockZ, 
            final double minX, final double minY, final double minZ, 
            final double maxX, final double maxY, final double maxZ, 
            final Axis axis, final int increment) {
        if (BlockProperties.isPassableBox(blockCache, blockX, blockY, blockZ, minX, minY, minZ, maxX, maxY, maxZ)) {
            /*
             * TODO: HEIGHT150 -> if not passable... how/where to test for block
             * change tracker? E.g.: y-offset from block < 0.5 -> check the
             * block underneath. (one method check box normal + opportunistic
             * past state handling in one?) Prefer to set the
             * air/liquid/whichever block above the fence as ignored, in order
             * to allow collision with the 1.0 height border of the fence (TODO:
             * legacy only?).
             */
            return true;
        }
        // Recovery attempt via the BlockChangeTracker.
        if (blockChangeTracker != null) {
            // Opportunistic (FCFS, no consistency).
            // TODO: Replace by BlockChangeTracker.isPassableBox(...) - iteration is necessary / better.
            BlockChangeEntry entry = blockChangeTracker.getBlockChangeEntry(blockChangeRef, tick, worldId, blockX, blockY + 1, blockZ, null);
            if (entry == null) entry = blockChangeTracker.getBlockChangeEntry(blockChangeRef, tick, worldId, blockX, blockY, blockZ, null);
            if (entry != null) {
                blockChangeRef.updateSpan(entry);
                return true;
            }
        }
        // No condition for passing through found.
        collides = true;
        return false;
    }

    @Override
    public void set(PlayerLocation from, PlayerLocation to) {
        set(from.getX(), from.getY(), from.getZ(), to.getX(), to.getY(), to.getZ());
        setBlockCache(from.getBlockCache());
    }

    @Override
    public boolean mightNeedSplitAxisHandling() {
        return false;
    }

    @Override
    public void cleanup() {
        preventCollisionGrowth = false;
        blockCache = null;
        blockChangeTracker = null;
        blockChangeRef = null;
        worldId = null;
    }

}
