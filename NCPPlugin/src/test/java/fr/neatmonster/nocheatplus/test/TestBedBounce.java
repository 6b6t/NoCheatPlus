package fr.neatmonster.nocheatplus.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.bukkit.Material;
import org.junit.Test;

import fr.neatmonster.nocheatplus.checks.moving.magic.BedBounce;
import fr.neatmonster.nocheatplus.logging.StaticLog;
import fr.neatmonster.nocheatplus.utilities.map.FakeBlockCache;
import fr.neatmonster.nocheatplus.utilities.map.MaterialUtil;

/**
 * Vanilla bed bounce after a short fall (BedBlock#bounceUp). Values from the Fox lab (8 Oct 2026), vanilla 1.21.11
 * client walking off a bed into a one-block gap and stepping up onto the next bed while falling: feet 0.5625, 0.515,
 * 0.39, 0.1892, landing 0.5625, bounce 0.6621, 0.6814, then 0.6218 (live NCP set back here: ychdec + lowjump).
 */
public class TestBedBounce {

    private static final Material BED = Material.RED_BED;

    public TestBedBounce() {
        StaticLog.setUseLogManager(false);
        BlockTests.initBlockProperties();
        StaticLog.setUseLogManager(true);
    }

    /** Vanilla: the landing tick moves with v = (lastDy - 0.08) * 0.98, the bed sets -v * 0.66, then gravity and drag. */
    private static double vanillaRise(final double lastDy) {
        final double impact = (lastDy - 0.08) * (double) 0.98F;
        double v = -impact * (double) 0.66F;
        v = (v - 0.08) * (double) 0.98F;
        return Math.max(0.0, v);
    }

    @Test
    public void testRiseMatchesLab() {
        // Last in-air move before the step-up landing: 0.39 -> 0.1892.
        final double rise = BedBounce.firstRise(0.1892 - 0.39, false);
        assertEquals("Lab bounce rise 0.6621 - 0.5625", 0.6621 - 0.5625, rise, 0.0005);
        // The following moves of the lab trace follow ordinary gravity and drag.
        final double second = (rise - 0.08) * (double) 0.98F;
        assertEquals(0.6814 - 0.6621, second, 0.0005);
        assertEquals(0.6218 - 0.6814, (second - 0.08) * (double) 0.98F, 0.0005);
    }

    @Test
    public void testRiseValues() {
        for (final double lastDy : new double[] {-0.0784, -0.1552, -0.2305, -0.3, -0.5, -1.0, -3.0}) {
            assertEquals("lastDy " + lastDy, vanillaRise(lastDy), BedBounce.firstRise(lastDy, false), 1e-12);
            assertTrue("Visible bounce for lastDy " + lastDy, BedBounce.firstRise(lastDy, false) > 0.0);
        }
        // One tick of falling off an edge (-0.0784): a 0.022 hop, as in the field low-jump pattern 0.58 -> 0.53.
        assertEquals(0.022, BedBounce.firstRise(-0.0784, false), 0.001);
        // Slow landings do not bounce visibly (impact above -0.1212).
        for (final double lastDy : new double[] {-0.04, -0.01, 0.0, 0.1, 0.42, Double.NaN,
                Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}) {
            assertEquals("No rise for lastDy " + lastDy, 0.0, BedBounce.firstRise(lastDy, false), 0.0);
        }
        // Never more than a jump for falls of up to a few blocks.
        assertTrue(BedBounce.firstRise(-0.6, false) < 0.42);
    }

    @Test
    public void testRecordedBedrockRisesAndOrdinaryJumpLimit() {
        for (final double[] trace : new double[][] {{-0.12087, 0.13017}, {-0.22791, 0.19889}}) {
            final double rise = BedBounce.riseAfterLanding(true, false, trace[0], false, true, false, true, true);
            assertEquals(trace[1], rise, BedBounce.RISE_MARGIN);
            assertTrue(BedBounce.isBounceLiftOff(rise, 1, 2, true, trace[1], true));
            assertFalse(BedBounce.isBounceLiftOff(rise, 1, 2, true, 0.42, true));
        }
    }

    @Test
    public void testLabSequence() {
        // Landing move: from in air (0.1892) to the bed top, last move 0.39 -> 0.1892 in air.
        final double armed = BedBounce.riseAfterLanding(true, false, 0.1892 - 0.39, false, true, false, true, false);
        assertTrue(armed > 0.0);
        // Next move: 0.5625 -> 0.6621 from the bed top.
        assertTrue(BedBounce.isBounceLiftOff(armed, 100, 101, true, 0.6621 - 0.5625, true));
    }

    @Test
    public void testNotArmed() {
        final double dy = -0.2;
        for (final boolean bedrock : new boolean[] {false, true}) {
            assertEquals("Last move invalid", 0.0, BedBounce.riseAfterLanding(false, false, dy, false, true, false, true, bedrock), 0.0);
            assertEquals("Last move ended on ground (truncated)", 0.0, BedBounce.riseAfterLanding(true, true, dy, false, true, false, true, bedrock), 0.0);
            assertEquals("Not a landing (from ground)", 0.0, BedBounce.riseAfterLanding(true, false, dy, true, true, false, true, bedrock), 0.0);
            assertEquals("Not on ground", 0.0, BedBounce.riseAfterLanding(true, false, dy, false, false, false, true, bedrock), 0.0);
            assertEquals("Sneaking suppresses the bounce", 0.0, BedBounce.riseAfterLanding(true, false, dy, false, true, true, true, bedrock), 0.0);
            assertEquals("Not on a bed", 0.0, BedBounce.riseAfterLanding(true, false, dy, false, true, false, false, bedrock), 0.0);
            assertEquals("Rising, no fall", 0.0, BedBounce.riseAfterLanding(true, false, 0.2, false, true, false, true, bedrock), 0.0);
        }
    }

    @Test
    public void testLiftOffLimits() {
        final double rise = BedBounce.firstRise(-0.2, false);
        assertTrue(BedBounce.isBounceLiftOff(rise, 7, 8, true, rise, true));
        assertTrue("Lower than vanilla", BedBounce.isBounceLiftOff(rise, 7, 8, true, rise / 2.0, true));
        assertFalse("Higher than vanilla", BedBounce.isBounceLiftOff(rise, 7, 8, true, rise + 0.01, true));
        assertFalse("A jump", BedBounce.isBounceLiftOff(rise, 7, 8, true, 0.42, true));
        assertFalse("Not the next move", BedBounce.isBounceLiftOff(rise, 7, 9, true, rise, true));
        assertFalse("Same move", BedBounce.isBounceLiftOff(rise, 7, 7, true, rise, true));
        assertFalse("Not from ground", BedBounce.isBounceLiftOff(rise, 7, 8, false, rise, true));
        assertFalse("Not from a bed top", BedBounce.isBounceLiftOff(rise, 7, 8, true, rise, false));
        assertFalse("Not rising", BedBounce.isBounceLiftOff(rise, 7, 8, true, 0.0, true));
        assertFalse("Falling", BedBounce.isBounceLiftOff(rise, 7, 8, true, -0.05, true));
        assertFalse("Nothing armed", BedBounce.isBounceLiftOff(0.0, 7, 8, true, 0.01, true));
    }

    @Test
    public void testOnBedTop() {
        assertTrue("Test material is a bed", MaterialUtil.BEDS.contains(BED));
        final FakeBlockCache bc = new FakeBlockCache();
        bc.set(0, 63, 0, Material.STONE);
        bc.set(1, 63, 0, Material.STONE);
        bc.set(0, 64, 0, BED);
        bc.set(1, 64, 0, Material.STONE);
        bc.set(2, 64, 0, Material.SLIME_BLOCK);
        assertTrue("Centered on the bed", BedBounce.isOnBedTop(bc, 0.2, 0.2, 0.8, 0.8, 64.5625));
        assertTrue("Edge over the bed, center over air", BedBounce.isOnBedTop(bc, -0.5, 0.2, 0.1, 0.8, 64.5625));
        assertFalse("Box ends exactly at the bed's edge", BedBounce.isOnBedTop(bc, -0.6, 0.2, 0.0, 0.8, 64.5625));
        assertFalse("Over air only", BedBounce.isOnBedTop(bc, -0.9, 0.2, -0.3, 0.8, 64.5625));
        assertFalse("Not at the bed top (slab height)", BedBounce.isOnBedTop(bc, 0.2, 0.2, 0.8, 0.8, 64.5));
        assertFalse("On the block below", BedBounce.isOnBedTop(bc, 0.2, 0.2, 0.8, 0.8, 64.0));
        assertFalse("Above the bed", BedBounce.isOnBedTop(bc, 0.2, 0.2, 0.8, 0.8, 65.0));
        assertFalse("Above the bed by the bed height", BedBounce.isOnBedTop(bc, 0.2, 0.2, 0.8, 0.8, 65.5625));
        assertFalse("Stone at bed height is no bed", BedBounce.isOnBedTop(bc, 1.2, 0.2, 1.8, 0.8, 64.5625));
        assertFalse("Slime block is no bed", BedBounce.isOnBedTop(bc, 2.2, 0.2, 2.8, 0.8, 65.0));
        assertFalse("No cache", BedBounce.isOnBedTop(null, 0.2, 0.2, 0.8, 0.8, 64.5625));
        assertFalse("NaN", BedBounce.isOnBedTop(bc, 0.2, 0.2, 0.8, 0.8, Double.NaN));
        bc.cleanup();
    }
}
