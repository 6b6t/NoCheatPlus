package fr.neatmonster.nocheatplus.compat.bukkit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.junit.Test;

import fr.neatmonster.nocheatplus.compat.Folia;
import fr.neatmonster.nocheatplus.compat.versions.ServerVersion;

/**
 * Side contact with an entity the player collides with (FluidEntityClimb, climbing out of water onto a boat). Lobby
 * geometry: water surface 61.889, floating boat 1.375 wide with its top at 62.086, player box 0.6 wide, feet 61.54.
 */
public class TestNativeEntitySide {

    private static final double FEET = 61.54;
    private static final double MAX_TOP = FEET + 0.65;

    /** Player box at x 10.0-10.6 / z 0.2-0.8; the boat east of it at 10.6-11.975. */
    private static boolean query(final BlockCacheBukkitModern cache, final Player player, final double feet,
                                 final double maxTop) {
        return cache.touchesCollidableEntitySide(player, 10.0, feet, 0.2, 10.6, feet + 1.8, 0.8, 0.1, 0.001, maxTop);
    }

    @Test
    public void boatSideCountsOnlyAsVanillaWouldClimbIt() {
        final String old = ServerVersion.getMinecraftVersion();
        ServerVersion.setMinecraftVersion("26.2");
        try (var folia = mockStatic(Folia.class); var nativeAccess = mockStatic(NativeLandingAccess.class)) {
            final Player player = mock(Player.class);
            final Boat boat = mock(Boat.class);
            final Entity cloud = mock(Entity.class);
            when(player.getUniqueId()).thenReturn(UUID.randomUUID());
            when(boat.getUniqueId()).thenReturn(UUID.randomUUID());
            when(cloud.getUniqueId()).thenReturn(UUID.randomUUID());
            when(boat.isValid()).thenReturn(true);
            when(cloud.isValid()).thenReturn(true);
            when(player.getNearbyEntities(2.0, 4.0, 2.0)).thenReturn(Arrays.asList(cloud, boat));
            when(boat.getBoundingBox()).thenReturn(new BoundingBox(10.6, 61.5235, -0.2, 11.975, 62.086, 1.175));
            when(cloud.getBoundingBox()).thenReturn(new BoundingBox(10.6, 61.0, 0.0, 11.0, 62.0, 1.0));
            folia.when(() -> Folia.isOwnedByCurrentRegion(player)).thenReturn(true);
            folia.when(() -> Folia.isOwnedByCurrentRegion(boat)).thenReturn(true);
            folia.when(() -> Folia.isOwnedByCurrentRegion(cloud)).thenReturn(true);
            nativeAccess.when(() -> NativeLandingAccess.canCollide(player, boat)).thenReturn(true);
            nativeAccess.when(() -> NativeLandingAccess.canCollide(player, cloud)).thenReturn(false);
            final BlockCacheBukkitModern cache = mock(BlockCacheBukkitModern.class, CALLS_REAL_METHODS);

            // In the water against the boat side.
            assertTrue(query(cache, player, FEET, MAX_TOP));
            // A gap within the side margin (entity position noise) still counts, more does not.
            when(boat.getBoundingBox()).thenReturn(new BoundingBox(10.68, 61.5235, -0.2, 12.055, 62.086, 1.175));
            assertTrue(query(cache, player, FEET, MAX_TOP));
            when(boat.getBoundingBox()).thenReturn(new BoundingBox(10.75, 61.5235, -0.2, 12.125, 62.086, 1.175));
            assertFalse(query(cache, player, FEET, MAX_TOP));
            when(boat.getBoundingBox()).thenReturn(new BoundingBox(10.6, 61.5235, -0.2, 11.975, 62.086, 1.175));
            // Feet at the boat top: the boat is ground, not a side.
            assertFalse(query(cache, player, 62.086, 62.086 + 0.65));
            // Vanilla isFree 0.6 higher fails: obstacle taller than allowed.
            assertFalse(query(cache, player, 61.2, 61.2 + 0.65));
            // Non-collidable entities (item frames, players, mobs) never count: cloud alone gives nothing.
            when(player.getNearbyEntities(2.0, 4.0, 2.0)).thenReturn(List.of(cloud));
            assertFalse(query(cache, player, FEET, MAX_TOP));
            when(player.getNearbyEntities(2.0, 4.0, 2.0)).thenReturn(Arrays.asList(cloud, boat));
            // Another region owns the boat: no claim.
            folia.when(() -> Folia.isOwnedByCurrentRegion(boat)).thenReturn(false);
            assertFalse(query(cache, player, FEET, MAX_TOP));
            folia.when(() -> Folia.isOwnedByCurrentRegion(boat)).thenReturn(true);
            // Not a player, or no usable bound: nothing.
            assertFalse(cache.touchesCollidableEntitySide(boat, 10.0, FEET, 0.2, 10.6, FEET + 1.8, 0.8, 0.1, 0.001, MAX_TOP));
            assertFalse(query(cache, player, FEET, Double.NaN));
            assertFalse(query(cache, player, FEET, FEET));
            // Lookup failures cannot make a contact.
            when(player.getNearbyEntities(2.0, 4.0, 2.0)).thenThrow(new IllegalStateException("cross region"));
            assertFalse(query(cache, player, FEET, MAX_TOP));
        }
        finally {
            ServerVersion.setMinecraftVersion(old);
        }
    }

    @Test
    public void tallerCollidableNeighbourBlocksTheClimb() {
        final String old = ServerVersion.getMinecraftVersion();
        ServerVersion.setMinecraftVersion("26.2");
        try (var folia = mockStatic(Folia.class); var nativeAccess = mockStatic(NativeLandingAccess.class)) {
            final Player player = mock(Player.class);
            final Boat boat = mock(Boat.class);
            final Entity shulker = mock(Entity.class);
            when(player.getUniqueId()).thenReturn(UUID.randomUUID());
            when(boat.getUniqueId()).thenReturn(UUID.randomUUID());
            when(shulker.getUniqueId()).thenReturn(UUID.randomUUID());
            when(boat.isValid()).thenReturn(true);
            when(shulker.isValid()).thenReturn(true);
            when(player.getNearbyEntities(2.0, 4.0, 2.0)).thenReturn(Arrays.asList(boat, shulker));
            when(boat.getBoundingBox()).thenReturn(new BoundingBox(10.6, 61.5235, -0.2, 11.975, 62.086, 1.175));
            // A shulker on the other side, one block tall from 61.5: its top 62.5 is above the climb bound.
            when(shulker.getBoundingBox()).thenReturn(new BoundingBox(9.0, 61.5, 0.0, 10.0, 62.5, 1.0));
            folia.when(() -> Folia.isOwnedByCurrentRegion(player)).thenReturn(true);
            folia.when(() -> Folia.isOwnedByCurrentRegion(boat)).thenReturn(true);
            folia.when(() -> Folia.isOwnedByCurrentRegion(shulker)).thenReturn(true);
            nativeAccess.when(() -> NativeLandingAccess.canCollide(player, boat)).thenReturn(true);
            nativeAccess.when(() -> NativeLandingAccess.canCollide(player, shulker)).thenReturn(true);
            final BlockCacheBukkitModern cache = mock(BlockCacheBukkitModern.class, CALLS_REAL_METHODS);
            assertFalse(query(cache, player, FEET, MAX_TOP));
            // Before 26.1 the native collision query is not used.
            nativeAccess.when(() -> NativeLandingAccess.canCollide(player, shulker)).thenReturn(false);
            assertTrue(query(cache, player, FEET, MAX_TOP));
            ServerVersion.setMinecraftVersion("1.21.4");
            assertFalse(query(cache, player, FEET, MAX_TOP));
        }
        finally {
            ServerVersion.setMinecraftVersion(old);
        }
    }
}
