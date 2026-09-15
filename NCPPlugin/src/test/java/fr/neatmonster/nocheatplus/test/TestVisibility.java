package fr.neatmonster.nocheatplus.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Random;

import org.bukkit.Material;
import org.bukkit.util.Vector;
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

    @Test
    public void testClickedSliver() {
        // Target at (3, 65, 0), wall in front 0.99 high, ceiling above it: a 0.01 slit at the target's top edge.
        final FakeBlockCache bc = new FakeBlockCache();
        bc.set(3, 65, 0, Material.STONE);
        bc.set(2, 65, 0, Material.STONE, 0, new double[]{0.0, 0.0, 0.0, 1.0, 0.99, 1.0});
        bc.set(2, 66, 0, Material.STONE);
        final double eyeY = 65.995;
        assertFalse("Sample points miss the slit.", CollisionUtil.canSeeBox(bc, 0.5, eyeY, 0.5, 3, 65, 0, 4, 66, 1, 3, 65, 0));
        assertTrue("Clicked point in the slit.", CollisionUtil.canSeeBlockPoint(bc, 0.5, eyeY, 0.5, 3, 65, 0, 0.0, 0.995, 0.5));
        assertFalse("Clicked point below the slit.", CollisionUtil.canSeeBlockPoint(bc, 0.5, eyeY, 0.5, 3, 65, 0, 0.0, 0.5, 0.5));
        bc.cleanup();
        // Clamped into the block, a point in front of a full wall doesn't count. New cache, nodes are cached once read.
        final FakeBlockCache wall = new FakeBlockCache();
        wall.set(3, 65, 0, Material.STONE);
        wall.set(2, 65, 0, Material.STONE);
        assertFalse("Point moved in front of the wall.", CollisionUtil.canSeeBlockPoint(wall, 0.5, eyeY, 0.5, 3, 65, 0, -0.9, 0.995, 0.5));
        wall.cleanup();
    }

    @Test
    public void testSeamBetweenBlocks() {
        // Two stacked full blocks in front of the target (3, 65, 0), their seam at y = 66 is no gap.
        final FakeBlockCache bc = new FakeBlockCache();
        bc.set(3, 65, 0, Material.STONE);
        bc.set(2, 65, 0, Material.STONE);
        bc.set(2, 66, 0, Material.STONE);
        assertFalse("Line exactly along the seam.", CollisionUtil.canSeeBlockPoint(bc, 0.5, 66.0, 0.5, 3, 65, 0, 0.0, 1.0, 0.5));
        assertFalse("Line a hair below the seam, point clamped onto the target's top edge.",
                CollisionUtil.canSeeBlockPoint(bc, 0.5, 66.0 - 3.0E-5, 0.5, 3, 65, 0, -0.5, 1.3, 0.5));
        assertFalse("Line a hair above the seam.", CollisionUtil.canSeeBlockPoint(bc, 0.5, 66.0 + 3.0E-5, 0.5, 3, 65, 0, 0.0, 1.0, 0.5));
        bc.cleanup();
        // Side by side, seam at z = 1.
        final FakeBlockCache side = new FakeBlockCache();
        side.set(3, 65, 0, Material.STONE);
        side.set(2, 65, 0, Material.STONE);
        side.set(2, 65, 1, Material.STONE);
        assertFalse("Line along a vertical seam.", CollisionUtil.canSeeBlockPoint(side, 0.5, 65.5, 1.0, 3, 65, 0, 0.0, 0.5, 1.0));
        side.cleanup();
        // Only touching the top of a block (nothing above it) doesn't hide the target.
        final FakeBlockCache top = new FakeBlockCache();
        top.set(3, 65, 0, Material.STONE);
        top.set(2, 65, 0, Material.STONE);
        assertTrue("Line along the top face of a single block.", CollisionUtil.canSeeBlockPoint(top, 0.5, 66.0, 0.5, 3, 65, 0, 0.0, 1.0, 0.5));
        top.cleanup();
    }

    @Test
    public void testLookPointSliver() {
        // Left clicks have no clicked point, the look direction gives one. Same slit as testClickedSliver.
        final FakeBlockCache bc = new FakeBlockCache();
        bc.set(3, 65, 0, Material.STONE);
        bc.set(2, 65, 0, Material.STONE, 0, new double[]{0.0, 0.0, 0.0, 1.0, 0.99, 1.0});
        bc.set(2, 66, 0, Material.STONE);
        final double eyeY = 65.995;
        final Vector slit = CollisionUtil.getLookPoint(0.5, eyeY, 0.5, new Vector(1.0, 0.0, 0.0), 3, 65, 0);
        assertTrue("Looking through the slit.", CollisionUtil.canSeeBlockPoint(bc, 0.5, eyeY, 0.5, 3, 65, 0, slit.getX(), slit.getY(), slit.getZ()));
        final Vector wall = CollisionUtil.getLookPoint(0.5, eyeY, 0.5, new Vector(2.5, -0.5, 0.0).normalize(), 3, 65, 0);
        assertFalse("Looking at the hidden part.", CollisionUtil.canSeeBlockPoint(bc, 0.5, eyeY, 0.5, 3, 65, 0, wall.getX(), wall.getY(), wall.getZ()));
        assertNull("Looking away.", CollisionUtil.getLookPoint(0.5, eyeY, 0.5, new Vector(-1.0, 0.0, 0.0), 3, 65, 0));
        bc.cleanup();
    }

    /** Local axes of the random scenes (forward, slit, side) to world axes. */
    private static final int[][] AXES = {{0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}};
    private static final int BASE = 64;
    /** Distance to the slit edges below which a line counts as grazing and isn't tested. */
    private static final double MARGIN = 1.0E-6;

    /**
     * Random scenes in all directions: target block at local forward 3, a wall block at 2 with a slit (0.01% to 30% of
     * the block), full blocks next to the wall, the eye in front, mostly lined up with the slit. A clicked point, also
     * one outside the block (clamped into it), must count as visible exactly when the straight line runs through the
     * slit.
     */
    @Test
    public void testRandomSlits() {
        final Random random = new Random(1);
        int clear = 0, blocked = 0, tiny = 0, tinier = 0, sampleMisses = 0;
        for (int scene = 0; scene < 100000; scene++) {
            final int[] axes = AXES[random.nextInt(AXES.length)];
            final boolean[] flip = {random.nextBoolean(), random.nextBoolean(), random.nextBoolean()};
            // Log-uniform width, most slits are narrow.
            final double width = Math.exp(Math.log(1.0E-4) + random.nextDouble() * Math.log(0.3 / 1.0E-4));
            final double low = 0.01 + random.nextDouble() * (0.98 - width);
            final double high = low + width;
            final FakeBlockCache bc = new FakeBlockCache();
            final int[] target = cell(axes, flip, 3, 0);
            bc.set(target[0], target[1], target[2], Material.STONE);
            final int[] wall = cell(axes, flip, 2, 0);
            bc.set(wall[0], wall[1], wall[2], Material.STONE, 0, bounds(axes, flip, 0, 0, 0, 1, low, 1, 0, high, 0, 1, 1, 1));
            for (final int side : new int[] {-1, 1}) {
                final int[] c = cell(axes, flip, 2, side);
                bc.set(c[0], c[1], c[2], Material.STONE);
            }
            final double eyeF = 0.2 + random.nextDouble() * 1.6;
            final double k = (2.0 - eyeF) / (3.0 - eyeF);
            // 3 of 4 eyes line up with the slit, up to a bit past the offset where nothing is visible anymore.
            final double reach = width * (1.0 + k) / (2.0 * (1.0 - k));
            final double eyeS = Math.clamp(random.nextInt(4) > 0 ? (low + high) / 2.0 + (random.nextDouble() * 2.4 - 1.2) * reach
                    : -1.0 + random.nextDouble() * 3.0, -1.0, 2.0);
            final double[] eye = point(axes, flip, eyeF, eyeS, 0.02 + random.nextDouble() * 0.96);
            // Visible part of the near face: points s on it whose line also passes the slit at the wall's front.
            final double visibleLow = Math.max(low, (low - (1.0 - k) * eyeS) / k);
            final double visibleHigh = Math.min(high, (high - (1.0 - k) * eyeS) / k);
            final double fraction = Math.max(0.0, visibleHigh - visibleLow);

            // The sample points of canSeeBox must not see through the wall either.
            final boolean samples = CollisionUtil.canSeeBox(bc, eye[0], eye[1], eye[2], target[0], target[1], target[2],
                    target[0] + 1, target[1] + 1, target[2] + 1, target[0], target[1], target[2]);
            assertTrue("Scene " + scene + ": samples see a hidden block.", !samples || visibleHigh > visibleLow - MARGIN);
            if (!samples && fraction > 0.01) {
                sampleMisses++;
            }

            for (int i = 0; i < 12; i++) {
                // Clicked point, vanilla accepts up to 1 from the block center. Every second one aims at the visible part.
                final boolean aim = i % 2 == 0 && fraction > 0.0;
                final double rawF = aim ? 2.5 + random.nextDouble() * 0.5 : 2.5 + random.nextDouble() * 2.0;
                final double rawS = aim ? visibleLow + random.nextDouble() * fraction : -0.5 + random.nextDouble() * 2.0;
                final double pf = Math.clamp(rawF, 3.0, 4.0);
                final double ps = Math.clamp(rawS, 0.0, 1.0);
                // Where the line enters and leaves the wall block.
                final double s2 = eyeS + (ps - eyeS) * (2.0 - eyeF) / (pf - eyeF);
                final double s3 = eyeS + (ps - eyeS) * (3.0 - eyeF) / (pf - eyeF);
                final boolean expect;
                if (within(s2, low + MARGIN, high - MARGIN) && within(s3, low + MARGIN, high - MARGIN)) {
                    expect = true;
                }
                else if (!within(s2, low - MARGIN, high + MARGIN) || !within(s3, low - MARGIN, high + MARGIN)) {
                    expect = false;
                }
                else {
                    continue; // Grazes a slit edge.
                }
                final double[] p = point(axes, flip, rawF, rawS, 0.02 + random.nextDouble() * 0.96);
                final boolean seen = CollisionUtil.canSeeBlockPoint(bc, eye[0], eye[1], eye[2], target[0], target[1], target[2],
                        p[0] - target[0], p[1] - target[1], p[2] - target[2]);
                assertEquals(String.format("Scene %d: axes %s flip %s slit %.5f-%.5f eye %.4f,%.4f point %.4f,%.4f visible %.5f",
                        scene, Arrays.toString(axes), Arrays.toString(flip), low, high, eyeF, eyeS, rawF, rawS, fraction), expect, seen);
                if (expect) {
                    clear++;
                    if (fraction < 0.01) {
                        tiny++;
                    }
                    if (fraction < 0.001) {
                        tinier++;
                    }
                }
                else {
                    blocked++;
                }
            }
            bc.cleanup();
        }
        System.out.println("TestVisibility random slits: clear=" + clear + " (under 1% of the face visible: " + tiny
                + ", under 0.1%: " + tinier + ") blocked=" + blocked + " scenes with >1% visible that the sample points miss=" + sampleMisses);
        assertTrue("Not enough visible slivers under 1% tested.", tiny > 20000);
        assertTrue("Not enough visible slivers under 0.1% tested.", tinier > 5000);
    }

    private static boolean within(final double v, final double min, final double max) {
        return v >= min && v <= max;
    }

    /** World block of a local block (side axis 0). */
    private static int[] cell(final int[] axes, final boolean[] flip, final int f, final int s) {
        final int[] local = {f, s, 0};
        final int[] world = new int[3];
        for (int i = 0; i < 3; i++) {
            world[axes[i]] = flip[i] ? BASE - local[i] - 1 : BASE + local[i];
        }
        return world;
    }

    private static double[] point(final int[] axes, final boolean[] flip, final double f, final double s, final double t) {
        final double[] local = {f, s, t};
        final double[] world = new double[3];
        for (int i = 0; i < 3; i++) {
            world[axes[i]] = flip[i] ? BASE - local[i] : BASE + local[i];
        }
        return world;
    }

    /** Local boxes (relative to the block, 6 values each) to world bounds. */
    private static double[] bounds(final int[] axes, final boolean[] flip, final double... local) {
        final double[] world = new double[local.length];
        for (int b = 0; b < local.length; b += 6) {
            for (int i = 0; i < 3; i++) {
                world[b + axes[i]] = flip[i] ? 1.0 - local[b + i + 3] : local[b + i];
                world[b + axes[i] + 3] = flip[i] ? 1.0 - local[b + i] : local[b + i + 3];
            }
        }
        return world;
    }
}
