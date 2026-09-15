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
package fr.neatmonster.nocheatplus.checks.blockinteract;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import fr.neatmonster.nocheatplus.checks.Check;
import fr.neatmonster.nocheatplus.checks.CheckType;
import fr.neatmonster.nocheatplus.checks.ViolationData;
import fr.neatmonster.nocheatplus.players.IPlayerData;
import fr.neatmonster.nocheatplus.utilities.collision.CollisionUtil;
import fr.neatmonster.nocheatplus.utilities.location.TrigUtil;
import fr.neatmonster.nocheatplus.utilities.map.BlockCache;

/**
 * Check if the block can be seen from the eye position (straight line, no
 * blocks in between). Look independent.
 */
public class Visible extends Check {

    public Visible() {
        super(CheckType.BLOCKINTERACT_VISIBLE);
    }

    /**
     * @param clicked
     *            Where the player clicked, relative to the block. May be null
     *            (left clicks), then the point the look direction hits is used.
     */
    public boolean check(final Player player, final Location loc, final double eyeHeight, final Block block, final Vector clicked,
            final BlockInteractData data, final BlockInteractConfig cc, final IPlayerData pData) {
        final int blockX = block.getX();
        final int blockY = block.getY();
        final int blockZ = block.getZ();
        final double eyeX = loc.getX();
        final double eyeY = loc.getY() + eyeHeight;
        final double eyeZ = loc.getZ();
        final boolean visible;
        if (TrigUtil.isSameBlock(blockX, blockY, blockZ, eyeX, eyeY, eyeZ)) {
            // Player is interacting with the block their head is in.
            visible = true;
        }
        else {
            // New cache per call, check instances are shared between Folia region threads.
            final BlockCache blockCache = mcAccess.getHandle().getBlockCache();
            blockCache.setAccess(loc.getWorld());
            // The clicked point first: a sliver of the block can be visible between the sample points of canSeeBox.
            // Left clicks come without one, there the look direction gives the point.
            final Vector point = clicked != null ? clicked
                    : CollisionUtil.getLookPoint(eyeX, eyeY, eyeZ, loc.getDirection(), blockX, blockY, blockZ);
            visible = point != null && CollisionUtil.canSeeBlockPoint(blockCache, eyeX, eyeY, eyeZ,
                    blockX, blockY, blockZ, point.getX(), point.getY(), point.getZ())
                    || CollisionUtil.canSeeBox(blockCache, eyeX, eyeY, eyeZ,
                    blockX, blockY, blockZ, blockX + 1, blockY + 1, blockZ + 1, blockX, blockY, blockZ);
            blockCache.cleanup();
        }
        if (pData.isDebugActive(type)) {
            debug(player, "visible=" + visible + " pitch=" + loc.getPitch() + ",yaw=" + loc.getYaw());
        }
        if (visible) {
            data.visibleVL *= 0.99;
            data.addPassedCheck(this.type);
            return false;
        }
        data.visibleVL += 1;
        final ViolationData vd = new ViolationData(this, player, data.visibleVL, 1, cc.visibleActions);
        return executeActions(vd).willCancel();
    }
}
