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
package fr.neatmonster.nocheatplus.compat.bukkit;

import java.util.Map;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

import fr.neatmonster.nocheatplus.compat.Folia;
import fr.neatmonster.nocheatplus.compat.bukkit.model.BukkitShapeModel;
import fr.neatmonster.nocheatplus.utilities.map.MaterialUtil;


/**
 * BlockCache for MCAccessBukkitModern.
 * 
 * @author asofold
 *
 */
public class BlockCacheBukkitModern extends BlockCacheBukkit {

    private Map<Material, BukkitShapeModel> shapeModels;

    public BlockCacheBukkitModern(Map<Material, BukkitShapeModel> shapeModels) {
        super(null);
        this.shapeModels = shapeModels;
    }

    public BlockCacheBukkitModern(World world) {
        super(world);
    }

    @Override
    public int fetchData(int x, int y, int z) {
        Material mat = getType(x, y, z);

        final BukkitShapeModel shapeModel = shapeModels.get(mat);
        if (shapeModel != null) {
            final int data = shapeModel.getFakeData(this, world, x, y, z);
            if (data != Integer.MAX_VALUE) {
                return data;
            }
        }
        return super.fetchData(x, y, z);
    }

    @Override
    public double[] fetchBounds(int x, int y, int z) {
        // minX, minY, minZ, maxX, maxY, maxZ

        // TODO: Fetch what's possible to fetch/guess (...).

        // TODO: Consider to store the last used block/stuff within BlockCacheBukkit already.
        //final Block block = world.getBlockAt(x, y, z);
        //final BlockState state = block.getState();
        //final MaterialData materialData = state.getData();
        //final BlockData blockData = state.getBlockData();
        Material mat = getType(x, y, z);

        final BukkitShapeModel shapeModel = shapeModels.get(mat);
        if (shapeModel == null) {
            return super.fetchBounds(x, y, z);
        }
        else {
            return shapeModel.getShape(this, world, x, y, z);
        }

    }
    
    /** Float noise only (same as the native NoFall contact): no margin. */
    static final double CONTACT_EPSILON = 0.00001;

    @Override
    public boolean standsOnEntity(final Entity entity, final double minX, final double minY, final double minZ, final double maxX, final double maxY, final double maxZ){
        // 1. The live rule, exactly as live. 2. Real support at the given bounds, so also at the move's target
        // (stepping onto a boat or raft): players only.
        return standsOnEntityLive(entity)
                || entity instanceof Player && standsOnEntityAt((Player) entity, minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * The live rule, unchanged: around the entity's current position (the move's start), the first boat or shulker
     * found decides, with the live leniency (0.1 beside, feet from 0.1 below to 0.7 above the top). Not widened
     * (rafts aren't in it), not tightened.
     */
    private boolean standsOnEntityLive(final Entity entity) {
        try{
            // TODO: Probably check vehicle ids too before doing this ?
            for (final Entity vehicle : entity.getNearbyEntities(0.1, 2.0, 0.1)){
                final EntityType type = vehicle.getType();
                if (!MaterialUtil.isBoat(type) && type != EntityType.SHULKER){ //  && !(vehicle instanceof Minecart)) 
                    continue; 
                }
                final double vehicleY = vehicle.getLocation(useLoc).getY() + vehicle.getHeight();
                final double entityY = entity.getLocation(useLoc).getY();
                useLoc.setWorld(null);
                if (vehicleY < entityY + 0.1 && Math.abs(vehicleY - entityY) < 0.7){
                    // TODO: A "better" estimate is possible, though some more tolerance would be good. 
                    return true; 
                }
                else return false;
            }		
        }
        catch (Throwable t){
            // Ignore exceptions (Context: DisguiseCraft).
        }
        return false;
    }

    /**
     * Real support under a player at the given bounds (as RichEntityLocation passes them: the player's box plus a
     * horizontal margin, from the feet minus yOnGround up to the feet): a boat, bamboo raft or shulker that the
     * server lets the player collide with, overlapping the player's real footprint (the margin taken off again), its
     * top between minY and maxY. Region ownership is checked before anything of an entity is read; any failure is no
     * support.
     */
    static boolean standsOnEntityAt(final Player player, final double minX, final double minY, final double minZ,
            final double maxX, final double maxY, final double maxZ) {
        try {
            if (!Folia.isOwnedByCurrentRegion(player)) {
                return false;
            }
            // The player's real footprint: centered in the given bounds, as wide as the player.
            final double half = player.getWidth() / 2.0;
            final double halfX = Math.min(half, (maxX - minX) / 2.0);
            final double halfZ = Math.min(half, (maxZ - minZ) / 2.0);
            if (!(halfX > 0.0) || !(halfZ > 0.0) || !(maxY >= minY)) {
                return false;
            }
            final double centerX = (minX + maxX) / 2.0;
            final double centerZ = (minZ + maxZ) / 2.0;
            final double footMinX = centerX - halfX;
            final double footMaxX = centerX + halfX;
            final double footMinZ = centerZ - halfZ;
            final double footMaxZ = centerZ + halfZ;
            final World world = player.getWorld();
            if (world == null || !Folia.isOwnedByCurrentRegion(new Location(world, centerX, minY, centerZ))) {
                return false;
            }
            final BoundingBox query = new BoundingBox(footMinX, minY - 0.001, footMinZ, footMaxX, maxY + 0.001, footMaxZ);
            for (final Entity other : world.getNearbyEntities(query, e -> e != player && Folia.isOwnedByCurrentRegion(e))) {
                if (!other.isValid()) {
                    continue;
                }
                final EntityType type = other.getType();
                if (!MaterialUtil.isBoatOrRaft(type) && type != EntityType.SHULKER) {
                    continue;
                }
                if (!NativeEntityCollision.canCollide(player, other)) {
                    continue;
                }
                final BoundingBox box = other.getBoundingBox();
                if (box.getMaxX() > footMinX && box.getMinX() < footMaxX
                        && box.getMaxZ() > footMinZ && box.getMinZ() < footMaxZ
                        && box.getMaxY() >= minY - CONTACT_EPSILON && box.getMaxY() <= maxY + CONTACT_EPSILON) {
                    return true;
                }
            }
        }
        catch (Throwable t) {
            // Ownership or lookup failure: no support.
        }
        return false;
    }

}
