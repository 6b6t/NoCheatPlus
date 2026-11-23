/*
 * This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package fr.neatmonster.nocheatplus.checks.moving.magic;

import java.util.Collection;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

import fr.neatmonster.nocheatplus.utilities.location.PlayerLocation;
import fr.neatmonster.nocheatplus.utilities.map.BlockCache;
import fr.neatmonster.nocheatplus.utilities.map.BlockFlags;
import fr.neatmonster.nocheatplus.utilities.map.BlockProperties;

/** Holds stale ground-jump credit when the player is fully embedded in blocks. */
public final class InBlockJumpGuard {

    private static final double EPSILON = 1.0E-7;

    private InBlockJumpGuard() {}

    public static boolean holdStaleGroundJump(final PlayerLocation from, final Collection<String> tags) {
        // A short ground probe is the same lost-ground signal used by the allowed S2 corner route.
        if (from.isOnGround(0.03)) return false;
        final BlockCache cache = from.getBlockCache();
        final World world = from.getWorld();
        if (cache == null || world == null) return false;

        final int feetY = Location.locToBlock(from.getMinY() + EPSILON);
        final int minX = Location.locToBlock(from.getMinX());
        final int maxX = Location.locToBlock(from.getMaxX() - EPSILON);
        final int minZ = Location.locToBlock(from.getMinZ());
        final int maxZ = Location.locToBlock(from.getMaxZ() - EPSILON);

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                final Material type = cache.getType(x, feetY, z);
                final long flags = BlockFlags.getBlockFlags(type);
                if ((flags & (BlockFlags.F_SOLID | BlockFlags.F_GROUND))
                        != (BlockFlags.F_SOLID | BlockFlags.F_GROUND)
                        || (flags & BlockFlags.F_LIQUID) != 0 || !cache.isFullBounds(x, feetY, z)) {
                    return false;
                }
            }
        }

        final int headY = feetY + 1;
        if (from.getMaxY() <= headY + EPSILON) return false;
        boolean headBlocked = false;
        for (int x = minX; x <= maxX && !headBlocked; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                final double[] bounds = cache.getBounds(x, headY, z);
                if (bounds != null && (BlockProperties.isFullBounds(bounds)
                        || bounds.length >= 6
                        && bounds[0] < bounds[3] && bounds[1] < bounds[4] && bounds[2] < bounds[5])) {
                    headBlocked = true;
                    break;
                }
            }
        }
        if (!headBlocked) return false;
        tags.add("phaseguard_hold");
        return true;
    }
}
