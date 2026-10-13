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
package fr.neatmonster.nocheatplus.checks.moving.util;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

import fr.neatmonster.nocheatplus.checks.moving.MovingData;
import fr.neatmonster.nocheatplus.compat.Folia;
import fr.neatmonster.nocheatplus.utilities.TickTask;

/**
 * The server's own push of a player by an opening shulker box (any facing: up, sideways, down).
 * <p>
 * Vanilla (ShulkerBoxBlockEntity.moveCollidedEntities) moves every entity in the swept lid slab with
 * MoverType.SHULKER_BOX on the server, ten ticks long, along the box's facing, without a PlayerMoveEvent. The
 * untracked-move poll then sees the server ahead of the last checked position and pulls the player back.
 * <p>
 * Nothing here is guessed from geometry alone. An allowance needs all of:
 * <ul>
 * <li>the server opened this box for this player (InventoryOpenEvent, not cancelled) with its lid not fully out,
 * at most {@link #WINDOW_TICKS} ticks ago;</li>
 * <li>the box's real lid progress now (its server shape) shows how many opening ticks have actually run since;</li>
 * <li>vanilla's push is replayed tick by tick with its own arithmetic from the trusted position: only a player
 * inside the slab is pushed, only along the facing;</li>
 * <li>no block collision shape in the region the leading side of the body moved through (vanilla's push would
 * have stopped there): above the head for an upward lid, ahead of the body for a sideways one, below the feet for
 * a downward one.</li>
 * </ul>
 * The trusted position only follows the push itself: an upward lid only to the real lid's top, a sideways or
 * downward lid only along its facing, exactly, and never past vanilla's replayed push.
 */
public final class ShulkerLidRide {

    /** Opening speed per tick (ShulkerBoxBlockEntity.updateAnimation). */
    public static final float STEP = 0.1F;
    /** Vanilla adds this to every push (moveCollidedEntities). */
    public static final double PUSH_EXTRA = 0.01;
    /** A lid opens in ten ticks. */
    public static final int MAX_TICKS = 10;
    /** Opening (10 ticks) plus client latency, in NCP ticks after the server opened the box. */
    public static final int WINDOW_TICKS = 30;
    /** The untracked poll's own margin (0.1 block). */
    public static final double MARGIN = 0.1;
    /** A position counts as exactly on the push axis within this (the push changes one coordinate only). */
    public static final double EXACT = 1.0e-4;

    private ShulkerLidRide() {}

    /** 0 = x, 1 = y, 2 = z. */
    public static int axis(final BlockFace face) {
        return face.getModX() != 0 ? 0 : face.getModY() != 0 ? 1 : 2;
    }

    /** +1 or -1 along {@link #axis(BlockFace)}. */
    public static int sign(final BlockFace face) {
        return face.getModX() + face.getModY() + face.getModZ();
    }

    private static boolean isLidFace(final BlockFace face) {
        return face != null && Math.abs(face.getModX()) + Math.abs(face.getModY()) + Math.abs(face.getModZ()) == 1;
    }

    /**
     * Facing of a shulker box from the server's state of that block. Reads only a loaded chunk owned by the
     * current region.
     *
     * @return null if it isn't a shulker box or can't be read here
     */
    public static BlockFace lidFacing(final World world, final int x, final int y, final int z) {
        try {
            if (!readable(world, x, y, z)) {
                return null;
            }
            final Block block = world.getBlockAt(x, y, z);
            if (block == null || block.getType() == null || !block.getType().name().endsWith("SHULKER_BOX")) {
                return null;
            }
            final BlockData blockData = block.getBlockData();
            if (!(blockData instanceof Directional)) {
                return null;
            }
            final BlockFace face = ((Directional) blockData).getFacing();
            return isLidFace(face) ? face : null;
        }
        catch (RuntimeException unavailable) {
            return null;
        }
    }

    private static boolean readable(final World world, final int x, final int y, final int z) {
        return world != null && Folia.isOwnedByCurrentRegion(new Location(world, x, y, z))
               && world.isChunkLoaded(x >> 4, z >> 4);
    }

    /**
     * Lid progress (0 closed .. 1 open) of a shulker box facing {@code face}, from the server's current shape of that
     * block: the block plus its lid, out by 0.5 * progress towards the facing. Reads only a loaded chunk owned by the
     * current region.
     *
     * @return NaN if it isn't a shulker box with that facing and a box-plus-lid shape, or can't be read here.
     */
    public static float lidProgress(final World world, final int x, final int y, final int z, final BlockFace face) {
        try {
            if (!isLidFace(face) || !readable(world, x, y, z)) {
                return Float.NaN;
            }
            final Block block = world.getBlockAt(x, y, z);
            if (block == null || block.getType() == null || !block.getType().name().endsWith("SHULKER_BOX")) {
                return Float.NaN;
            }
            final BlockData blockData = block.getBlockData();
            if (!(blockData instanceof Directional) || ((Directional) blockData).getFacing() != face) {
                return Float.NaN;
            }
            final BoundingBox box = block.getBoundingBox();
            if (box == null) {
                return Float.NaN;
            }
            final double eps = 1.0e-6;
            final int a = axis(face);
            final int s = sign(face);
            final double[] min = {box.getMinX() - x, box.getMinY() - y, box.getMinZ() - z};
            final double[] max = {box.getMaxX() - x - 1.0, box.getMaxY() - y - 1.0, box.getMaxZ() - z - 1.0};
            double lid = Double.NaN;
            for (int i = 0; i < 3; i++) {
                if (i == a && s > 0) {
                    if (Math.abs(min[i]) > eps) return Float.NaN;
                    lid = max[i];
                }
                else if (i == a) {
                    if (Math.abs(max[i]) > eps) return Float.NaN;
                    lid = -min[i];
                }
                else if (Math.abs(min[i]) > eps || Math.abs(max[i]) > eps) {
                    return Float.NaN;
                }
            }
            if (!(lid > -eps) || !(lid < 0.5 + eps)) {
                return Float.NaN;
            }
            return Math.max(0.0F, Math.min(1.0F, (float) (2.0 * lid)));
        }
        catch (RuntimeException unavailable) {
            return Float.NaN;
        }
    }

    /** {@link #lidProgress(World, int, int, int, BlockFace)} of an upward box. */
    public static float upwardLidProgress(final World world, final int x, final int y, final int z) {
        return lidProgress(world, x, y, z, BlockFace.UP);
    }

    /**
     * Number of opening ticks that took the lid from {@code from} to {@code now}, replaying vanilla's float steps.
     *
     * @return 0 if {@code now} is not reached by opening steps (closed again, or not opened at all).
     */
    public static int openingTicks(final float from, final float now) {
        if (!(from >= 0.0F) || !(now <= 1.0F) || !(now > from)) {
            return 0;
        }
        float progress = from;
        for (int ticks = 1; ticks <= MAX_TICKS; ticks++) {
            progress += STEP;
            if (progress >= 1.0F) {
                progress = 1.0F;
            }
            if (Math.abs(progress - now) <= 1.0e-4F) {
                return ticks;
            }
            if (progress > now || progress == 1.0F) {
                return 0;
            }
        }
        return 0;
    }

    /**
     * Vanilla's total push (along the facing) of a player in front of the box's lid face during {@code ticks} opening
     * ticks from lid progress {@code from}. Each tick the slab between the old and the new progress (unhalved, out of
     * the lid face) pushes an entity whose box intersects it by the slab's thickness plus 0.01.
     *
     * @param feet  distance of the player's near side from the lid face at the start (feet above the top face for
     *              an upward box)
     * @param height player's bounding box extent along the facing (its height for an upward box)
     */
    public static double vanillaRide(final float from, final int ticks, final double feet, final double height) {
        float progress = from;
        double at = feet;
        double ride = 0.0;
        for (int i = 0; i < ticks && i < MAX_TICKS; i++) {
            final float old = progress;
            progress += STEP;
            if (progress >= 1.0F) {
                progress = 1.0F;
            }
            // The slab out of the lid face (vanilla: Shulker.getProgressDeltaAabb with unhalved progress).
            final double bottom = (double) old;
            final double top = (double) progress;
            if (at < top && at + height > bottom) {
                final double push = top - bottom + PUSH_EXTRA;
                at += push;
                ride += push;
            }
        }
        return ride;
    }

    /**
     * Whether the server position lies on the vanilla push path of an upward lid from the trusted position: within
     * the poll's margin of the segment trusted .. trusted + ride (straight up), and never above the swept slab's top
     * plus one push.
     *
     * @param lidNow lid progress now
     * @param faceY  y of the box's top face
     */
    public static boolean onPushPath(final double tx, final double ty, final double tz,
                                     final double px, final double py, final double pz,
                                     final double ride, final float lidNow, final double faceY) {
        return onPushPath(new double[] {tx, ty, tz}, new double[] {px, py, pz}, 1, 1, ride, lidNow, py - faceY);
    }

    /**
     * Whether the server position {@code p} lies on the vanilla push path from the trusted position {@code t} along
     * {@code axis} in direction {@code sign}: within the poll's margin of the segment t .. t + ride, and the near side
     * of the body never further out of the lid face than the swept slab's top plus one push.
     *
     * @param nearNow distance of the body's near side from the lid face at {@code p}
     */
    static boolean onPushPath(final double[] t, final double[] p, final int axis, final int sign,
                              final double ride, final float lidNow, final double nearNow) {
        double off = 0.0;
        for (int i = 0; i < 3; i++) {
            final double d = p[i] - t[i];
            if (i == axis) {
                final double along = sign * d;
                final double rest = along - Math.max(0.0, Math.min(ride, along));
                off += rest * rest;
            }
            else {
                off += d * d;
            }
        }
        if (off > MARGIN * MARGIN) {
            return false;
        }
        return nearNow <= (double) lidNow + STEP + PUSH_EXTRA + 1.0e-6;
    }

    /**
     * The server opened a shulker box for this player (InventoryOpenEvent, not cancelled): remember it if its lid
     * isn't fully out yet (only then does an opening, and a push, follow).
     */
    public static void recordOpening(final MovingData data, final Location box) {
        data.shulkerLidOpenValid = false;
        if (box == null || box.getWorld() == null) {
            return;
        }
        final BlockFace face = lidFacing(box.getWorld(), box.getBlockX(), box.getBlockY(), box.getBlockZ());
        if (face == null) {
            return;
        }
        final float lid = lidProgress(box.getWorld(), box.getBlockX(), box.getBlockY(), box.getBlockZ(), face);
        if (!(lid < 1.0F)) {
            // Not readable here, or already fully open (someone else holds it open).
            return;
        }
        data.shulkerLidOpenWorld = box.getWorld().getName();
        data.shulkerLidOpenX = box.getBlockX();
        data.shulkerLidOpenY = box.getBlockY();
        data.shulkerLidOpenZ = box.getBlockZ();
        data.shulkerLidOpenFace = face;
        data.shulkerLidOpenProgress = lid;
        data.shulkerLidOpenTick = TickTask.getTick();
        data.shulkerLidOpenValid = true;
        data.shulkerLidMoveStartLogged = false;
    }

    private static double bodyMin(final int i, final double[] at, final double[] half) {
        return i == 1 ? at[1] : at[i] - half[i];
    }

    private static double bodyMax(final int i, final double[] at, final double[] half) {
        return i == 1 ? at[1] + half[1] : at[i] + half[i];
    }

    /** Distance of the body's near side from the lid face (positive out of the box). */
    private static double near(final double[] at, final double[] half, final int a, final int s, final double face) {
        return s > 0 ? bodyMin(a, at, half) - face : face - bodyMax(a, at, half);
    }

    /**
     * Whether the untracked server position is explained by an opening shulker lid the server opened for this
     * player. Never changes the trusted position.
     *
     * @return the replayed ride if explained, else NaN
     */
    public static double explains(final Player player, final MovingData data, final Location loc) {
        if (loc == null || loc.getWorld() == null || !loc.getWorld().getName().equals(data.untrackedTrustedWorld)) {
            return Double.NaN;
        }
        return ride(player, data, loc.getWorld(), new double[] {data.untrackedTrustedX, data.untrackedTrustedY, data.untrackedTrustedZ},
                new double[] {loc.getX(), loc.getY(), loc.getZ()}, false);
    }

    /**
     * Whether the server's own lid push explains the start of a move event that NCP would split: from the event's
     * {@code from} (NCP's last checked position) to the server position {@code loc} before the client's move. Stricter
     * than {@link #explains}: exactly along the facing (the other two coordinates within {@link #EXACT}), forward, no
     * further than vanilla's replayed push.
     *
     * @return the replayed ride if explained, else NaN
     */
    public static double explainsMoveStart(final Player player, final MovingData data, final Location from, final Location loc) {
        if (from == null || loc == null || from.getWorld() == null || loc.getWorld() == null
            || !from.getWorld().getName().equals(loc.getWorld().getName())) {
            return Double.NaN;
        }
        return ride(player, data, loc.getWorld(), new double[] {from.getX(), from.getY(), from.getZ()},
                new double[] {loc.getX(), loc.getY(), loc.getZ()}, true);
    }

    /**
     * Core of {@link #explains} / {@link #explainsMoveStart}: the server moved the player from {@code t} to {@code p}
     * by the push of the box this player opened.
     *
     * @param exact only exactly along the facing, forward, within the replayed push (else the poll's margin)
     */
    private static double ride(final Player player, final MovingData data, final World world, final double[] t,
                               final double[] p, final boolean exact) {
        if (!data.shulkerLidOpenValid || !isLidFace(data.shulkerLidOpenFace)) {
            return Double.NaN;
        }
        final int age = TickTask.getTick() - data.shulkerLidOpenTick;
        if (age < 0 || age > WINDOW_TICKS || !world.getName().equals(data.shulkerLidOpenWorld)) {
            return Double.NaN;
        }
        final BlockFace face = data.shulkerLidOpenFace;
        final int[] b = {data.shulkerLidOpenX, data.shulkerLidOpenY, data.shulkerLidOpenZ};
        final float lidNow = lidProgress(world, b[0], b[1], b[2], face);
        final int ticks = openingTicks(data.shulkerLidOpenProgress, lidNow);
        if (ticks <= 0) {
            return Double.NaN;
        }
        final BoundingBox body = player.getBoundingBox();
        if (body == null) {
            return Double.NaN;
        }
        // Half width, full height, half depth.
        final double[] half = {body.getWidthX() / 2.0, body.getHeight(), body.getWidthZ() / 2.0};
        for (final double h : half) {
            if (!(h > 0.0) || !Double.isFinite(h)) {
                return Double.NaN;
            }
        }
        final int a = axis(face);
        final int s = sign(face);
        // Only an entity over the lid face (the other two axes, strict, like AABB.intersects) is in the slab.
        for (int i = 0; i < 3; i++) {
            if (i != a && !(bodyMin(i, t, half) < b[i] + 1.0 && bodyMax(i, t, half) > b[i])) {
                return Double.NaN;
            }
        }
        final double faceAt = b[a] + (s > 0 ? 1.0 : 0.0);
        final double size = bodyMax(a, t, half) - bodyMin(a, t, half);
        final double ride = vanillaRide(data.shulkerLidOpenProgress, ticks, near(t, half, a, s, faceAt), size);
        if (!(ride > 0.0) || !onPushPath(t, p, a, s, ride, lidNow, near(p, half, a, s, faceAt))) {
            return Double.NaN;
        }
        if (exact && !alongFacingOnly(t, p, a, s, ride)) {
            return Double.NaN;
        }
        // The whole box the body swept from the trusted to the server position (both bodies and everything between)
        // must hold no collision shape but the box's own (its lid pushes into the start body): vanilla's push would
        // have stopped at anything there, and a body that already is inside something isn't pushed out through it.
        final double[] min = new double[3];
        final double[] max = new double[3];
        for (int i = 0; i < 3; i++) {
            min[i] = Math.min(bodyMin(i, t, half), bodyMin(i, p, half));
            max[i] = Math.max(bodyMax(i, t, half), bodyMax(i, p, half));
        }
        if (!clearOfCollisions(world, min[0], min[1], min[2], max[0], max[1], max[2], b[0], b[1], b[2])) {
            return Double.NaN;
        }
        return ride;
    }

    /**
     * After {@link #explains} accepted the position of an upward lid's push: if the player now stands exactly on
     * top of that box's real lid (straight above the trusted position), the y the trusted position may take. The
     * lid's top is set by the server's own state of the box; x and z stay the trusted ones, so nothing but standing
     * on the lid can come of it.
     *
     * @return the lid's top y, else NaN
     */
    public static double restingOnLid(final MovingData data, final Location loc) {
        if (data.shulkerLidOpenFace != BlockFace.UP || loc == null || loc.getWorld() == null
            || !(loc.getY() > data.untrackedTrustedY)) {
            return Double.NaN;
        }
        final float lidNow = upwardLidProgress(loc.getWorld(), data.shulkerLidOpenX, data.shulkerLidOpenY, data.shulkerLidOpenZ);
        if (Float.isNaN(lidNow)) {
            return Double.NaN;
        }
        final double top = data.shulkerLidOpenY + 1.0 + (double) (0.5F * lidNow);
        return Math.abs(loc.getY() - top) <= 1.0e-4 ? top : Double.NaN;
    }

    /**
     * After {@link #explains} accepted the position of a sideways or downward lid's push (ride {@code ride}): if the
     * server moved the player exactly along the facing (the other two coordinates unchanged), by no more than
     * vanilla's replayed push, the coordinate along the facing the trusted position may take. The player then stands
     * where the server's own push put them; a client that is not moved by the push sends move events as usual.
     *
     * @return {x, y, z} for the trusted position, else null
     */
    public static double[] pushedAlongFacing(final MovingData data, final Location loc, final double ride) {
        final BlockFace face = data.shulkerLidOpenFace;
        if (!isLidFace(face) || face == BlockFace.UP || loc == null || !(ride > 0.0)) {
            return null;
        }
        final double[] t = {data.untrackedTrustedX, data.untrackedTrustedY, data.untrackedTrustedZ};
        final double[] p = {loc.getX(), loc.getY(), loc.getZ()};
        final int a = axis(face);
        if (!alongFacingOnly(t, p, a, sign(face), ride)) {
            return null;
        }
        final double[] to = t.clone();
        to[a] = p[a];
        return to;
    }

    /** Moved exactly along the facing (other coordinates within {@link #EXACT}), forward, by at most the ride. */
    static boolean alongFacingOnly(final double[] t, final double[] p, final int a, final int s, final double ride) {
        for (int i = 0; i < 3; i++) {
            if (i != a && !(Math.abs(p[i] - t[i]) <= EXACT)) {
                return false;
            }
        }
        final double along = s * (p[a] - t[a]);
        return along > 0.0 && along <= ride + EXACT;
    }

    /**
     * Distance of the body's near side from the lid face (positive out of the box) at the given position, for the
     * log line. NaN without a recorded box.
     */
    public static double nearSide(final MovingData data, final Player player, final double x, final double y, final double z) {
        final BlockFace face = data.shulkerLidOpenFace;
        final BoundingBox body = player.getBoundingBox();
        if (!isLidFace(face) || body == null) {
            return Double.NaN;
        }
        final double[] half = {body.getWidthX() / 2.0, body.getHeight(), body.getWidthZ() / 2.0};
        final int a = axis(face);
        final int s = sign(face);
        final int[] b = {data.shulkerLidOpenX, data.shulkerLidOpenY, data.shulkerLidOpenZ};
        return near(new double[] {x, y, z}, half, a, s, b[a] + (s > 0 ? 1.0 : 0.0));
    }

    /**
     * No block collision shape in the given region (strict overlap), except the block at {@code exX, exY, exZ}.
     * Blocks one cell around it are read too: a fence or wall reaches 0.5 above its cell, a shulker lid 0.5 out of
     * its cell in any direction. Every chunk this touches must be loaded and owned by the current region before any
     * block is read; otherwise false.
     */
    static boolean clearOfCollisions(final World world, final double minX, final double minY, final double minZ,
                                     final double maxX, final double maxY, final double maxZ,
                                     final int exX, final int exY, final int exZ) {
        if (!(maxX > minX) || !(maxY > minY) || !(maxZ > minZ)) {
            return true;
        }
        final int x0 = (int) Math.floor(minX) - 1;
        final int x1 = (int) Math.floor(maxX) + 1;
        final int y0 = (int) Math.floor(minY) - 1;
        final int y1 = (int) Math.floor(maxY) + 1;
        final int z0 = (int) Math.floor(minZ) - 1;
        final int z1 = (int) Math.floor(maxZ) + 1;
        try {
            for (int cx = x0 >> 4; cx <= x1 >> 4; cx++) {
                for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) {
                    if (!Folia.isOwnedByCurrentRegion(new Location(world, cx << 4, y0, cz << 4))
                        || !world.isChunkLoaded(cx, cz)) {
                        return false;
                    }
                }
            }
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    for (int y = y0; y <= y1; y++) {
                        if (x == exX && y == exY && z == exZ) {
                            continue;
                        }
                        final Block block = world.getBlockAt(x, y, z);
                        if (block == null) {
                            return false;
                        }
                        for (final BoundingBox box : block.getCollisionShape().getBoundingBoxes()) {
                            if (x + box.getMinX() < maxX && x + box.getMaxX() > minX
                                && y + box.getMinY() < maxY && y + box.getMaxY() > minY
                                && z + box.getMinZ() < maxZ && z + box.getMaxZ() > minZ) {
                                return false;
                            }
                        }
                    }
                }
            }
            return true;
        }
        catch (RuntimeException unavailable) {
            return false;
        }
    }
}
