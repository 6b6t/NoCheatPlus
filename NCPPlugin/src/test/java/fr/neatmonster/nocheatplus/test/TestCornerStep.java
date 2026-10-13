package fr.neatmonster.nocheatplus.test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.bukkit.Material;
import org.junit.Test;

import fr.neatmonster.nocheatplus.checks.moving.magic.CornerStep;
import fr.neatmonster.nocheatplus.logging.StaticLog;
import fr.neatmonster.nocheatplus.utilities.map.FakeBlockCache;

/**
 * Vanilla step past a block corner (Entity#collide, 1.21+). Moves from the Fox lab (8 Oct 2026), vanilla 1.21.11
 * client sprint-jumping east while strafing left along a 3-wide staircase / half-slab staircase, past its north side:
 * the client steps up 0.5 onto the next block and the same tick carries the box past the corner. Live NCP keeps the set
 * back at the lower ground; the next jump reads 1.50 (stairs) / 1.37 (slabs) above it: vdistsb set back.
 */
public class TestCornerStep {

    private static final double HALF = 0.3;
    private static final double HEIGHT = 1.8;
    private static final double STEP = 0.6;
    private static final double[] SLAB = {0.0, 0.0, 0.0, 1.0, 0.5, 1.0};
    /** Bottom stairs facing east: bottom half plus the east half up to the top. */
    private static final double[] STAIRS_EAST = {0.0, 0.0, 0.0, 1.0, 0.5, 1.0, 0.5, 0.5, 0.0, 1.0, 1.0, 1.0};
    private static final double[] FENCE = {0.375, 0.0, 0.375, 0.625, 1.5, 0.625};

    public TestCornerStep() {
        StaticLog.setUseLogManager(false);
        BlockTests.initBlockProperties();
        StaticLog.setUseLogManager(true);
    }

    /** Floor top at y 151; the lane strip covers z 339..341. */
    private static FakeBlockCache floor() {
        final FakeBlockCache bc = new FakeBlockCache();
        bc.fill(295, 150, 335, 340, 150, 345, Material.STONE);
        return bc;
    }

    /** CollLab "stairslong": stairs at x 303 (y 151), stone + stairs at x 304 (y 152), ... */
    private static FakeBlockCache stairs() {
        final FakeBlockCache bc = floor();
        for (int i = 0; i < 4; i++) {
            for (int z = 339; z <= 341; z++) {
                for (int y = 151; y < 151 + i; y++) {
                    bc.set(303 + i, y, z, Material.STONE);
                }
                bc.set(303 + i, 151 + i, z, Material.OAK_STAIRS, STAIRS_EAST);
            }
        }
        return bc;
    }

    /** CollLab "slabslong": half-block staircase, slab at x 303, stone at x 304, stone + slab at x 305, ... */
    private static FakeBlockCache slabs() {
        final FakeBlockCache bc = floor();
        for (int i = 0; i < 6; i++) {
            for (int z = 339; z <= 341; z++) {
                int hh = i + 1;
                int y = 151;
                for (; hh >= 2; hh -= 2) {
                    bc.set(303 + i, y++, z, Material.STONE);
                }
                if (hh == 1) {
                    bc.set(303 + i, y, z, Material.STONE_SLAB, SLAB);
                }
            }
        }
        return bc;
    }

    private static boolean step(final FakeBlockCache bc, final double fx, final double fy, final double fz,
                                final double tx, final double ty, final double tz) {
        return CornerStep.crossedStepTop(bc, fx - HALF, fz - HALF, fx + HALF, fz + HALF, fy,
                tx - HALF, tz - HALF, tx + HALF, tz + HALF, ty, HEIGHT, STEP);
    }

    @Test
    public void testLabStairsStepFromGround() {
        final FakeBlockCache bc = stairs();
        // On the stairs top (152.0), step 0.5 onto the next stairs' lower half and past its north corner.
        assertTrue("Lab stairs corner step", step(bc, 303.6378349223339, 152.0, 338.81747653456245,
                303.8356299250084, 152.5, 338.6304430185404));
        bc.cleanup();
    }

    @Test
    public void testLabSlabsStepWhileLanding() {
        final FakeBlockCache bc = slabs();
        // Falling onto the slab (151.5) and stepping 0.5 onto the stone (152.0) past its north corner, in one tick.
        assertTrue("Lab slab corner step", step(bc, 303.6378349223339, 151.79673560066868, 338.81747653456245,
                303.77906138688024, 152.0, 338.6870115566686));
        bc.cleanup();
    }

    @Test
    public void testStepHacks() {
        final FakeBlockCache bc = floor();
        bc.set(303, 151, 339, Material.STONE);                    // 1.0 high
        bc.set(306, 151, 339, Material.STONE);                    // 2.0 high
        bc.set(306, 152, 339, Material.STONE);
        bc.set(309, 151, 339, Material.STONE);                    // 1.5 high
        bc.set(309, 152, 339, Material.STONE_SLAB, SLAB);
        bc.set(312, 151, 339, Material.STONE_SLAB, SLAB);         // 0.5 high
        // Step hacks past the corner of each, ending at its top over air (moves like the lab: x first, then off the side).
        assertFalse("Step 1.0", step(bc, 302.65, 151.0, 338.8, 302.9, 152.0, 338.65));
        assertFalse("Step 2.0", step(bc, 305.65, 151.0, 338.8, 305.9, 153.0, 338.65));
        assertFalse("Step 1.5", step(bc, 308.65, 151.0, 338.8, 308.9, 152.5, 338.65));
        // Vanilla: 0.5 onto the slab, past its corner.
        assertTrue("Vanilla 0.5 corner step onto a slab", step(bc, 311.65, 151.0, 338.8, 311.9, 151.5, 338.65));
        // Step 0.6 / 0.55 past the 0.5 slab: no block top at the end height.
        assertFalse("Step 0.6 past a 0.5 slab", step(bc, 311.65, 151.0, 338.8, 311.9, 151.6, 338.65));
        assertFalse("Step 0.55 past a 0.5 slab", step(bc, 311.65, 151.0, 338.8, 311.9, 151.55, 338.65));
        // Speed + step: the same corner crossed with a much longer move still needs the block in the way.
        assertTrue("Fast corner step onto the slab", step(bc, 311.5, 151.0, 338.8, 312.3, 151.5, 338.6));
        assertFalse("Fast step 1.0", step(bc, 302.5, 151.0, 338.8, 303.3, 152.0, 338.6));
        // Live config has stepheight 1.1: a corner step still never rises more than the vanilla 0.6.
        assertFalse("Step 1.0 with sfStepHeight 1.1", CornerStep.crossedStepTop(bc, 302.35, 338.5, 302.95, 339.1, 151.0,
                302.6, 338.35, 303.2, 338.95, 152.0, HEIGHT, 1.1));
        assertFalse("Step 0.75 onto a stairs top with sfStepHeight 1.1", CornerStep.crossedStepTop(bc, 308.35, 338.5, 308.95, 339.1,
                151.75, 308.6, 338.35, 309.2, 338.95, 152.5, HEIGHT, 1.1));
        assertTrue("Vanilla 0.5 with sfStepHeight 1.1", CornerStep.crossedStepTop(bc, 311.35, 338.5, 311.95, 339.1, 151.0,
                311.6, 338.35, 312.2, 338.95, 151.5, HEIGHT, 1.1));
        bc.cleanup();
    }

    @Test
    public void testNoBlockCrossed() {
        final FakeBlockCache bc = floor();
        bc.set(312, 151, 339, Material.STONE_SLAB, SLAB);
        // Rising to slab height beside the slab without the box crossing it (fake ground / hover at slab height).
        assertFalse("Beside the slab, not crossing", step(bc, 311.0, 151.0, 338.6, 311.3, 151.5, 338.6));
        // The boxes end exactly at the slab's edges (touching, not overlapping).
        assertFalse("Touching the slab edge only", step(bc, 311.5, 151.0, 338.7, 311.7, 151.5, 338.7));
        // Diagonal past the corner without running into it (vanilla moves the larger axis, z, first: no collision).
        assertFalse("Corner cut, never blocked", step(bc, 311.6, 151.0, 339.1, 311.9, 151.5, 338.65));
        // Over open floor.
        assertFalse("No block at all", step(bc, 320.0, 151.0, 340.0, 320.3, 151.5, 340.0));
        // No rise, or a fall.
        assertFalse("No rise", step(bc, 311.65, 151.5, 338.8, 311.9, 151.5, 338.65));
        assertFalse("Falling", step(bc, 311.65, 151.6, 338.8, 311.9, 151.5, 338.65));
        bc.cleanup();
    }

    @Test
    public void testCoveredTop() {
        final FakeBlockCache bc = floor();
        // Two-high pillar: the top of the lower block (152.0) is covered by the upper block.
        bc.set(310, 151, 339, Material.STONE);
        bc.set(310, 152, 339, Material.STONE);
        assertFalse("Covered top (spider at a pillar corner)", step(bc, 309.65, 151.5, 338.8, 309.9, 152.0, 338.65));
        bc.cleanup();
        // Same move at a one-high block is a vanilla step.
        final FakeBlockCache open = floor();
        open.set(310, 151, 339, Material.STONE);
        assertTrue("Open top", step(open, 309.65, 151.5, 338.8, 309.9, 152.0, 338.65));
        open.cleanup();
        // Ceiling beside the step: the stepped path at the top's height runs into it.
        bc.set(315, 151, 339, Material.STONE_SLAB, SLAB);
        bc.set(315, 152, 338, Material.STONE);
        assertFalse("Blocked above the top", step(bc, 314.65, 151.0, 338.8, 314.9, 151.5, 338.65));
        bc.cleanup();
    }

    @Test
    public void testClimbNeedsHigherTops() {
        final FakeBlockCache bc = slabs();
        // After the lab slab step (152.0, beside the lane), another rise needs a block top under the swept box.
        assertFalse("Repeat at the same corner", step(bc, 303.779, 152.0, 338.687, 303.95, 152.5, 338.55));
        assertFalse("Repeat higher in the air", step(bc, 303.779, 152.0, 338.687, 303.95, 152.6, 338.55));
        assertFalse("Repeat 0.6 in place", step(bc, 303.779, 152.0, 338.687, 303.8, 152.6, 338.687));
        // The next real half step (stone + slab at x 305, top 152.5) is a vanilla step again.
        assertTrue("Next real half step", step(bc, 304.65, 152.0, 338.8, 304.9, 152.5, 338.65));
        bc.cleanup();
    }

    @Test
    public void testNonGround() {
        final FakeBlockCache bc = floor();
        bc.set(312, 151, 339, Material.WATER, SLAB);
        bc.set(316, 151, 339, Material.POPPY, SLAB);
        assertFalse("Water is no step", step(bc, 311.65, 151.0, 338.8, 311.9, 151.5, 338.65));
        assertFalse("A flower is no step", step(bc, 315.65, 151.0, 338.8, 315.9, 151.5, 338.65));
        // Fence post (1.5 high) next to a one-high block: a 0.5 step onto the post top, past its corner.
        bc.set(320, 151, 339, Material.OAK_FENCE, FENCE);
        bc.set(319, 151, 339, Material.STONE);
        bc.set(319, 151, 338, Material.STONE);
        assertTrue("Fence post top from a block", step(bc, 319.6, 152.0, 339.2, 320.1, 152.5, 339.05));
        bc.cleanup();
    }

    @Test
    public void testBadInput() {
        final FakeBlockCache bc = stairs();
        assertFalse("No cache", CornerStep.crossedStepTop(null, 303.3378, 338.5175, 303.9378, 339.1175, 152.0,
                303.5356, 338.3304, 304.1356, 338.9304, 152.5, HEIGHT, STEP));
        assertFalse("NaN", step(bc, 303.6378, Double.NaN, 338.8175, 303.8356, 152.5, 338.6304));
        assertFalse("Infinite", step(bc, 303.6378, 152.0, 338.8175, 303.8356, Double.POSITIVE_INFINITY, 338.6304));
        assertFalse("No box height", CornerStep.crossedStepTop(bc, 303.3378, 338.5175, 303.9378, 339.1175, 152.0,
                303.5356, 338.3304, 304.1356, 338.9304, 152.5, 0.0, STEP));
        assertFalse("Step height 0.4", CornerStep.crossedStepTop(bc, 303.3378, 338.5175, 303.9378, 339.1175, 152.0,
                303.5356, 338.3304, 304.1356, 338.9304, 152.5, HEIGHT, 0.4));
        bc.cleanup();
    }

    @Test
    public void testLiftOff() {
        assertTrue("Next move from the step end", CornerStep.isLiftOff(true, 41, 42, 303.8, 152.5, 338.6, 303.8, 152.5, 338.6));
        assertFalse("Not armed", CornerStep.isLiftOff(false, 41, 42, 303.8, 152.5, 338.6, 303.8, 152.5, 338.6));
        assertFalse("Same move", CornerStep.isLiftOff(true, 41, 41, 303.8, 152.5, 338.6, 303.8, 152.5, 338.6));
        assertFalse("Two moves later", CornerStep.isLiftOff(true, 41, 43, 303.8, 152.5, 338.6, 303.8, 152.5, 338.6));
        assertFalse("Other start y", CornerStep.isLiftOff(true, 41, 42, 303.8, 152.5, 338.6, 303.8, 152.6, 338.6));
        assertFalse("Other start x", CornerStep.isLiftOff(true, 41, 42, 303.8, 152.5, 338.6, 303.9, 152.5, 338.6));
        assertFalse("Other start z", CornerStep.isLiftOff(true, 41, 42, 303.8, 152.5, 338.6, 303.8, 152.5, 338.7));
        assertFalse("Move counter wrap", CornerStep.isLiftOff(true, Integer.MAX_VALUE, Integer.MIN_VALUE, 1, 2, 3, 1, 2, 3));
    }
}
