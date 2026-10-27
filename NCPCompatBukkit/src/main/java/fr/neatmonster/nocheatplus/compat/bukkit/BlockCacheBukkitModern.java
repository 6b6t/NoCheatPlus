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
import org.bukkit.util.VoxelShape;
import org.bukkit.block.Block;
import fr.neatmonster.nocheatplus.utilities.ReflectionUtil;
import fr.neatmonster.nocheatplus.utilities.ds.map.CoordMap;
import fr.neatmonster.nocheatplus.utilities.ds.map.CoordHashMap;
import fr.neatmonster.nocheatplus.utilities.map.MapUtil;

import fr.neatmonster.nocheatplus.compat.Folia;
import fr.neatmonster.nocheatplus.compat.bukkit.model.BukkitShapeModel;
import fr.neatmonster.nocheatplus.compat.bukkit.model.BedrockBlockShapes;
import fr.neatmonster.nocheatplus.utilities.map.MaterialUtil;
import fr.neatmonster.nocheatplus.players.IPlayerData;


/**
 * BlockCache for MCAccessBukkitModern.
 * 
 * @author asofold
 *
 */
public class BlockCacheBukkitModern extends BlockCacheBukkit {

    private Map<Material, BukkitShapeModel> shapeModels;
    private final CoordMap<double[]> collisionBounds = new CoordHashMap<double[]>(23);
    private final CoordMap<double[]> bedrockBounds = new CoordHashMap<double[]>(23);
    private boolean verifiedBedrockProfile;
    private static final boolean HAS_COLLISION_SHAPE =
            ReflectionUtil.getMethodNoArgs(Block.class, "getCollisionShape") != null;

    public BlockCacheBukkitModern(Map<Material, BukkitShapeModel> shapeModels) {
        super(null);
        this.shapeModels = shapeModels;
    }

    public BlockCacheBukkitModern(World world) {
        super(world);
    }

    @Override
    public void setPlayerData(final IPlayerData playerData) {
        super.setPlayerData(playerData);
        verifiedBedrockProfile = isBedrockCache()
                && BedrockBlockShapes.supportsVersion(playerData.getBedrockVersion());
    }

    @Override
    public double[] getCollisionBounds(final int x, final int y, final int z) {
        // Guard even cached queries: an unavailable cell never authorizes selection.
        if (!HAS_COLLISION_SHAPE || world == null || y < world.getMinHeight()
                || y >= world.getMaxHeight() || !MapUtil.areChunksLoadedAndOwned(world, x, z, 0.0)) return null;
        final double[] cached = collisionBounds.get(x, y, z);
        if (cached != null) return cached;
        final VoxelShape shape = world.getBlockAt(x, y, z).getCollisionShape();
        if (shape == null) return null;
        final java.util.Collection<BoundingBox> boxes = shape.getBoundingBoxes();
        final double[] bounds = new double[boxes.size() * 6];
        int i = 0;
        for (final BoundingBox box : boxes) {
            bounds[i++] = box.getMinX(); bounds[i++] = box.getMinY(); bounds[i++] = box.getMinZ();
            bounds[i++] = box.getMaxX(); bounds[i++] = box.getMaxY(); bounds[i++] = box.getMaxZ();
        }
        collisionBounds.put(x, y, z, bounds);
        return bounds;
    }

    @Override
    public void cleanup() {
        super.cleanup();
        collisionBounds.clear();
        bedrockBounds.clear();
        verifiedBedrockProfile = false;
    }

    @Override
    public double[] getBedrockBounds(final int x, final int y, final int z) {
        if (!isBedrockCache() || !verifiedBedrockProfile
                || world == null || y < world.getMinHeight() || y >= world.getMaxHeight()
                || !MapUtil.areChunksLoadedAndOwned(world, x, z, 0.0)) return null;
        final double[] cached = bedrockBounds.get(x, y, z);
        if (cached != null) return cached;
        if (!BedrockBlockShapes.contains(getType(x, y, z))) return null;
        final String state = world.getBlockAt(x, y, z).getBlockData().getAsString();
        if (!BedrockBlockShapes.permitsVanillaProfile(state)) return null;
        final double[] bounds = BedrockBlockShapes.get(state);
        if (bounds != null) bedrockBounds.put(x, y, z, bounds);
        return bounds;
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
        final Material mat = getType(x, y, z);
        if (mat == Material.ENDER_CHEST && isBedrockCache()) {
            // Bedrock ender chests are wider and 0.075 blocks taller than Java's.
            return new double[] {0.025, 0.0, 0.025, 0.975, 0.95, 0.975};
        }

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
        if (fr.neatmonster.nocheatplus.compat.versions.ServerVersion.compareMinecraftVersion("26.1") >= 0
                && entity instanceof org.bukkit.entity.Player) {
            // Movement support: the live rule as live, or native support under
            // the player's real footprint (never the caller's widened box).
            return standsOnEntityLive(entity)
                    || standsOnNativeEntity((org.bukkit.entity.Player) entity, minX, minY, minZ, maxX, maxY, maxZ, false);
        }
        return standsOnEntityLive(entity)
                || entity instanceof Player && standsOnEntityAt((Player) entity, minX, minY, minZ, maxX, maxY, maxZ);
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
        return standsOnEntityLive(entity);
    }

    @Override
    public boolean standsOnEntityTop(final Entity entity, final double minX, final double minY,
            final double minZ, final double maxX, final double maxY, final double maxZ) {
        if (!(entity instanceof Player) || !Folia.isOwnedByCurrentRegion(entity)
                || fr.neatmonster.nocheatplus.compat.versions.ServerVersion.compareMinecraftVersion("26.1") < 0) {
            return false;
        }
        return nativeEntitySupport((Player) entity, minX, minY, minZ, maxX, maxY, maxZ, true, true);
    }

    @Override
    public boolean stepsOnEntityTop(final Entity entity, final double minX, final double minY,
            final double minZ, final double maxX, final double maxY, final double maxZ,
            final double fromMinX, final double fromMinZ, final double fromMaxX, final double fromMaxZ) {
        if (!(entity instanceof Player) || !Folia.isOwnedByCurrentRegion(entity)
                || fr.neatmonster.nocheatplus.compat.versions.ServerVersion.compareMinecraftVersion("26.1") < 0) {
            return false;
        }
        return nativeEntitySupport((Player) entity, minX, minY, minZ, maxX, maxY, maxZ, true, true,
                fromMinX, fromMinZ, fromMaxX, fromMaxZ);
    }

    @Override
    public boolean touchesCollidableEntitySide(final Entity entity, final double minX, final double minY,
            final double minZ, final double maxX, final double maxY, final double maxZ, final double xzMargin,
            final double minSideHeight, final double maxTop) {
        if (!(entity instanceof org.bukkit.entity.Player) || Double.isNaN(maxTop) || Double.isInfinite(maxTop)
                || !(maxTop > minY + minSideHeight) || !(xzMargin >= 0.0) || !(maxY > minY)
                || fr.neatmonster.nocheatplus.compat.versions.ServerVersion.compareMinecraftVersion("26.1") < 0) {
            return false;
        }
        final org.bukkit.entity.Player player = (org.bukkit.entity.Player) entity;
        if (!Folia.isOwnedByCurrentRegion(player)) return false;
        boolean touched = false;
        try {
            for (final Entity other : player.getNearbyEntities(2.0, 4.0, 2.0)) {
                if (!Folia.isOwnedByCurrentRegion(other) || !other.isValid()
                        || other.getUniqueId().equals(player.getUniqueId())) continue;
                final BoundingBox box = other.getBoundingBox();
                if (box.getMaxX() < minX - xzMargin || box.getMinX() > maxX + xzMargin
                        || box.getMaxZ() < minZ - xzMargin || box.getMinZ() > maxZ + xzMargin) continue;
                // Beside the player: top above the feet (else it is ground), bottom below the head.
                if (box.getMaxY() <= minY + minSideHeight || box.getMinY() >= maxY) continue;
                if (!NativeLandingAccess.canCollide(player, other)) continue;
                // Vanilla jumpOutOfFluid needs the box 0.6 higher to be free: a taller obstacle blocks the climb.
                if (box.getMaxY() > maxTop) return false;
                touched = true;
            }
        } catch (RuntimeException crossRegion) {
            // Ownership/lookup failure cannot manufacture a climb.
            return false;
        }
        return touched;
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
            return nativeEntitySupport(player, cx - half, minY, cz - half, cx + half, maxY, cz + half, false, false);
        }
        return nativeEntitySupport(player, minX, minY, minZ, maxX, maxY, maxZ, true, false);
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
            final double minZ, final double maxX, final double maxY, final double maxZ, final boolean contact,
            final boolean strictTop) {
        return nativeEntitySupport(player, minX, minY, minZ, maxX, maxY, maxZ, contact, strictTop,
                Double.NaN, Double.NaN, Double.NaN, Double.NaN);
    }

    private boolean nativeEntitySupport(final Player player, final double minX, final double minY,
            final double minZ, final double maxX, final double maxY, final double maxZ, final boolean contact,
            final boolean strictTop, final double fromMinX, final double fromMinZ,
            final double fromMaxX, final double fromMaxZ) {
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
                        || strictTop && !MaterialUtil.isBoatOrRaft(other.getType())
                        || !NativeLandingAccess.canCollide(player, other)) continue;
                final org.bukkit.util.BoundingBox box = other.getBoundingBox();
                if (box.getMaxX() <= minX || box.getMinX() >= maxX
                        || box.getMaxZ() <= minZ || box.getMinZ() >= maxZ) continue;
                if (strictTop) {
                    if (box.getMaxX() > fromMinX && box.getMinX() < fromMaxX
                            && box.getMaxZ() > fromMinZ && box.getMinZ() < fromMaxZ) continue;
                    if (box.getMaxY() >= minY && box.getMaxY() <= maxY) return true;
                    continue;
                }
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
