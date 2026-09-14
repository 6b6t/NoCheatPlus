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
package fr.neatmonster.nocheatplus.checks.fight;

import fr.neatmonster.nocheatplus.checks.Check;
import fr.neatmonster.nocheatplus.checks.CheckType;
import fr.neatmonster.nocheatplus.checks.ViolationData;
import fr.neatmonster.nocheatplus.checks.moving.util.MovingUtil;
import fr.neatmonster.nocheatplus.compat.MCAccess;
import fr.neatmonster.nocheatplus.utilities.collision.CollisionUtil;
import fr.neatmonster.nocheatplus.utilities.map.BlockCache;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/**
 * Check if the attacked entity can be seen from the eye position (straight
 * line, no blocks in between). Look independent.
 *
 * @author xaw3ep
 */
public class Visible extends Check{

    public Visible() {
        super(CheckType.FIGHT_VISIBLE);
    }

    public boolean check(final Player player, final Location loc,
            final Entity damaged, final boolean damagedIsFake, final Location dLoc,
            final FightData data, final FightConfig cc) {
        final MCAccess mcAccess = this.mcAccess.getHandle();

        if (!damagedIsFake && mcAccess.isComplexPart(damaged)) {
            return false;
        }

        // Find out how wide the entity is.
        final double width = damagedIsFake ? 0.6 : mcAccess.getWidth(damaged);

        // Find out how high the entity is.
        final double height = damagedIsFake ? (damaged instanceof LivingEntity ? ((LivingEntity) damaged).getEyeHeight() : 1.75) : mcAccess.getHeight(damaged);
        final double dxz = Math.round(width * 500.0) / 1000.0; // this.width / 2; // 0.3;
        final double dminX = dLoc.getX() - dxz;
        final double dminY = dLoc.getY();
        final double dminZ = dLoc.getZ() - dxz;
        final double dmaxX = dLoc.getX() + dxz;
        final double dmaxY = dLoc.getY() + height;
        final double dmaxZ = dLoc.getZ() + dxz;

        final double eyeX = loc.getX();
        final double eyeY = loc.getY() + MovingUtil.getEyeHeight(player);
        final double eyeZ = loc.getZ();
        if (CollisionUtil.isInsideAABBIncludeEdges(eyeX, eyeY, eyeZ, dminX, dminY, dminZ, dmaxX, dmaxY, dmaxZ)) {
            return false;
        }

        // New cache per call, check instances are shared between Folia region threads.
        final BlockCache blockCache = mcAccess.getBlockCache();
        blockCache.setAccess(loc.getWorld());
        final boolean visible = CollisionUtil.canSeeBox(blockCache, eyeX, eyeY, eyeZ,
                dminX, dminY, dminZ, dmaxX, dmaxY, dmaxZ, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
        blockCache.cleanup();
        if (visible) {
            return false;
        }
        data.visibleVL += 1.0;
        final ViolationData vd = new ViolationData(this, player, data.visibleVL, 1.0, cc.visibleActions);
        return executeActions(vd).willCancel();
    }
}
