package fr.neatmonster.nocheatplus.compat.bukkit;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;

import org.bukkit.Location;
import org.bukkit.entity.*;
import org.junit.Test;

import fr.neatmonster.nocheatplus.compat.Folia;
import fr.neatmonster.nocheatplus.compat.versions.ServerVersion;

/** Ownership must precede every legacy support getter, including entity type. */
public class TestNativeEntityOwnership {
    // MaterialUtil scans block registries at initialization; no Bukkit server runs in these tests.
    @org.junit.BeforeClass public static void initializeLegacyClassifier() {
        try (var bukkit = mockStatic(org.bukkit.Bukkit.class, invocation -> {
                    if (invocation.getMethod().getName().equals("getRegistry")) {
                        return mock(org.bukkit.Registry.class);
                    }
                    if (invocation.getMethod().getName().equals("getUnsafe")) {
                        return mock(org.bukkit.UnsafeValues.class, unsafe ->
                                unsafe.getMethod().getName().equals("fromLegacy")
                                        ? org.bukkit.Material.AIR : RETURNS_DEFAULTS.answer(unsafe));
                    }
                    return RETURNS_DEFAULTS.answer(invocation);
                })) {
            assertTrue(fr.neatmonster.nocheatplus.utilities.map.MaterialUtil.isBoat(EntityType.BOAT));
        }
    }

    @Test public void foreignBoatsAndShulkersAreSkipped() {
        for (Class<? extends Entity> type : List.of(Boat.class, Shulker.class)) {
            Entity foreign = mock(type, invocation -> {
                throw new AssertionError("Foreign candidate getter: " + invocation.getMethod().getName());
            });
            withSupport(foreign, null, false);
            verifyNoInteractions(foreign);
        }
    }

    @Test public void ownedBoatsAndShulkersStillSupportAfterForeignCandidate() {
        for (EntityType type : List.of(EntityType.BOAT, EntityType.SHULKER)) {
            Class<? extends Entity> candidateClass = type == EntityType.BOAT ? Boat.class : Shulker.class;
            Entity foreign = mock(candidateClass, invocation -> {
                throw new AssertionError("Foreign candidate getter: " + invocation.getMethod().getName());
            });
            Entity owned = mock(Entity.class);
            when(owned.getType()).thenReturn(type);
            when(owned.getLocation(any(Location.class))).thenAnswer(inv -> {
                Location loc = inv.getArgument(0); loc.setY(64.0); return loc;
            });
            when(owned.getHeight()).thenReturn(0.5625);
            withSupport(foreign, owned, true);
            verifyNoInteractions(foreign);
            verify(owned).getLocation(any(Location.class));
            verify(owned).getHeight();
        }
    }

    private static void withSupport(Entity foreign, Entity owned, boolean expected) {
        String old = ServerVersion.getMinecraftVersion();
        ServerVersion.setMinecraftVersion("26.2");
        try (var folia = mockStatic(Folia.class)) {
            Player player = mock(Player.class);
            folia.when(() -> Folia.isOwnedByCurrentRegion(player)).thenReturn(true);
            if (owned != null) folia.when(() -> Folia.isOwnedByCurrentRegion(owned)).thenReturn(true);
            when(player.getNearbyEntities(anyDouble(), anyDouble(), anyDouble()))
                    .thenReturn(owned == null ? List.of(foreign) : List.of(foreign, owned));
            when(player.getLocation()).thenReturn(new Location(null, 0, 64.5625, 0));
            when(player.getLocation(any(Location.class))).thenAnswer(inv -> {
                Location loc = inv.getArgument(0); loc.setY(64.5625); return loc;
            });
            BlockCacheBukkitModern cache = new BlockCacheBukkitModern(Map.of());
            assertEquals(expected, cache.standsOnEntity(player, -0.3, 64.54, -0.3, 0.3, 64.5625, 0.3));
        } finally { ServerVersion.setMinecraftVersion(old); }
    }

    @Test public void foreignPlayerIsRejectedBeforeAnyGetter() {
        String old = ServerVersion.getMinecraftVersion();
        ServerVersion.setMinecraftVersion("26.2");
        try (var folia = mockStatic(Folia.class)) {
            Player player = mock(Player.class, invocation -> {
                throw new AssertionError("Foreign player getter: " + invocation.getMethod().getName());
            });
            BlockCacheBukkitModern cache = new BlockCacheBukkitModern(Map.of());
            assertFalse(cache.standsOnEntity(player, -0.3, 64.54, -0.3, 0.3, 64.5625, 0.3));
            verifyNoInteractions(player);
        } finally { ServerVersion.setMinecraftVersion(old); }
    }
}
