package fr.neatmonster.nocheatplus.test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.bukkit.Material;
import org.junit.Test;

import fr.neatmonster.nocheatplus.logging.StaticLog;
import fr.neatmonster.nocheatplus.utilities.collision.CollisionUtil;
import fr.neatmonster.nocheatplus.utilities.map.FakeBlockCache;

public class TestVisibility {

    public TestVisibility() {
        StaticLog.setUseLogManager(false);
        BlockTests.initBlockProperties();
        StaticLog.setUseLogManager(true);
    }

    /** Eye at (0.5, 65.62, 0.5), stone target block at (3, 65, 0), optional block at (2, 65, 0) in between. */
    private static boolean canSeeTarget(final double[] frontBounds) {
        final FakeBlockCache bc = new FakeBlockCache();
        bc.set(3, 65, 0, Material.STONE);
        if (frontBounds != null) {
            bc.set(2, 65, 0, Material.STONE, frontBounds);
        }
        final boolean visible = CollisionUtil.canSeeBox(bc, 0.5, 65.62, 0.5, 3, 65, 0, 4, 66, 1, 3, 65, 0);
        bc.cleanup();
        return visible;
    }

    @Test
    public void testLineOfSight() {
        assertTrue("Nothing in between.", canSeeTarget(null));
        assertFalse("Full block in front of the target.", canSeeTarget(new double[]{0.0, 0.0, 0.0, 1.0, 1.0, 1.0}));
        assertTrue("Top part visible over a bottom slab.", canSeeTarget(new double[]{0.0, 0.0, 0.0, 1.0, 0.5, 1.0}));
    }
}
