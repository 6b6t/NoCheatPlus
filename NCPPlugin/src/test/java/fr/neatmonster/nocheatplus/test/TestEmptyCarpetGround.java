package fr.neatmonster.nocheatplus.test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.bukkit.Material;
import org.junit.Test;

import fr.neatmonster.nocheatplus.logging.StaticLog;
import fr.neatmonster.nocheatplus.utilities.map.BlockFlags;
import fr.neatmonster.nocheatplus.utilities.map.BlockProperties;
import fr.neatmonster.nocheatplus.utilities.map.FakeBlockCache;

/**
 * The upper part of a pale moss carpet (bottom=false) has no collision. The shape is fetched, so it has no bounds,
 * which must not count as ground: players could stand and walk on air in and above it. The test API has no pale
 * moss carpet; white carpet has the same flags (ground, carpet).
 */
public class TestEmptyCarpetGround {

    private static final Material CARPET = Material.WHITE_CARPET;
    private static final double[] CARPET_BOTTOM = {0.0, 0.0, 0.0, 1.0, 0.0625, 1.0};

    public TestEmptyCarpetGround() {
        StaticLog.setUseLogManager(false);
        BlockTests.initBlockProperties();
        StaticLog.setUseLogManager(true);
        // Same as the carpet setup in BlockProperties (pale moss carpet copies moss carpet).
        BlockFlags.setBlockFlags(CARPET, BlockFlags.F_GROUND | BlockFlags.F_CARPET);
    }

    /** Feet box x/z 0.2 - 0.8, feet at y (0.001 below, like yOnGround). */
    private static boolean onGround(final FakeBlockCache bc, final double feetY) {
        return BlockProperties.isOnGround(bc, 0.2, feetY - 0.001, 0.2, 0.8, feetY, 0.8, 0L);
    }

    @Test
    public void testNoBottomOverAir() {
        // Only the empty upper part at (0, 65, 0), air below it.
        final FakeBlockCache bc = new FakeBlockCache();
        bc.set(0, 65, 0, CARPET, null);
        for (final double feet : new double[] {65.0, 65.0625, 65.4, 66.0, 66.5}) {
            assertFalse("No ground in or above an empty carpet, feet at " + feet, onGround(bc, feet));
        }
        bc.cleanup();
    }

    @Test
    public void testOverItsBottomPart() {
        // As it grows: the bottom part at (0, 64, 0), the empty upper part at (0, 65, 0) above it.
        final FakeBlockCache bc = new FakeBlockCache();
        bc.set(0, 63, 0, Material.STONE);
        bc.set(0, 64, 0, CARPET, CARPET_BOTTOM);
        bc.set(0, 65, 0, CARPET, null);
        assertTrue("Standing on the bottom part.", onGround(bc, 64.0625));
        assertFalse("Not on ground inside the upper part.", onGround(bc, 65.3));
        assertFalse("Not on ground on top of the upper part.", onGround(bc, 66.0));
        bc.cleanup();
    }

    @Test
    public void testOtherBlocksWithoutBounds() {
        // Unchanged: other ground blocks with unknown bounds still count as ground.
        final FakeBlockCache bc = new FakeBlockCache();
        bc.set(0, 64, 0, Material.STONE, null);
        assertTrue("Ground block without bounds.", onGround(bc, 65.0));
        bc.cleanup();
    }
}
