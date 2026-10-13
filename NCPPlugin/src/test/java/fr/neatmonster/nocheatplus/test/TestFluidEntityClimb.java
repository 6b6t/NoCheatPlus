package fr.neatmonster.nocheatplus.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.bukkit.Material;
import org.junit.Test;

import fr.neatmonster.nocheatplus.checks.moving.magic.FluidEntityClimb;
import fr.neatmonster.nocheatplus.checks.moving.model.LiftOffEnvelope;
import fr.neatmonster.nocheatplus.logging.StaticLog;
import fr.neatmonster.nocheatplus.utilities.map.FakeBlockCache;

/**
 * Vanilla lift-offs from water beside/on a boat (FluidEntityClimb). Values from the test-server lobby debug log (8 Oct
 * 2026, boat-race boats floating on still water, surface y+0.889, boat top y+1.086): a player in the water pushing
 * against a boat rose 0.34000001 (jumpOutOfFluid 0.3F + jumpInLiquid 0.04F), then 0.30 / 0.216 / 0.133 onto the boat;
 * live NCP set it back (hliquid/hliquidexit + swimup).
 */
public class TestFluidEntityClimb {

    private static final double SURFACE = 61.0 + 8.0 / 9.0;
    /** Floating boat: AbstractBoat#floatBoat settles at buoyancy 0.65, i.e. y = surface - 0.65 * 0.5625. */
    private static final double BOAT_TOP = SURFACE - 0.65 * 0.5625 + 0.5625;

    public TestFluidEntityClimb() {
        StaticLog.setUseLogManager(false);
        BlockTests.initBlockProperties();
        StaticLog.setUseLogManager(true);
    }

    /** Geometry stand-in for the server query: a boat side touches the box, top above the feet, none taller than maxTop. */
    private static boolean besideBoat(final double feetY, final double boatTop, final double maxTop) {
        return boatTop > feetY + FluidEntityClimb.MIN_SIDE_HEIGHT && boatTop <= maxTop;
    }

    private static boolean climbAllowed(final double feetY, final double lastDy, final double dy, final double boatTop) {
        final double maxTop = FluidEntityClimb.maxObstacleTop(feetY, lastDy);
        return !Double.isNaN(maxTop) && FluidEntityClimb.allowsClimbOut(dy, true, false, besideBoat(feetY, boatTop, maxTop));
    }

    @Test
    public void testVanillaRiseValues() {
        final double impulse = (double) 0.3F;
        final double kick = (double) 0.04F;
        assertEquals(0.3400000110268593, impulse + kick, 1e-15);
        assertTrue(FluidEntityClimb.isClimbRise(impulse + kick));
        assertTrue(FluidEntityClimb.isClimbRise(impulse));
        // Lobby log: 61.49963581677866 -> 61.83963582780552.
        assertTrue(FluidEntityClimb.isClimbRise(61.83963582780552 - 61.49963581677866));
        assertTrue(FluidEntityClimb.isClimbRise(0.04));
        assertFalse(FluidEntityClimb.isClimbRise(impulse + kick + 0.0002));
        for (final double dy : new double[] {0.35, 0.42, 0.5, 1.0, 0.0, -0.1, Double.NaN,
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertFalse("rise " + dy, FluidEntityClimb.isClimbRise(dy));
        }
    }

    @Test
    public void testLobbyTrace() {
        assertEquals(62.086, BOAT_TOP, 0.001);
        // Fell into the water beside the boat (-0.23), then the climb rise.
        assertTrue(climbAllowed(61.49963581677866, -0.22965883833691, 0.34, BOAT_TOP));
        // Set-back loop of the live jar: +0.04 from the set-back position, then 0.34 again.
        assertTrue(climbAllowed(61.5396, 0.04, 0.34, BOAT_TOP));
        assertTrue(climbAllowed(61.8596, 0.04, 0.34, BOAT_TOP));
        // The 0.30 move (no swim kick) after a 0.34 one, feet still below the boat top.
        assertTrue(climbAllowed(61.8396, 0.34, 0.30, BOAT_TOP));
        // Feet at or above the boat top: the boat is ground, not a side (no climb impulse).
        assertFalse(climbAllowed(BOAT_TOP, 0.0, 0.34, BOAT_TOP));
        assertFalse(climbAllowed(BOAT_TOP + 0.1, 0.30, 0.30, BOAT_TOP));
        // Deep under the boat side: vanilla isFree 0.6 higher fails, no impulse.
        assertFalse(climbAllowed(BOAT_TOP - 0.9, 0.04, 0.34, BOAT_TOP));
        assertFalse(climbAllowed(BOAT_TOP - 0.7, 0.10, 0.30, BOAT_TOP));
    }

    @Test
    public void testMaxObstacleTop() {
        assertEquals(61.5 + FluidEntityClimb.FREE_ABOVE + 0.05, FluidEntityClimb.maxObstacleTop(61.5, 0.04), 1e-12);
        assertEquals(61.5 + FluidEntityClimb.FREE_ABOVE + 0.115 + 0.05, FluidEntityClimb.maxObstacleTop(61.5, -0.23), 1e-12);
        assertTrue(Double.isNaN(FluidEntityClimb.maxObstacleTop(61.5, Double.NaN)));
        assertTrue(Double.isNaN(FluidEntityClimb.maxObstacleTop(Double.NaN, 0.0)));
        assertTrue(Double.isNaN(FluidEntityClimb.maxObstacleTop(61.5, -5.0)));
        assertTrue(Double.isNaN(FluidEntityClimb.maxObstacleTop(61.5, Double.NEGATIVE_INFINITY)));
    }

    @Test
    public void testDecisionNeedsEveryCondition() {
        assertTrue(FluidEntityClimb.allowsClimbOut(0.34, true, false, true));
        assertFalse("not in liquid", FluidEntityClimb.allowsClimbOut(0.34, false, false, true));
        assertFalse("head obstructed", FluidEntityClimb.allowsClimbOut(0.34, true, true, true));
        assertFalse("no entity beside", FluidEntityClimb.allowsClimbOut(0.34, true, false, false));
        assertFalse("too high", FluidEntityClimb.allowsClimbOut(0.42, true, false, true));
    }

    /** Jesus / water walking: no entity beside or under the player, nothing is exempted. */
    @Test
    public void testJesusGetsNothing() {
        for (final double dy : new double[] {0.04, 0.1, 0.2, 0.3, 0.34, 0.42, 0.5}) {
            assertFalse(FluidEntityClimb.allowsClimbOut(dy, true, false, false));
            assertFalse(FluidEntityClimb.allowsShallowJump(dy, false, 0.1, false, 0.42));
            assertFalse(FluidEntityClimb.allowsShallowJump(dy, false, 0.0, false, 0.42));
        }
    }

    /** Repeated jumps in water without any ground: neither rule applies however often it is tried. */
    @Test
    public void testRepeatedJumpsWithoutGround() {
        double feet = SURFACE - 0.6;
        for (int i = 0; i < 20; i++) {
            assertFalse(FluidEntityClimb.allowsShallowJump(0.42, false, SURFACE - feet, false, 0.42));
            assertFalse(FluidEntityClimb.allowsClimbOut(0.34, true, false, false));
            feet += 0.04;
        }
    }

    /**
     * Fake ground packets: the rules only take server-side support (entity contact / an entity beside the box) and the
     * fluid height from the world. A client ground flag is no input, so claiming ground in deep water gives nothing.
     */
    @Test
    public void testFakeGroundGetsNothing() {
        // No entity under the feet: the client's claim does not matter.
        assertFalse(FluidEntityClimb.allowsShallowJump(0.42, false, 0.3, false, 0.42));
        // Even on a real entity, deep water (fluid above the vanilla threshold) means swimming, not jumping.
        assertFalse(FluidEntityClimb.allowsShallowJump(0.42, true, 0.41, false, 0.42));
        assertFalse(FluidEntityClimb.allowsShallowJump(0.42, true, 0.889, false, 0.42));
        assertFalse(FluidEntityClimb.allowsShallowJump(0.42, true, Double.NaN, false, 0.42));
        // Not in a fluid at all: this rule is for the liquid model only.
        assertFalse(FluidEntityClimb.allowsShallowJump(0.42, true, 0.0, false, 0.42));
    }

    @Test
    public void testShallowJumpExactlyVanilla() {
        final double gain = LiftOffEnvelope.NORMAL.getMinJumpGain(0.0);
        assertEquals(0.42, gain, 1e-12);
        assertTrue(FluidEntityClimb.allowsShallowJump(0.42, true, 0.25, false, gain));
        assertTrue(FluidEntityClimb.allowsShallowJump((double) 0.42F, true, 0.4, false, gain));
        assertFalse(FluidEntityClimb.allowsShallowJump(0.4202, true, 0.25, false, gain));
        assertFalse(FluidEntityClimb.allowsShallowJump(0.42, true, 0.25, true, gain));
        // Jump Boost I: 0.52.
        final double boost = LiftOffEnvelope.NORMAL.getMinJumpGain(1.0);
        assertEquals(0.52, boost, 1e-12);
        assertTrue(FluidEntityClimb.allowsShallowJump(0.52, true, 0.25, false, boost));
        assertFalse(FluidEntityClimb.allowsShallowJump(0.5202, true, 0.25, false, boost));
    }

    /**
     * Boat-fly / boat ladder: a rising boat or a boat beside the player gives at most the vanilla 0.34 per move, and
     * only while the feet are below its top. Climbing beside a floating boat ends at most 0.34 above its top.
     */
    @Test
    public void testBoatClimbIsBounded() {
        for (final double dy : new double[] {0.35, 0.5, 0.8, 1.5, 3.0}) {
            assertFalse("boat rising beside the player " + dy, climbAllowed(61.5, 0.04, dy, BOAT_TOP));
        }
        // Lobby start: feet 0.35 below the surface (0.55 below the boat top).
        double feet = SURFACE - 0.35;
        double lastDy = 0.04;
        int allowed = 0;
        for (int i = 0; i < 20; i++) {
            if (!climbAllowed(feet, lastDy, 0.34, BOAT_TOP)) {
                break;
            }
            allowed++;
            feet += 0.34;
            lastDy = 0.34;
        }
        assertTrue(allowed >= 1);
        assertTrue("climb ends near the boat top", feet <= BOAT_TOP + FluidEntityClimb.MAX_CLIMB_RISE);
        assertTrue(allowed <= 3);
    }

    @Test
    public void testFluidHeight() {
        final FakeBlockCache bc = new FakeBlockCache();
        // Still water at y 61 (surface 61.889), feet on a boat at 61.5 (a no-gravity boat set below the surface).
        bc.set(0, 61, 0, Material.WATER, 0);
        double h = FluidEntityClimb.fluidHeight(bc, 0.2, 61.5, 0.2, 0.8, 63.3, 0.8);
        assertEquals(SURFACE - 61.501, h, 1e-9);
        assertTrue(h <= FluidEntityClimb.FLUID_JUMP_THRESHOLD);
        // Feet lower: more than the threshold, the player swims.
        assertTrue(FluidEntityClimb.fluidHeight(bc, 0.2, 61.3, 0.2, 0.8, 63.1, 0.8) > FluidEntityClimb.FLUID_JUMP_THRESHOLD);
        // Feet above the surface: no fluid.
        assertEquals(0.0, FluidEntityClimb.fluidHeight(bc, 0.2, 62.0, 0.2, 0.8, 63.8, 0.8), 0.0);
        // Water above: full block, the surface is the block above's.
        bc.set(0, 62, 0, Material.WATER, 0);
        assertTrue(FluidEntityClimb.fluidHeight(bc, 0.2, 61.5, 0.2, 0.8, 63.3, 0.8) > 1.0);
        // Flowing level 4 (height 4/9).
        final FakeBlockCache flow = new FakeBlockCache();
        flow.set(0, 61, 0, Material.WATER, 4);
        assertEquals(4.0 / 9.0 - 0.001, FluidEntityClimb.fluidHeight(flow, 0.2, 61.0, 0.2, 0.8, 62.8, 0.8), 1e-9);
        // Falling water (8+) is 8/9 like a source.
        final FakeBlockCache fall = new FakeBlockCache();
        fall.set(0, 61, 0, Material.WATER, 8);
        assertEquals(8.0 / 9.0 - 0.001, FluidEntityClimb.fluidHeight(fall, 0.2, 61.0, 0.2, 0.8, 62.8, 0.8), 1e-9);
        // Lava like water.
        final FakeBlockCache lava = new FakeBlockCache();
        lava.set(0, 61, 0, Material.LAVA, 0);
        assertEquals(SURFACE - 61.501, FluidEntityClimb.fluidHeight(lava, 0.2, 61.5, 0.2, 0.8, 63.3, 0.8), 1e-9);
        // Seagrass (always water, height not modelled): unknown, no exemption.
        final FakeBlockCache grass = new FakeBlockCache();
        grass.set(0, 61, 0, Material.SEAGRASS);
        assertTrue(Double.isNaN(FluidEntityClimb.fluidHeight(grass, 0.2, 61.5, 0.2, 0.8, 63.3, 0.8)));
        assertTrue(Double.isNaN(FluidEntityClimb.fluidHeight(null, 0.2, 61.5, 0.2, 0.8, 63.3, 0.8)));
    }
}
