package fr.neatmonster.nocheatplus.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.bukkit.Material;
import org.junit.Test;

import fr.neatmonster.nocheatplus.logging.StaticLog;
import fr.neatmonster.nocheatplus.utilities.collision.CollisionUtil;
import fr.neatmonster.nocheatplus.utilities.map.BlockFlags;
import fr.neatmonster.nocheatplus.utilities.map.FakeBlockCache;

public class TestVisibility {

    private static final double[] FULL = {0.0, 0.0, 0.0, 1.0, 1.0, 1.0};

    public TestVisibility() {
        StaticLog.setUseLogManager(false);
        BlockTests.initBlockProperties();
        StaticLog.setUseLogManager(true);
        // Not set up in the test environment, same as BlocksMC1_5 and the fence setup.
        BlockFlags.setBlockFlags(Material.SNOW, BlockFlags.F_HEIGHT_8_INC | BlockFlags.F_XZ100 | BlockFlags.F_GROUND_HEIGHT | BlockFlags.F_GROUND);
        BlockFlags.setBlockFlags(Material.OAK_FENCE, BlockFlags.F_SOLID | BlockFlags.F_GROUND | BlockFlags.F_HEIGHT150 | BlockFlags.F_THICK_FENCE);
    }

    /** Eye at (0.5, eyeY, 0.5), stone target block at (3, 65, 0), optional block at (2, 65, 0) in between. */
    private static boolean canSeeTarget(final double eyeY, final Material front, final int data, final double[] frontBounds) {
        final FakeBlockCache bc = new FakeBlockCache();
        bc.set(3, 65, 0, Material.STONE);
        if (front != null) {
            bc.set(2, 65, 0, front, data, frontBounds);
        }
        final boolean visible = CollisionUtil.canSeeBox(bc, 0.5, eyeY, 0.5, 3, 65, 0, 4, 66, 1, 3, 65, 0);
        bc.cleanup();
        return visible;
    }

    @Test
    public void testLineOfSight() {
        assertTrue("Nothing in between.", canSeeTarget(65.62, null, 0, null));
        assertFalse("Full block in front of the target.", canSeeTarget(65.62, Material.STONE, 0, FULL));
        assertTrue("Top part visible over a bottom slab.", canSeeTarget(65.62, Material.STONE, 0, new double[]{0.0, 0.0, 0.0, 1.0, 0.5, 1.0}));
        // NCP keeps snow bounds full, the layers are in the data (layers - 1).
        assertTrue("Visible over one snow layer.", canSeeTarget(65.62, Material.SNOW, 0, FULL));
        assertFalse("Eight snow layers are a full block.", canSeeTarget(65.62, Material.SNOW, 7, FULL));
        // Fences collide up to 1.5 but look 1.0 high, lines just below the top are blocked.
        assertFalse("Fence top hides the target.", canSeeTarget(65.9, Material.OAK_FENCE, 0, new double[]{0.0, 0.0, 0.0, 1.0, 1.5, 1.0}));
    }

    @Test
    public void testAllSides() {
        final int[][] directions = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (final int[] d : directions) {
            // Block at (0, 65, 0), target right behind it, eye 1.7 blocks in front of the block's center.
            final int tx = d[0], ty = 65 + d[1], tz = d[2];
            for (final boolean between : new boolean[] {false, true}) {
                final FakeBlockCache bc = new FakeBlockCache();
                bc.set(tx, ty, tz, Material.STONE);
                if (between) {
                    bc.set(0, 65, 0, Material.STONE);
                }
                final boolean visible = CollisionUtil.canSeeBox(bc, 0.5 - 1.7 * d[0], 65.5 - 1.7 * d[1], 0.5 - 1.7 * d[2],
                        tx, ty, tz, tx + 1, ty + 1, tz + 1, tx, ty, tz);
                bc.cleanup();
                assertEquals("Direction " + Arrays.toString(d) + ", block between: " + between, !between, visible);
            }
        }
    }
}
