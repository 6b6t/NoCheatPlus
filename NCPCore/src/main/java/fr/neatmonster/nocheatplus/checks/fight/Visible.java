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

import fr.neatmonster.nocheatplus.actions.ParameterName;
import fr.neatmonster.nocheatplus.checks.Check;
import fr.neatmonster.nocheatplus.checks.CheckType;
import fr.neatmonster.nocheatplus.checks.ViolationData;
import fr.neatmonster.nocheatplus.checks.moving.util.MovingUtil;
import fr.neatmonster.nocheatplus.compat.MCAccess;
import fr.neatmonster.nocheatplus.compat.blocks.changetracker.BlockChangeTracker.Direction;
import fr.neatmonster.nocheatplus.utilities.StringUtil;
import fr.neatmonster.nocheatplus.utilities.collision.Axis;
import fr.neatmonster.nocheatplus.utilities.collision.CollisionUtil;
import fr.neatmonster.nocheatplus.utilities.collision.InteractAxisTracing;
import fr.neatmonster.nocheatplus.utilities.collision.CollisionUtil.RichAxisData;
import fr.neatmonster.nocheatplus.utilities.ds.map.BlockCoord;
import fr.neatmonster.nocheatplus.utilities.location.TrigUtil;
import fr.neatmonster.nocheatplus.utilities.map.BlockCache;
import java.util.HashSet;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 *
 * @author xaw3ep
 */
public class Visible extends Check{

    /** Max steps per trace. */
    private static final int MAX_STEPS = 30;

    /*
     * No per-call state in fields: every region thread runs this check on the same instance. The block cache and
     * the ray tracing are created per call (they were shared fields, a data race between region threads).
     */

    public Visible() {
        super(CheckType.FIGHT_VISIBLE);
    }

    public boolean check(final Player player, final Location loc,
            final Entity damaged, final boolean damagedIsFake, final Location dLoc,
            final FightData data, final FightConfig cc) {
        if (damaged.getType() != null && "END_CRYSTAL".equals(damaged.getType().name())) {
            // crystal raytrace allowed by poll 23 (2 Nov 2026)
            return false;
        }

        final int obstruction = getObstructionCode(player, loc, damaged, damagedIsFake, dLoc);
        if (obstruction == 0) {
            data.visibleVL *= 0.99;
            return false;
        }
        data.visibleVL += 1.0;
        final ViolationData vd = new ViolationData(this, player, data.visibleVL, 1.0, cc.visibleActions);
        fr.neatmonster.nocheatplus.telemetry.Telemetry.tags(vd,
                obstruction == 2 ? "raytracing_maxsteps" : "raytracing");
        return executeActions(vd).willCancel();
    }

    /** Ray trace only; callers such as sweep handling must not add visible VL or run actions. */
    public boolean isObstructed(final Player player, final Location loc, final Entity damaged,
            final boolean damagedIsFake, final Location dLoc) {
        return getObstructionCode(player, loc, damaged, damagedIsFake, dLoc) != 0;
    }

    private int getObstructionCode(final Player player, final Location loc, final Entity damaged,
            final boolean damagedIsFake, final Location dLoc) {
        final MCAccess mcAccess = this.mcAccess.getHandle();
        if (!damagedIsFake && mcAccess.isComplexPart(damaged)) {
            return 0;
        }

        final double width = damagedIsFake ? 0.6 : mcAccess.getWidth(damaged);
        final double height = damagedIsFake
                ? (damaged instanceof LivingEntity ? ((LivingEntity) damaged).getEyeHeight() : 1.75)
                : mcAccess.getHeight(damaged);
        final double dxz = Math.round(width * 500.0) / 1000.0;
        final double dminX = dLoc.getX() - dxz;
        final double dminY = dLoc.getY();
        final double dminZ = dLoc.getZ() - dxz;
        final double dmaxX = dLoc.getX() + dxz;
        final double dmaxY = dLoc.getY() + height;
        final double dmaxZ = dLoc.getZ() + dxz;
        final int dBX = Location.locToBlock(dLoc.getX());
        final int dBY = Location.locToBlock(dLoc.getY());
        final int dBZ = Location.locToBlock(dLoc.getZ());

        final double eyeX = loc.getX();
        final double eyeY = loc.getY() + MovingUtil.getEyeHeight(player);
        final double eyeZ = loc.getZ();
        final BlockCoord sCollidingBox = new BlockCoord(Location.locToBlock(dminX), Location.locToBlock(dminY), Location.locToBlock(dminZ));
        final BlockCoord eCollidingBox = new BlockCoord(Location.locToBlock(dmaxX), Location.locToBlock(dmaxY), Location.locToBlock(dmaxZ));
        if (CollisionUtil.isInsideAABBIncludeEdges(eyeX, eyeY, eyeZ, dminX, dminY, dminZ, dmaxX, dmaxY, dmaxZ)) {
            return 0;
        }

        final BlockCache blockCache = mcAccess.getBlockCache();
        blockCache.setAccess(loc.getWorld());
        final InteractAxisTracing rayTracing = new InteractAxisTracing();
        rayTracing.setMaxSteps(MAX_STEPS);
        rayTracing.setBlockCache(blockCache);
        rayTracing.set(dLoc.getX(), dLoc.getY(), dLoc.getZ(), eyeX, eyeY, eyeZ);
        rayTracing.loop();
        boolean obstructed = rayTracing.collides();
        if (obstructed) {
            BlockCoord bc = new BlockCoord(dBX, dBY, dBZ);
            Vector direction = new Vector(eyeX - dBX, eyeY - dBY, eyeZ - dBZ).normalize();
            boolean canContinue;
            Set<BlockCoord> visited = new HashSet<>();
            RichAxisData axisData = new RichAxisData(Axis.NONE, Direction.NONE);
            do {
                canContinue = false;
                for (BlockCoord neighbor : CollisionUtil.getNeighborsInDirection(bc, direction, eyeX, eyeY, eyeZ, axisData)) {
                    if (CollisionUtil.canPassThrough(rayTracing, blockCache, bc, neighbor.getX(), neighbor.getY(), neighbor.getZ(), direction, eyeX, eyeY, eyeZ, MovingUtil.getEyeHeight(player), sCollidingBox, eCollidingBox, false, axisData)
                            && CollisionUtil.correctDir(neighbor.getY(), dBY, Location.locToBlock(eyeY), sCollidingBox.getY(), eCollidingBox.getY())
                            && !visited.contains(neighbor)) {
                        if (TrigUtil.isSameBlock(neighbor.getX(), neighbor.getY(), neighbor.getZ(), eyeX, eyeY, eyeZ)) {
                            obstructed = false;
                            break;
                        }
                        visited.add(neighbor);
                        rayTracing.set(neighbor.getX(), neighbor.getY(), neighbor.getZ(), eyeX, eyeY, eyeZ);
                        rayTracing.loop();
                        canContinue = true;
                        obstructed = rayTracing.collides();
                        bc = new BlockCoord(neighbor.getX(), neighbor.getY(), neighbor.getZ());
                        direction = new Vector(eyeX - neighbor.getX(), eyeY - neighbor.getY(), eyeZ - neighbor.getZ()).normalize();
                        break;
                    }
                }
            } while (obstructed && canContinue);
        }
        final boolean maxSteps = rayTracing.getStepsDone() > rayTracing.getMaxSteps();
        if (maxSteps) {
            obstructed = true;
        }
        rayTracing.cleanup();
        blockCache.cleanup();
        return maxSteps ? 2 : obstructed ? 1 : 0;
    }

}
