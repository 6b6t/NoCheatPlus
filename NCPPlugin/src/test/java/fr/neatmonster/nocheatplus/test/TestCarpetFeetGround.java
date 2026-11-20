package fr.neatmonster.nocheatplus.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.junit.Test;

import fr.neatmonster.nocheatplus.checks.moving.magic.Magic;
import fr.neatmonster.nocheatplus.logging.StaticLog;
import fr.neatmonster.nocheatplus.utilities.location.RichBoundsLocation;
import fr.neatmonster.nocheatplus.utilities.map.BlockFlags;
import fr.neatmonster.nocheatplus.utilities.map.BlockProperties;
import fr.neatmonster.nocheatplus.utilities.map.FakeBlockCache;

/**
 * Ticket 1202: a carpet in the feet block of a player who stands on a full block, feet at whole-number Y (a moss
 * carpet that bone meal grew into the feet block, or any carpet placed there). Vanilla ignores the carpet shape the
 * player already overlaps: on ground. NCP's full ground check called the block below no ground (the 2012 "spider"
 * test against the block above), so MovingListener's powder-snow re-check forced onGround false and SurvivalFly
 * looped set-backs (vdistrel, airjump), also while standing still.
 * <p>
 * Uses the live block flags (MCAccessBukkitModern, as in NCP telemetry): carpets GROUND|CARPET, moss block
 * SOLID|GROUND|HEIGHT100|XZ100. The unit-test MCAccessBukkit guesses carpets as uncertain and adds IGN_PASSABLE,
 * which takes a different path and hides the bug.
 */
public class TestCarpetFeetGround {

    private static final double[] CARPET_SHAPE = {0.0, 0.0, 0.0, 1.0, 0.0625, 1.0};
    private static final double[] FULL = {0.0, 0.0, 0.0, 1.0, 1.0, 1.0};
    private static final double[] BOTTOM_SLAB = {0.0, 0.0, 0.0, 1.0, 0.5, 1.0};
    private static final Material[] CARPETS = {Material.MOSS_CARPET, Material.WHITE_CARPET, Material.RED_CARPET};
    private static final Material[] SUPPORTS = {Material.MOSS_BLOCK, Material.STONE};
    /** yOnGround margins: the probe's 0.001 and the config default. */
    private static final double[] Y_ON_GROUND = {0.001, Magic.Y_ON_GROUND_DEFAULT};
    /** Feet x/z: centre, near a corner (four columns), near an edge (two columns). */
    private static final double[][] SPOTS = {{0.5, 0.5}, {0.95, 0.95}, {0.5, 0.05}};

    public TestCarpetFeetGround() {
        StaticLog.setUseLogManager(false);
        BlockTests.initBlockProperties();
        StaticLog.setUseLogManager(true);
        // Live flags (see class comment).
        for (final Material carpet : CARPETS) {
            BlockFlags.setBlockFlags(carpet, BlockFlags.F_GROUND | BlockFlags.F_CARPET);
        }
        BlockFlags.setBlockFlags(Material.MOSS_BLOCK, BlockFlags.F_SOLID | BlockFlags.F_GROUND | BlockFlags.F_HEIGHT100 | BlockFlags.F_XZ100);
    }

    private static World world() {
        final World world = mock(World.class);
        when(world.getMaxHeight()).thenReturn(320);
        when(world.getMinHeight()).thenReturn(-64);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        return world;
    }

    /** 3x3 support blocks at y=64 (x/z -1..1); a carpet at y=65 where carpet[x + 1][z + 1]. */
    private static FakeBlockCache field(final Material support, final Material carpet, final boolean[][] where) {
        final FakeBlockCache bc = new FakeBlockCache();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                if (support != null) {
                    bc.set(x, 64, z, support, FULL);
                }
                if (where[x + 1][z + 1]) {
                    bc.set(x, 65, z, carpet, CARPET_SHAPE);
                }
            }
        }
        return bc;
    }

    private static boolean[][] all() {
        return new boolean[][] {{true, true, true}, {true, true, true}, {true, true, true}};
    }

    private static boolean[][] none() {
        return new boolean[3][3];
    }

    /** Full block check, player box (0.6 wide), yOnGround below the feet. */
    private static boolean full(final FakeBlockCache bc, final double x, final double feetY, final double z,
            final double yOnGround, final long ignoreFlags) {
        return BlockProperties.isOnGround(bc, x - 0.3, feetY - yOnGround, z - 0.3, x + 0.3, feetY, z + 0.3, ignoreFlags);
    }

    /**
     * What MovingListener ends up with for a player without leather boots: the location's ground, then the
     * powder-snow re-check (ignoreFlags F_POWDERSNOW) that sets it to false.
     */
    private static boolean listenerGround(final FakeBlockCache bc, final double x, final double feetY, final double z,
            final double yOnGround) {
        final RichBoundsLocation loc = new RichBoundsLocation(bc);
        loc.set(new Location(world(), x, feetY, z), 0.6, 1.8, yOnGround);
        if (loc.isOnGround()) {
            loc.adjustOnGround(!loc.isOnGround(yOnGround, BlockFlags.F_POWDERSNOW));
        }
        final boolean ground = loc.isOnGround();
        loc.cleanup();
        return ground;
    }

    /** All three views agree on ground. */
    private static void assertGround(final String what, final boolean expected, final FakeBlockCache bc,
            final double x, final double feetY, final double z, final double yOnGround) {
        final String at = what + " (feet " + x + ", " + feetY + ", " + z + ", yOnGround " + yOnGround + ")";
        assertEquals(at + ": full check", expected, full(bc, x, feetY, z, yOnGround, 0L));
        assertEquals(at + ": powder-snow re-check", expected, full(bc, x, feetY, z, yOnGround, BlockFlags.F_POWDERSNOW));
        assertEquals(at + ": MovingListener", expected, listenerGround(bc, x, feetY, z, yOnGround));
    }

    @Test
    public void testLiveFlags() {
        for (final Material carpet : CARPETS) {
            assertEquals(carpet + " flags", BlockFlags.F_GROUND | BlockFlags.F_CARPET, BlockFlags.getBlockFlags(carpet));
        }
        for (final Material support : SUPPORTS) {
            final long flags = BlockFlags.getBlockFlags(support);
            assertTrue(support + " is ground", (flags & BlockFlags.F_GROUND) != 0);
            assertEquals(support + " is no passable/variable block", 0L, flags & (BlockFlags.F_IGN_PASSABLE | BlockFlags.F_VARIABLE));
        }
    }

    @Test
    public void testFeetInsideCarpetOnFullBlock() {
        // The bug: fails before the fix.
        for (final Material support : SUPPORTS) {
            for (final Material carpet : CARPETS) {
                final FakeBlockCache bc = field(support, carpet, all());
                for (final double[] spot : SPOTS) {
                    for (final double yOnGround : Y_ON_GROUND) {
                        assertGround(carpet + " in the feet block on " + support, true, bc, spot[0], 65.0, spot[1], yOnGround);
                    }
                }
                bc.cleanup();
            }
        }
    }

    @Test
    public void testOnTopOfCarpetUnchanged() {
        for (final Material support : SUPPORTS) {
            for (final Material carpet : CARPETS) {
                final FakeBlockCache bc = field(support, carpet, all());
                for (final double[] spot : SPOTS) {
                    assertGround("On top of " + carpet, true, bc, spot[0], 65.0625, spot[1], 0.001);
                    assertGround("0.1 above " + carpet, false, bc, spot[0], 65.1625, spot[1], 0.001);
                }
                bc.cleanup();
            }
        }
    }

    @Test
    public void testPlainSupportUnchanged() {
        for (final Material support : SUPPORTS) {
            final FakeBlockCache bc = field(support, null, none());
            for (final double[] spot : SPOTS) {
                assertGround("On " + support + " without carpet", true, bc, spot[0], 65.0, spot[1], 0.001);
            }
            bc.cleanup();
        }
    }

    @Test
    public void testCarpetOverAirNoGround() {
        // Only a carpet, nothing below it: the fix adds no ground inside it.
        for (final Material carpet : CARPETS) {
            final FakeBlockCache bc = field(null, carpet, all());
            for (final double[] spot : SPOTS) {
                for (final double yOnGround : Y_ON_GROUND) {
                    assertGround("Inside " + carpet + " over air", false, bc, spot[0], 65.0, spot[1], yOnGround);
                }
                assertGround("Inside " + carpet + " over air", false, bc, spot[0], 65.03, spot[1], 0.001);
                assertGround("On top of " + carpet + " over air", true, bc, spot[0], 65.0625, spot[1], 0.001);
            }
            bc.cleanup();
        }
    }

    @Test
    public void testCarpetOverLowBlockNoGround() {
        // Carpet floating over a bottom slab (top at 64.5): the block below does not reach the feet, no ground.
        final FakeBlockCache bc = new FakeBlockCache();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                bc.set(x, 64, z, Material.STONE_SLAB, BOTTOM_SLAB);
                bc.set(x, 65, z, Material.MOSS_CARPET, CARPET_SHAPE);
            }
        }
        for (final double[] spot : SPOTS) {
            assertGround("Inside a carpet over a bottom slab", false, bc, spot[0], 65.0, spot[1], 0.001);
        }
        bc.cleanup();
    }

    @Test
    public void testFeetHalfwayUpTheCarpetUnchanged() {
        // Inside the carpet but above the block below by more than yOnGround: no ground, as before.
        final FakeBlockCache bc = field(Material.MOSS_BLOCK, Material.MOSS_CARPET, all());
        for (final double[] spot : SPOTS) {
            assertGround("Feet 0.03 up inside the carpet", false, bc, spot[0], 65.03, spot[1], 0.001);
        }
        bc.cleanup();
    }

    @Test
    public void testFullBlockAtFeetStillNoGround() {
        // Burrow: a full block in the feet block (feet at its bottom) is not ground, unchanged.
        for (final Material support : SUPPORTS) {
            final FakeBlockCache bc = field(support, null, none());
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    bc.set(x, 65, z, Material.STONE, FULL);
                }
            }
            for (final double[] spot : SPOTS) {
                for (final double yOnGround : Y_ON_GROUND) {
                    assertGround("Full block in the feet block over " + support, false, bc, spot[0], 65.0, spot[1], yOnGround);
                }
            }
            bc.cleanup();
        }
    }

    @Test
    public void testPartlyInFullBlockSameAsWithoutCarpet() {
        // Box over two columns: x=1 has a full block in the feet block, x=0 has a carpet (or nothing) on the support.
        // The carpet must count like air above the support, not more.
        for (final Material carpet : CARPETS) {
            final boolean[][] centre = none();
            centre[1][1] = true;
            final FakeBlockCache withCarpet = field(Material.MOSS_BLOCK, carpet, centre);
            final FakeBlockCache without = field(Material.MOSS_BLOCK, null, none());
            for (int z = -1; z <= 1; z++) {
                withCarpet.set(1, 65, z, Material.STONE, FULL);
                without.set(1, 65, z, Material.STONE, FULL);
            }
            for (final double yOnGround : Y_ON_GROUND) {
                assertGround("Partly in a full block, rest in " + carpet, full(without, 0.95, 65.0, 0.5, yOnGround, 0L),
                        withCarpet, 0.95, 65.0, 0.5, yOnGround);
            }
            withCarpet.cleanup();
            without.cleanup();
        }
    }

    @Test
    public void testPartialCarpet() {
        for (final Material carpet : CARPETS) {
            // Carpet only in the centre column. Box over the x=0/x=1 border: on ground via the bare column (worked
            // before too). Box only over the carpet column: the bug.
            final boolean[][] centre = none();
            centre[1][1] = true;
            FakeBlockCache bc = field(Material.MOSS_BLOCK, carpet, centre);
            assertGround("Carpet under part of the box", true, bc, 0.95, 65.0, 0.5, 0.001);
            assertGround("Carpet under all of the box", true, bc, 0.5, 65.0, 0.5, 0.001);
            bc.cleanup();
            // Carpets in both columns; only the centre one has a block below (before the fix: no ground).
            bc = field(null, carpet, all());
            bc.set(0, 64, 0, Material.MOSS_BLOCK, FULL);
            assertGround("Carpets, block below one of them", true, bc, 0.95, 65.0, 0.5, 0.001);
            assertGround("Carpets, only over air", false, bc, 1.5, 65.0, 0.5, 0.001);
            bc.cleanup();
        }
    }
}
