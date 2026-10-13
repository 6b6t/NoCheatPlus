/*
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package fr.neatmonster.nocheatplus.checks.moving.magic;

import org.bukkit.Location;
import org.bukkit.Material;

import fr.neatmonster.nocheatplus.utilities.map.BlockCache;
import fr.neatmonster.nocheatplus.utilities.map.BlockFlags;

/**
 * Vanilla step-up past the corner of a block (Entity#collide, 1.21+).
 * <p>
 * When a move is blocked sideways while on ground (or while landing), vanilla
 * tries the collider tops within the step height as candidate heights and
 * moves the box by (dx, height, dz) in one go. There is no move back down
 * afterwards. If the sideways part carries the box past the corner of the
 * block, the player ends at the block's top height beside the block, over air,
 * and the client still counts as on ground for that tick. The next tick the
 * client jumps from there (or falls). The server sees a move that ends in the
 * air, keeps the set back at the lower ground, and the following jump reads
 * higher than a jump (vdistsb), which is a false set back.
 * <p>
 * This helper only answers the geometry question: did the move rise by at most
 * the step height, end exactly at the collision top of a standable block that
 * the box crossed during the move, with nothing in the way above that top? The
 * caller accepts the step and lets the next move lift off from that top, once.
 * Every height still comes from a real block top under the move; nothing can
 * rise higher than a vanilla step onto that block.
 */
public final class CornerStep {

    /**
     * Vanilla player step height (Attributes.STEP_HEIGHT default 0.6). The configured sfStepHeight can be higher
     * (live: 1.1); a corner step never uses more than this.
     */
    public static final double VANILLA_STEP_HEIGHT = 0.6;
    /** Vanilla step heights are floats (collectCandidateStepUpHeights); end heights match block tops within this. */
    public static final double TOP_EPSILON = 1.0E-5;
    /** A box ending exactly on a block edge does not touch that block. */
    private static final double EDGE_EPSILON = 1.0E-7;
    /** Fence-like blocks: NCP overrides the collision height to 1.5. */
    private static final double HEIGHT150 = 1.5;

    private CornerStep() {}

    /**
     * Test if a rising move ended exactly at the collision top of a standable
     * block that the move's box crossed.
     *
     * @param blockCache
     *            Server block cache.
     * @param fromMinX
     *            Box of the from position (horizontal) ...
     * @param fromMinZ
     * @param fromMaxX
     * @param fromMaxZ
     * @param fromY
     *            Feet height at the start of the move.
     * @param toMinX
     *            Box of the to position (horizontal) ...
     * @param toMinZ
     * @param toMaxX
     * @param toMaxZ
     * @param toY
     *            Feet height at the end of the move.
     * @param boxHeight
     *            Height of the player's box.
     * @param stepHeight
     *            Configured step height (sfStepHeight); capped at the vanilla 0.6.
     * @return True, if the rise is above 0 and at most the step height (never
     *         more than the vanilla 0.6), a
     *         standable collision box with its top at toY lies under the box
     *         swept from the from box to the to box, the plain move (larger
     *         axis first, like vanilla) runs into something solid above the
     *         feet, and the same path at the top's height is free.
     */
    public static boolean crossedStepTop(final BlockCache blockCache,
                                         final double fromMinX, final double fromMinZ, final double fromMaxX, final double fromMaxZ,
                                         final double fromY,
                                         final double toMinX, final double toMinZ, final double toMaxX, final double toMaxZ,
                                         final double toY, final double boxHeight, final double stepHeight) {
        if (blockCache == null
                || !finite(fromMinX) || !finite(fromMinZ) || !finite(fromMaxX) || !finite(fromMaxZ) || !finite(fromY)
                || !finite(toMinX) || !finite(toMinZ) || !finite(toMaxX) || !finite(toMaxZ) || !finite(toY)
                || !finite(boxHeight) || !finite(stepHeight)) {
            return false;
        }
        final double rise = toY - fromY;
        if (!(rise > TOP_EPSILON) || rise > Math.min(stepHeight, VANILLA_STEP_HEIGHT) + TOP_EPSILON || !(boxHeight > 0.0)) {
            return false;
        }
        // Box swept by the move (horizontal hull of both boxes): vanilla collects the candidate step heights here.
        final double minX = Math.min(fromMinX, toMinX);
        final double minZ = Math.min(fromMinZ, toMinZ);
        final double maxX = Math.max(fromMaxX, toMaxX);
        final double maxZ = Math.max(fromMaxZ, toMaxZ);
        if (!(maxX > minX) || !(maxZ > minZ) || !hasTopUnder(blockCache, minX, minZ, maxX, maxZ, toY)) {
            return false;
        }
        // Vanilla moves the box axis by axis, the larger horizontal axis first (Direction#axisStepOrder): two legs.
        final double dx = toMinX - fromMinX;
        final double dz = toMinZ - fromMinZ;
        final boolean xFirst = !(Math.abs(dx) < Math.abs(dz));
        final double midMinX = xFirst ? toMinX : fromMinX;
        final double midMaxX = xFirst ? toMaxX : fromMaxX;
        final double midMinZ = xFirst ? fromMinZ : toMinZ;
        final double midMaxZ = xFirst ? fromMaxZ : toMaxZ;
        // Leg hulls.
        final double l1MinX = Math.min(fromMinX, midMinX), l1MaxX = Math.max(fromMaxX, midMaxX);
        final double l1MinZ = Math.min(fromMinZ, midMinZ), l1MaxZ = Math.max(fromMaxZ, midMaxZ);
        final double l2MinX = Math.min(midMinX, toMinX), l2MaxX = Math.max(midMaxX, toMaxX);
        final double l2MinZ = Math.min(midMinZ, toMinZ), l2MaxZ = Math.max(midMaxZ, toMaxZ);
        // A step is only tried after the plain move was blocked sideways: something solid in the way above the feet.
        final double blockMinY = fromY + TOP_EPSILON;
        final double blockMaxY = fromY + boxHeight;
        if (!collides(blockCache, l1MinX, l1MinZ, l1MaxX, l1MaxZ, blockMinY, blockMaxY)
                && !collides(blockCache, l2MinX, l2MinZ, l2MaxX, l2MaxZ, blockMinY, blockMaxY)) {
            return false;
        }
        // The stepped move went through free space at the top's height.
        final double freeMinY = toY + TOP_EPSILON;
        final double freeMaxY = toY + boxHeight;
        return !collides(blockCache, l1MinX, l1MinZ, l1MaxX, l1MaxZ, freeMinY, freeMaxY)
                && !collides(blockCache, l2MinX, l2MinZ, l2MaxX, l2MaxZ, freeMinY, freeMaxY);
    }

    /**
     * Test if the given move is the lift-off from an armed corner step: the
     * very next move, starting exactly where the step ended.
     *
     * @param armed
     * @param armedMove
     *            playerMoveCount of the step move.
     * @param moveCount
     *            playerMoveCount of this move.
     * @param armedX
     *            Position where the step ended ...
     * @param armedY
     * @param armedZ
     * @param fromX
     *            Start of this move ...
     * @param fromY
     * @param fromZ
     * @return
     */
    public static boolean isLiftOff(final boolean armed, final int armedMove, final int moveCount,
                                    final double armedX, final double armedY, final double armedZ,
                                    final double fromX, final double fromY, final double fromZ) {
        return armed && armedMove != Integer.MAX_VALUE && moveCount == armedMove + 1
                && fromX == armedX && fromY == armedY && fromZ == armedZ;
    }

    /** A standable collision box with its top at y, horizontally overlapping the given area. */
    static boolean hasTopUnder(final BlockCache blockCache, final double minX, final double minZ,
                               final double maxX, final double maxZ, final double y) {
        final int x1 = Location.locToBlock(minX);
        final int x2 = Location.locToBlock(maxX - EDGE_EPSILON);
        final int z1 = Location.locToBlock(minZ);
        final int z2 = Location.locToBlock(maxZ - EDGE_EPSILON);
        final int yTop = Location.locToBlock(y - TOP_EPSILON);
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                // The block containing the top, and the one below for 1.5 high blocks.
                for (int by = yTop; by >= yTop - 1; by--) {
                    final Material type = blockCache.getType(x, by, z);
                    if (type == null) {
                        continue;
                    }
                    final long flags = BlockFlags.getBlockFlags(type);
                    // (F_IGN_PASSABLE is set for slabs, stairs, fences: a passable-check workaround, not "no collision".)
                    if ((flags & BlockFlags.F_GROUND) == 0 || (flags & BlockFlags.F_LIQUID) != 0) {
                        continue;
                    }
                    final double[] bounds = blockCache.getBounds(x, by, z);
                    if (bounds == null || bounds.length < 6) {
                        continue;
                    }
                    final boolean height150 = (flags & BlockFlags.F_HEIGHT150) != 0;
                    for (int i = 0; i + 5 < bounds.length; i += 6) {
                        final double top = by + (height150 ? Math.max(bounds[i + 4], HEIGHT150) : bounds[i + 4]);
                        if (Math.abs(top - y) <= TOP_EPSILON
                                && x + bounds[i] < maxX && x + bounds[i + 3] > minX
                                && z + bounds[i + 2] < maxZ && z + bounds[i + 5] > minZ) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /** Something solid (collision box of a solid or ground block) overlaps the given box. */
    static boolean collides(final BlockCache blockCache, final double minX, final double minZ,
                            final double maxX, final double maxZ, final double minY, final double maxY) {
        final int x1 = Location.locToBlock(minX);
        final int x2 = Location.locToBlock(maxX - EDGE_EPSILON);
        final int z1 = Location.locToBlock(minZ);
        final int z2 = Location.locToBlock(maxZ - EDGE_EPSILON);
        final int y1 = Location.locToBlock(minY) - 1; // 1.5 high blocks below.
        final int y2 = Location.locToBlock(maxY - EDGE_EPSILON);
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                for (int by = y1; by <= y2; by++) {
                    final Material type = blockCache.getType(x, by, z);
                    if (type == null) {
                        continue;
                    }
                    final long flags = BlockFlags.getBlockFlags(type);
                    if ((flags & (BlockFlags.F_SOLID | BlockFlags.F_GROUND)) == 0 || (flags & BlockFlags.F_LIQUID) != 0) {
                        continue;
                    }
                    final double[] bounds = blockCache.getBounds(x, by, z);
                    if (bounds == null || bounds.length < 6) {
                        // Unknown shape: a full block.
                        if (by + 1.0 > minY && by < maxY) {
                            return true;
                        }
                        continue;
                    }
                    final boolean height150 = (flags & BlockFlags.F_HEIGHT150) != 0;
                    for (int i = 0; i + 5 < bounds.length; i += 6) {
                        final double top = by + (height150 ? Math.max(bounds[i + 4], HEIGHT150) : bounds[i + 4]);
                        if (by + bounds[i + 1] < maxY && top > minY
                                && x + bounds[i] < maxX && x + bounds[i + 3] > minX
                                && z + bounds[i + 2] < maxZ && z + bounds[i + 5] > minZ) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static boolean finite(final double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }
}
