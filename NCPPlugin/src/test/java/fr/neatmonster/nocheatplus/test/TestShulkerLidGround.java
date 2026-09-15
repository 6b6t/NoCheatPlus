package fr.neatmonster.nocheatplus.test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.bukkit.Material;
import org.junit.Test;

import fr.neatmonster.nocheatplus.logging.StaticLog;
import fr.neatmonster.nocheatplus.utilities.map.BlockFlags;
import fr.neatmonster.nocheatplus.utilities.map.BlockProperties;
import fr.neatmonster.nocheatplus.utilities.map.FakeBlockCache;

/** Ground on the part of a sideways shulker box lid that reaches into the neighbor block. */
public class TestShulkerLidGround {

    /** Open, facing north: the lid reaches to z = -0.5. */
    private static final double[] OPEN_NORTH = {0.0, 0.0, -0.5, 1.0, 1.0, 1.0};

    public TestShulkerLidGround() {
        StaticLog.setUseLogManager(false);
        BlockTests.initBlockProperties();
        StaticLog.setUseLogManager(true);
        // Same as MCAccessBukkitModern.
        BlockFlags.setBlockFlags(Material.SHULKER_BOX, BlockFlags.F_SOLID | BlockFlags.F_GROUND | BlockFlags.F_GROUND_HEIGHT);
    }

    /** Feet box x 0.2 - 0.8 on top of a block at (0, 64, 0) with the given bounds. */
    private static boolean onGround(final Material type, final double minZ, final double maxZ) {
        final FakeBlockCache bc = new FakeBlockCache();
        bc.set(0, 64, 0, type, OPEN_NORTH);
        final boolean ground = BlockProperties.isOnGround(bc, 0.2, 64.999, minZ, 0.8, 65.0, maxZ, 0L);
        bc.cleanup();
        return ground;
    }

    @Test
    public void testSidewaysLid() {
        assertTrue("On the part of the lid that sticks out.", onGround(Material.SHULKER_BOX, -0.9, -0.3));
        assertFalse("Just past the lid.", onGround(Material.SHULKER_BOX, -1.2, -0.6));
        assertTrue("On the box itself.", onGround(Material.SHULKER_BOX, 0.2, 0.8));
    }

    @Test
    public void testOnlyShulkerBoxesReachOut() {
        // Neighbor blocks are only checked for shulker boxes.
        assertFalse("Other blocks are not checked from the neighbor block.", onGround(Material.STONE, -0.9, -0.3));
    }
}
