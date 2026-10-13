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
 * Two vanilla lift-offs from water next to or on an entity the player collides
 * with (boat, bamboo raft, shulker, happy ghast). NCP models both for blocks
 * (lift-off envelope LIMIT_NEAR_GROUND, waterlogged ground) but not for
 * entities, so the lift-off was judged against the swim-up limit and set back
 * (tags hliquid/hliquidexit + swimup).
 * <p>
 * <b>Climb out next to an entity</b> (LivingEntity#jumpOutOfFluid): at the end of
 * a tick in water or lava, a player whose move was blocked sideways and who
 * would be free 0.6 higher gets a vertical speed of exactly 0.3F. The next tick
 * adds the swim-up kick 0.04F if jump is held in the liquid
 * (LivingEntity#jumpInLiquid), so that move rises 0.3 or 0.34. Climbing onto a
 * boat from the water does this every time.
 * <p>
 * <b>Jump from an entity in shallow water</b> (LivingEntity#aiStep): on ground
 * with the fluid at most getFluidJumpThreshold() (0.4) above the feet the
 * player jumps as on land (jumpFromGround: 0.42 + 0.1 per Jump Boost level).
 * <p>
 * Only these exact rises are accepted. Nothing else changes.
 */
public final class FluidEntityClimb {

    /** LivingEntity#jumpOutOfFluid sets this vertical speed (float like vanilla). */
    public static final double JUMP_OUT_OF_FLUID = (double) 0.3F;
    /** LivingEntity#jumpInLiquid adds this per tick with jump held (float like vanilla). */
    public static final double FLUID_JUMP_KICK = (double) 0.04F;
    /** Tolerance for comparing a client rise with the vanilla rise (vanilla 0.3F + 0.04F = 0.3400000110). */
    public static final double RISE_MARGIN = 0.0001;
    /** Highest vanilla rise of the move after a climb impulse. */
    public static final double MAX_CLIMB_RISE = JUMP_OUT_OF_FLUID + FLUID_JUMP_KICK + RISE_MARGIN;
    /** LivingEntity#jumpOutOfFluid: the box this much higher (plus the remaining speed) must be free. */
    public static final double FREE_ABOVE = (double) 0.6F;
    /** Entity#getFluidJumpThreshold for players (eye height above 0.4). */
    public static final double FLUID_JUMP_THRESHOLD = 0.4;
    /** Horizontal distance at which the side of an entity counts as touched (server/client entity position noise). */
    public static final double SIDE_MARGIN = 0.1;
    /** Entity top tolerance (a floating boat bobs; the client interpolates its position). */
    public static final double TOP_MARGIN = 0.05;
    /** The entity top must be at least this much above the feet to block a sideways move (else the player stands on it). */
    public static final double MIN_SIDE_HEIGHT = 0.001;
    /** Entity#updateFluidHeightAndDoFluidPushing deflates the box by this. */
    private static final double FLUID_BOX_DEFLATE = 0.001;

    private FluidEntityClimb() {}

    /**
     * Test if a rise fits the vanilla move right after a climb impulse (0.3, or
     * 0.34 with jump held in the liquid).
     *
     * @param yDistance
     * @return
     */
    public static boolean isClimbRise(final double yDistance) {
        return yDistance > 0.0 && yDistance <= MAX_CLIMB_RISE;
    }

    /**
     * Highest top an obstacle beside the player may have for the climb impulse
     * of the last tick. Vanilla tests the box at (start of the last tick + 0.6
     * + vertical speed left after the liquid drag): start = feetY - lastYDistance,
     * speed left = lastYDistance * 0.8 (water) or * 0.5 (lava) minus gravity.
     * Upper bound over both: feetY + 0.6 for a rising last move, feetY + 0.6 +
     * 0.5 * fall for a falling one.
     *
     * @param feetY
     *            Feet height at the start of this move (end of the last move).
     * @param lastYDistance
     *            yDistance of the last move (NaN if unknown).
     * @return The highest allowed obstacle top, NaN if unknown.
     */
    public static double maxObstacleTop(final double feetY, final double lastYDistance) {
        if (Double.isNaN(feetY) || Double.isInfinite(feetY) || Double.isNaN(lastYDistance)
                || Double.isInfinite(lastYDistance) || Math.abs(lastYDistance) > 4.0) {
            return Double.NaN;
        }
        return feetY + FREE_ABOVE + 0.5 * Math.max(0.0, -lastYDistance) + TOP_MARGIN;
    }

    /**
     * Decide the climb-out exemption.
     *
     * @param yDistance
     *            This move's yDistance.
     * @param fromInLiquid
     *            This move starts in water or lava.
     * @param headObstructed
     *            The head is obstructed (vanilla isFree fails).
     * @param besideEntity
     *            The player touches the side of an entity it collides with,
     *            whose top is above the feet and at most
     *            {@link #maxObstacleTop(double, double)}, and no taller such
     *            entity touches it.
     * @return True if the move is the vanilla rise after a climb impulse.
     */
    public static boolean allowsClimbOut(final double yDistance, final boolean fromInLiquid,
                                         final boolean headObstructed, final boolean besideEntity) {
        return fromInLiquid && !headObstructed && besideEntity && isClimbRise(yDistance);
    }

    /**
     * Decide the shallow-water jump from an entity.
     *
     * @param yDistance
     *            This move's yDistance.
     * @param fromOnEntity
     *            The feet stand on an entity the player collides with
     *            (server-side check, not the client's ground flag).
     * @param fluidHeight
     *            Fluid height above the feet (vanilla definition), NaN if
     *            unknown.
     * @param headObstructed
     *            The head is obstructed.
     * @param jumpGain
     *            Vanilla jump speed with the current Jump Boost (0.42 + 0.1 per
     *            level).
     * @return True if the move is the vanilla jump from ground.
     */
    public static boolean allowsShallowJump(final double yDistance, final boolean fromOnEntity,
                                            final double fluidHeight, final boolean headObstructed,
                                            final double jumpGain) {
        return fromOnEntity && !headObstructed && fluidHeight > 0.0 && fluidHeight <= FLUID_JUMP_THRESHOLD
                && yDistance > 0.0 && yDistance <= jumpGain + RISE_MARGIN;
    }

    /**
     * Vanilla fluid height above the feet (Entity#getFluidHeight): highest
     * water or lava surface in the blocks the box overlaps, minus the deflated
     * box bottom. Surface = block y + 1.0 below the same fluid, else (8 - level)
     * / 9 (source and falling: 8 / 9).
     *
     * @return The height, 0.0 for none, NaN if the box overlaps a fluid block
     *         that is not plain water or lava (caller must not exempt).
     *         Waterlogged blocks carry no fluid flag; the caller excludes them.
     */
    public static double fluidHeight(final BlockCache blockCache, final double minX, final double minY,
                                     final double minZ, final double maxX, final double maxY, final double maxZ) {
        if (blockCache == null || Double.isNaN(minY) || Double.isInfinite(minY)) {
            return Double.NaN;
        }
        final double bottom = minY + FLUID_BOX_DEFLATE;
        final int x1 = Location.locToBlock(minX + FLUID_BOX_DEFLATE);
        final int x2 = Location.locToBlock(maxX - FLUID_BOX_DEFLATE);
        final int y1 = Location.locToBlock(bottom);
        final int y2 = Location.locToBlock(maxY - FLUID_BOX_DEFLATE);
        final int z1 = Location.locToBlock(minZ + FLUID_BOX_DEFLATE);
        final int z2 = Location.locToBlock(maxZ - FLUID_BOX_DEFLATE);
        double height = 0.0;
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                for (int y = y1; y <= y2; y++) {
                    final Material type = blockCache.getType(x, y, z);
                    if (type == null) {
                        continue;
                    }
                    final long flags = BlockFlags.getBlockFlags(type);
                    if ((flags & BlockFlags.F_WATER_PLANT) != 0L) {
                        // Kelp, seagrass: always water inside, height not modelled here.
                        return Double.NaN;
                    }
                    final long fluid = flags & (BlockFlags.F_WATER | BlockFlags.F_LAVA);
                    if (fluid == 0L) {
                        continue;
                    }
                    if (type != Material.WATER && type != Material.LAVA) {
                        // Bubble columns and other fluid-like blocks: not modelled here.
                        return Double.NaN;
                    }
                    final double surface;
                    final Material above = blockCache.getType(x, y + 1, z);
                    if (above != null && (BlockFlags.getBlockFlags(above) & fluid) != 0L) {
                        surface = y + 1.0;
                    }
                    else {
                        final int level = blockCache.getData(x, y, z);
                        if (level < 0 || level > 15) {
                            return Double.NaN;
                        }
                        surface = y + (level >= 8 ? 8.0 : 8.0 - level) / 9.0;
                    }
                    if (surface >= bottom) {
                        height = Math.max(height, surface - bottom);
                    }
                }
            }
        }
        return height;
    }
}
