package fr.neatmonster.nocheatplus.test;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

import org.bukkit.Material;
import org.bukkit.World;
import org.junit.BeforeClass;
import org.junit.Test;

import fr.neatmonster.nocheatplus.checks.moving.magic.InBlockStand;
import fr.neatmonster.nocheatplus.utilities.location.PlayerLocation;
import fr.neatmonster.nocheatplus.utilities.map.FakeBlockCache;

public class TestInBlockStand {

    private static final double[] FULL = {0, 0, 0, 1, 1, 1};
    private static final double[] SLAB = {0, 0, 0, 1, 0.5, 1};

    @BeforeClass
    public static void init() {
        BlockTests.initBlockProperties();
    }

    private static World world(final World.Environment environment) {
        final World world = mock(World.class);
        when(world.getEnvironment()).thenReturn(environment);
        when(world.getMaxHeight()).thenReturn(320);
        when(world.getMinHeight()).thenReturn(-64);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        return world;
    }

    private static PlayerLocation location(final World world, final FakeBlockCache cache,
                                           final double minX, final double feetY, final double minZ,
                                           final double maxX, final double maxZ) {
        final PlayerLocation loc = mock(PlayerLocation.class);
        when(loc.getWorld()).thenReturn(world);
        when(loc.getBlockCache()).thenReturn(cache);
        when(loc.getMinX()).thenReturn(minX);
        when(loc.getMaxX()).thenReturn(maxX);
        when(loc.getMinY()).thenReturn(feetY);
        when(loc.getMaxY()).thenReturn(feetY + 1.8);
        when(loc.getMinZ()).thenReturn(minZ);
        when(loc.getMaxZ()).thenReturn(maxZ);
        return loc;
    }

    private static void burrow(final FakeBlockCache cache, final int x, final int y, final int z,
                               final Material feet, final Material support) {
        cache.set(x, y, z, feet, FULL);
        cache.set(x, y - 1, z, support, FULL);
    }

    @Test
    public void fullBurrowAndDifferentFullSupportsAreEligible() {
        final World world = world(World.Environment.NORMAL);
        final FakeBlockCache cache = new FakeBlockCache();
        burrow(cache, 0, 64, 0, Material.OBSIDIAN, Material.OBSIDIAN);
        PlayerLocation loc = location(world, cache, 0.2, 64.0, 0.2, 0.8, 0.8);
        assertTrue(InBlockStand.isStanding(loc, 0.001, false, true, null));
        assertTrue(InBlockStand.isStanding(loc, 0.001, true, true, null));
        burrow(cache, 0, 64, 0, Material.OBSIDIAN, Material.BEDROCK);
        assertTrue(InBlockStand.isStanding(loc, 0.001, false, true, null));
        burrow(cache, 0, 64, 0, Material.CRYING_OBSIDIAN, Material.RED_SAND);
        assertTrue(InBlockStand.isStanding(loc, 0.001, false, true, null));
        cache.cleanup();
    }

    @Test
    public void footprintMustBeInsideFullBlocksAndNotAtTheWallFace() {
        final World world = world(World.Environment.NORMAL);
        final FakeBlockCache cache = new FakeBlockCache();
        burrow(cache, 0, 64, 0, Material.OBSIDIAN, Material.OBSIDIAN);
        final PlayerLocation spansAir = location(world, cache, 0.7, 64.0, 0.2, 1.3, 0.8);
        assertFalse(InBlockStand.isStanding(spansAir, 0.001, false, true, null));

        burrow(cache, 1, 65, 0, Material.OBSIDIAN, Material.OBSIDIAN);
        final PlayerLocation wallFace = location(world, cache, 0.7, 65.0, 0.2, 1.3, 0.8);
        assertFalse("body partly over air at the two-high wall face",
                InBlockStand.isStanding(wallFace, 0.001, false, true, null));
        cache.cleanup();
    }

    @Test
    public void feetMustBeAtTheBlockBottomAndHeadPolicyIsDirectional() {
        final World world = world(World.Environment.NORMAL);
        final FakeBlockCache cache = new FakeBlockCache();
        burrow(cache, 0, 64, 0, Material.OBSIDIAN, Material.OBSIDIAN);
        PlayerLocation loc = location(world, cache, 0.2, 64.3, 0.2, 0.8, 0.8);
        assertFalse(InBlockStand.isStanding(loc, 0.001, false, true, null));
        loc = location(world, cache, 0.2, 64.42, 0.2, 0.8, 0.8);
        assertFalse(InBlockStand.isStanding(loc, 0.001, false, true, null));

        cache.set(0, 65, 0, Material.OBSIDIAN, FULL);
        loc = location(world, cache, 0.2, 64.0, 0.2, 0.8, 0.8);
        assertTrue(InBlockStand.isStanding(loc, 0.001, false, true, null));
        assertFalse(InBlockStand.isStanding(loc, 0.001, true, true, null));
        assertFalse(InBlockStand.isStanding(loc, 0.001, false, false, null));
        cache.cleanup();
    }

    @Test
    public void partialFeetOrSupportShapesAreRejected() {
        final World world = world(World.Environment.NORMAL);
        final FakeBlockCache cache = new FakeBlockCache();
        burrow(cache, 0, 64, 0, Material.OBSIDIAN, Material.OBSIDIAN);
        final PlayerLocation loc = location(world, cache, 0.2, 64.0, 0.2, 0.8, 0.8);
        for (final Material support : new Material[] {Material.STONE_SLAB, Material.SOUL_SAND,
                Material.WHITE_CARPET, Material.AIR, Material.END_PORTAL_FRAME}) {
            cache.set(0, 63, 0, support, support == Material.STONE_SLAB ? SLAB : null);
            assertFalse("support " + support, InBlockStand.isStanding(loc, 0.001, false, true, null));
        }
        for (final Material feet : new Material[] {Material.STONE_SLAB, Material.OAK_STAIRS,
                Material.OAK_FENCE, Material.CHEST}) {
            cache.set(0, 64, 0, feet, SLAB);
            cache.set(0, 63, 0, Material.OBSIDIAN, FULL);
            assertFalse("feet " + feet, InBlockStand.isStanding(loc, 0.001, false, true, null));
        }
        cache.cleanup();
    }

    @Test
    public void netherRoofAndNewDestinationColumnsAreRejected() {
        final FakeBlockCache cache = new FakeBlockCache();
        burrow(cache, 0, 120, 0, Material.OBSIDIAN, Material.OBSIDIAN);
        final PlayerLocation loc = location(world(World.Environment.NETHER), cache, 0.2, 120.0, 0.2, 0.8, 0.8);
        assertFalse(InBlockStand.isStanding(loc, 0.001, false, true, null));
        assertTrue(InBlockStand.isStanding(location(world(World.Environment.NORMAL), cache,
                0.2, 120.0, 0.2, 0.8, 0.8), 0.001, false, true, null));
        burrow(cache, 1, 120, 0, Material.OBSIDIAN, Material.OBSIDIAN);
        final PlayerLocation to = location(world(World.Environment.NORMAL), cache, 0.7, 120.0, 0.2, 1.3, 0.8);
        final PlayerLocation from = location(world(World.Environment.NORMAL), cache, 0.2, 120.0, 0.2, 0.8, 0.8);
        assertFalse(InBlockStand.isStanding(to, 0.001, false, true, from));
        cache.cleanup();
    }
}
