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
import fr.neatmonster.nocheatplus.utilities.map.MaterialUtil;

/**
 * Vanilla bed bounce after a short fall (BedBlock#bounceUp).
 * <p>
 * A player who lands on a bed without sneaking has the vertical speed of the
 * landing tick reversed and scaled by 0.66. After gravity and drag the next
 * Java move rises by {@code (-impact * 0.66 - 0.08) * 0.98}; Bedrock sends the
 * reflected impulse before that gravity step. A fall of more than one
 * block is handled by the prepared bounce in MovingListener (velocity). Shorter
 * falls get no such velocity, so the small hop that follows was judged as a
 * jump and the change to descending counted as a "low jump" (set back). This
 * happens when stepping down from a block onto a bed, when jumping from the
 * floor onto a bed, and when stepping up onto a bed while falling.
 * <p>
 * Only the exact vanilla rise is accepted, and only for the move right after an
 * accepted landing on the top face of a bed. The rest of the hop is still
 * judged by the ordinary in-air envelope; nothing gets higher or faster than a
 * vanilla bounce.
 */
public final class BedBounce {

    /** Top of a bed's collision shape above the block's base. */
    public static final double BED_TOP = 0.5625;
    /** BedBlock#bounceUp factor for living entities, as float like vanilla. */
    public static final double BOUNCE_FACTOR = (double) 0.66F;
    /** Player gravity per tick. */
    public static final double GRAVITY = 0.08;
    /** Vertical drag per tick, as float like vanilla. */
    public static final double DRAG = (double) 0.98F;
    /** Tolerance for comparing a client rise with the computed vanilla rise. */
    public static final double RISE_MARGIN = 0.001;
    /** Tolerance for the feet height being on the bed top. */
    private static final double TOP_EPSILON = 1.0E-7;

    private BedBounce() {}

    /**
     * Rise of the first move after landing on a bed.
     *
     * @param lastYDistance
     *            yDistance of the full in-air move before the landing move,
     *            i.e. the vertical speed the landing tick started from.
     * @param bedrock Whether the authenticated client uses Bedrock physics.
     * @return The vanilla rise of the following move, or 0.0 if there is no
     *         visible bounce.
     */
    public static double firstRise(final double lastYDistance, final boolean bedrock) {
        if (!(lastYDistance < 0.0) || Double.isNaN(lastYDistance) || Double.isInfinite(lastYDistance)) {
            return 0.0;
        }
        final double impact = (lastYDistance - GRAVITY) * DRAG;
        // Bedrock sends the reflected impulse before the next gravity step.
        final double rise = bedrock ? -impact * BOUNCE_FACTOR : (-impact * BOUNCE_FACTOR - GRAVITY) * DRAG;
        return rise > 0.0 ? rise : 0.0;
    }

    /**
     * Rise to expect after this move, if this move is an accepted landing on a
     * bed that bounces.
     *
     * @param lastToValid
     *            Last move has valid end data.
     * @param lastToOnGround
     *            Last move ended on ground (then its yDistance is not a free
     *            in-air speed).
     * @param lastYDistance
     *            Last move's yDistance.
     * @param fromOnGround
     *            This move starts on ground (no landing).
     * @param toOnGround
     *            This move ends on ground.
     * @param sneaking
     *            Really sneaking (vanilla: no bounce).
     * @param toOnBedTop
     *            The feet end on the top face of a bed.
     * @param bedrock Whether the authenticated client uses Bedrock physics.
     * @return The rise of the next move, or 0.0 if no bounce is expected.
     */
    public static double riseAfterLanding(final boolean lastToValid, final boolean lastToOnGround,
                                          final double lastYDistance, final boolean fromOnGround,
                                          final boolean toOnGround, final boolean sneaking,
                                          final boolean toOnBedTop, final boolean bedrock) {
        if (!lastToValid || lastToOnGround || fromOnGround || !toOnGround || sneaking || !toOnBedTop) {
            return 0.0;
        }
        return firstRise(lastYDistance, bedrock);
    }

    /**
     * Test if this move is the vanilla bounce right after the landing.
     *
     * @param expectedRise
     *            Rise armed by the landing move (0.0 = none).
     * @param armedMoveCount
     *            Move count of the landing move.
     * @param moveCount
     *            Move count of this move.
     * @param fromOnGround
     *            This move starts on ground.
     * @param yDistance
     *            This move's yDistance.
     * @param fromOnBedTop
     *            The feet start on the top face of a bed.
     * @return True if the move rises no more than the vanilla bounce.
     */
    public static boolean isBounceLiftOff(final double expectedRise, final int armedMoveCount, final int moveCount,
                                          final boolean fromOnGround, final double yDistance,
                                          final boolean fromOnBedTop) {
        return expectedRise > 0.0 && moveCount == armedMoveCount + 1 && fromOnGround && fromOnBedTop
                && yDistance > 0.0 && yDistance <= expectedRise + RISE_MARGIN;
    }

    /**
     * Test if feet at the given height stand on the top face of a bed under
     * the given horizontal box.
     *
     * @param blockCache
     * @param minX
     * @param minZ
     * @param maxX
     * @param maxZ
     * @param feetY
     * @return
     */
    public static boolean isOnBedTop(final BlockCache blockCache, final double minX, final double minZ,
                                     final double maxX, final double maxZ, final double feetY) {
        if (blockCache == null || Double.isNaN(feetY) || Double.isInfinite(feetY)) {
            return false;
        }
        final int y = Location.locToBlock(feetY);
        if (Math.abs(feetY - y - BED_TOP) > TOP_EPSILON) {
            return false;
        }
        // Blocks the box overlaps (a box ending exactly on a block edge does not overlap that block).
        final int x1 = Location.locToBlock(minX);
        final int x2 = Location.locToBlock(maxX - TOP_EPSILON);
        final int z1 = Location.locToBlock(minZ);
        final int z2 = Location.locToBlock(maxZ - TOP_EPSILON);
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                final Material type = blockCache.getType(x, y, z);
                if (type != null && MaterialUtil.BEDS.contains(type)) {
                    return true;
                }
            }
        }
        return false;
    }
}
