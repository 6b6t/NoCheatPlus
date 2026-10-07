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

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;

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
    
    @Override
    public boolean standsOnEntity(final Entity entity, final double minX, final double minY, final double minZ, final double maxX, final double maxY, final double maxZ){
        if (fr.neatmonster.nocheatplus.compat.versions.ServerVersion.compareMinecraftVersion("26.1") >= 0
                && entity instanceof org.bukkit.entity.Player) {
            // Movement support: the live rule as live, or native support under
            // the player's real footprint (never the caller's widened box).
            return standsOnEntityLive(entity)
                    || standsOnNativeEntity((org.bukkit.entity.Player) entity, minX, minY, minZ, maxX, maxY, maxZ, false);
        }
        return standsOnEntityLive(entity);
    }

    /**
     * The legacy geometry: the first owned boat or shulker within 0.1 beside the
     * player decides (feet from 0.1 below to 0.7 above its top). Rafts are not
     * in it.
     */
    private boolean standsOnEntityLive(final Entity entity) {
        if (!fr.neatmonster.nocheatplus.compat.Folia.isOwnedByCurrentRegion(entity)) return false;
        try{
            // TODO: Probably check vehicle ids too before doing this ?
            for (final Entity vehicle : entity.getNearbyEntities(0.1, 2.0, 0.1)){
                if (!fr.neatmonster.nocheatplus.compat.Folia.isOwnedByCurrentRegion(vehicle)) continue;
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

    @Override
    public boolean standsOnEntityContact(final Entity entity, final double minX, final double minY,
            final double minZ, final double maxX, final double maxY, final double maxZ) {
        if (fr.neatmonster.nocheatplus.compat.versions.ServerVersion.compareMinecraftVersion("26.1") >= 0
                && entity instanceof org.bukkit.entity.Player) {
            return standsOnNativeEntity((org.bukkit.entity.Player) entity, minX, minY, minZ, maxX, maxY, maxZ, true);
        }
        return standsOnEntity(entity, minX, minY, minZ, maxX, maxY, maxZ);
    }

    private boolean standsOnNativeEntity(final org.bukkit.entity.Player player, final double minX, final double minY,
            final double minZ, final double maxX, final double maxY, final double maxZ, final boolean contact) {
        if (!fr.neatmonster.nocheatplus.compat.Folia.isOwnedByCurrentRegion(player)) return false;
        if (!contact) {
            // RichEntityLocation#isOnGround passes the player's box widened by
            // 0.25. As movement ground that margin is a ledge: a player up to
            // 0.24 beside a raft or boat, feet at its top, would stand on air.
            // Only the real footprint can stand on an entity.
            final double half = footprintHalfWidth(player, minX, maxX, minZ, maxZ);
            final double cx = (minX + maxX) * 0.5, cz = (minZ + maxZ) * 0.5;
            return nativeEntitySupport(player, cx - half, minY, cz - half, cx + half, maxY, cz + half, false);
        }
        return nativeEntitySupport(player, minX, minY, minZ, maxX, maxY, maxZ, true);
    }

    /** Half the player's real (scaled) width, never wider than the bounds given;
     * vanilla's 0.3 if the width is unavailable. */
    static double footprintHalfWidth(final org.bukkit.entity.Player player, final double minX, final double maxX,
            final double minZ, final double maxZ) {
        final double given = Math.max(0.0, Math.min(maxX - minX, maxZ - minZ) * 0.5);
        double half;
        try {
            half = player.getWidth() * 0.5;
        } catch (RuntimeException unavailable) {
            half = Double.NaN;
        }
        if (!(half > 0.0) || !Double.isFinite(half)) half = 0.3;
        return Math.min(half, given);
    }

    private boolean nativeEntitySupport(final org.bukkit.entity.Player player, final double minX, final double minY,
            final double minZ, final double maxX, final double maxY, final double maxZ, final boolean contact) {
        final org.bukkit.Location feet = player.getLocation();
        final boolean currentFeet = Math.abs(feet.getY() - maxY) <= 0.00001
                && Math.abs(feet.getX() - (minX + maxX) * 0.5) <= 0.00001
                && Math.abs(feet.getZ() - (minZ + maxZ) * 0.5) <= 0.00001;
        try {
            // A falling boat can leave the immediate collision neighborhood
            // while the client still displays its tracked/interpolated boxes.
            // Observe ahead of contact; support still requires a real box span.
            for (final Entity other : player.getNearbyEntities(1.5, 8.0, 1.5)) {
                if (!fr.neatmonster.nocheatplus.compat.Folia.isOwnedByCurrentRegion(other) || !other.isValid()
                        || other.getUniqueId().equals(player.getUniqueId())
                        || !NativeLandingAccess.canCollide(player, other)) continue;
                final org.bukkit.util.BoundingBox box = other.getBoundingBox();
                if (box.getMaxX() <= minX || box.getMinX() >= maxX
                        || box.getMaxZ() <= minZ || box.getMinZ() >= maxZ) continue;
                if (box.getMaxY() >= minY - 0.00001 && box.getMaxY() <= maxY + 0.00001) return true;
                // Interpolation may explain a gravity transition, but must not
                // consume NoFall descent on every point along the old path.
                // A strict contact needs native ground at actual current feet.
                final boolean swept = NativeLandingAccess.supportsSweptBoat(other, minX, maxY, minZ, maxX, maxZ);
                if (swept
                        && (!contact || currentFeet && player.isOnGround())) return true;
                // Native swept contact with a descending boat can precede its
                // next position. Only the actual current feet/collision pair
                // can select this exception, never a proposed movement endpoint.
                if (currentFeet
                        && NativeLandingAccess.hasCollisionBelow(player)
                        && Math.abs(box.getMaxY() - maxY) <= 0.85) return true;
            }
        } catch (RuntimeException crossRegion) {
            // Ownership/lookup failure cannot manufacture support.
        }
        return false;
    }

}
