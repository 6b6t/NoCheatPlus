/*
 * This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package fr.neatmonster.nocheatplus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import fr.neatmonster.nocheatplus.checks.moving.ElytraResidual;

/** Elytra unequip window: the legit lag and coast pass, held speed and hovering fail. */
public class TestElytraSwapEnvelope {

    /** CreativeFly elytra limit on live: horizontal speed 450 x 0.6. */
    private static final double CAP = 2.7;

    /** Live commit the change is based on. */
    private static final String BASE = "6b87164494266f6e2bec3719c5cf93adb57bb382";

    private static double fall(final double y) {
        return (y - 0.08) * 0.98;
    }

    /** A move without an elytra in the chest slot, made at this pitch. */
    private static boolean allowAt(final ElytraResidual.State state, final double h, final double y, final double pitch) {
        return ElytraResidual.allow(state, h, y, pitch, false, false, false);
    }

    /** A move without an elytra in the chest slot (no pitch: only the pitch-free envelope). */
    private static boolean allow(final ElytraResidual.State state, final double h, final double y,
                                 final boolean onGround, final boolean resetCond) {
        return ElytraResidual.allow(state, h, y, onGround, resetCond, false);
    }

    /**
     * One vanilla glide tick, LivingEntity#updateFallFlyingMovement, flying straight
     * along the look. Returns {h, y} of the move.
     */
    private static double[] glide(final double h, final double y, final double pitchDegrees) {
        final double pitch = Math.toRadians(pitchDegrees);
        final double cos2 = Math.cos(pitch) * Math.cos(pitch);
        double vy = y + 0.08 * (-1.0 + cos2 * 0.75);
        double nh = h;
        if (vy < 0.0) {
            final double lift = vy * -0.1 * cos2;
            vy += lift;
            nh += lift;
        }
        if (pitch < 0.0) {
            final double climb = h * -Math.sin(pitch) * 0.04;
            nh -= climb;
            vy += climb * 3.2;
        }
        nh += (h - nh) * 0.1;
        return new double[] {nh * 0.99F, vy * 0.98F};
    }

    /** Sprint input in the air, per tick. */
    private static final double AIR_INPUT = 0.026;

    /**
     * Feed a vanilla unequip: the glide moves the client still makes, then the first
     * air tick (it repeats the last glide move plus input), then air drag and gravity
     * until the window closes. Every move must be accepted.
     */
    private static void assertUnequipAccepted(final String label, final double beginH, final double beginY,
                                              final int ping, final List<double[]> glideMoves) {
        final ElytraResidual.State state = new ElytraResidual.State();
        assertTrue(label + " opens", ElytraResidual.begin(state, beginH, beginY, ping, CAP));
        double h = beginH;
        double y = beginY;
        int move = 0;
        for (final double[] glideMove : glideMoves) {
            h = glideMove[0];
            y = glideMove[1];
            move++;
            assertTrue(label + " glide move " + move + " h " + h + " y " + y, allow(state, h, y, false, false));
        }
        h += AIR_INPUT;
        move++;
        assertTrue(label + " first air tick", allow(state, h, y, false, false));
        while (state.open) {
            h = h * 0.91 + AIR_INPUT;
            y = fall(y);
            move++;
            assertTrue(label + " air move " + move + " h " + h + " y " + y, allow(state, h, y, false, false));
            assertTrue(label + " ends", move < 80);
        }
        assertTrue(label + " closes at a normal air move", h <= ElytraResidual.NORMAL_AIR_H);
    }

    @Test
    public void settledCoastIsAcceptedUntilItClosesAtNormalAirSpeed() {
        for (final int ping : new int[] {0, 160, 300}) {
            final ElytraResidual.State state = new ElytraResidual.State();
            assertTrue(ElytraResidual.begin(state, 1.8, -0.15, ping, CAP));
            double h = 1.8;
            double y = -0.15;
            int moves = 0;
            while (true) {
                h *= 0.91;
                y = fall(y);
                moves++;
                assertTrue("ping " + ping + " coast move " + moves + " h " + h, allow(state, h, y, false, false));
                if (h <= ElytraResidual.NORMAL_AIR_H) {
                    break;
                }
                assertTrue(state.open);
            }
            assertFalse("closed at a normal air move", state.open);
            assertFalse(allow(state, h * 0.91, fall(y), false, false));
            assertEquals(18, moves);
        }
        // Measured vanilla coast after the lag (ELYTRA-V2 section 1).
        final ElytraResidual.State measured = new ElytraResidual.State();
        assertTrue(ElytraResidual.begin(measured, 0.786, -0.15, 0, CAP));
        for (int i = 0; i < measured.lagMoves; i++) {
            assertTrue(allow(measured, 0.786, -0.15, false, false));
        }
        double y = -0.15;
        for (final double h : new double[] {0.715, 0.651}) {
            y = fall(y);
            assertTrue("measured " + h, allow(measured, h, y, false, false));
        }
        assertTrue(measured.open);
    }

    @Test
    public void lagGrowthThenDragIsAccepted() {
        // Without a rocket, speed that grows without a descent paying for it (lift) is rejected.
        final ElytraResidual.State plain = new ElytraResidual.State();
        assertTrue(ElytraResidual.begin(plain, 1.45, -0.15, 160, CAP));
        assertTrue(allow(plain, 1.47, -0.15, false, false));
        assertTrue(allow(plain, 1.49, -0.15, false, false));
        assertTrue(allow(plain, 1.51, -0.15, false, false));
        assertFalse("+0.10 without a descent", allow(plain, 1.61, -0.15, false, false));
        // A rocket adds speed along the look: PLAN test 2's growth is accepted while it boosts at pitch -10.
        for (final int ping : new int[] {50, 160}) {
            final ElytraResidual.State state = new ElytraResidual.State();
            assertTrue(ElytraResidual.begin(state, 1.45, -0.15, ping, CAP, true));
            for (final double h : new double[] {1.47, 1.49, 1.51, 1.61}) {
                assertTrue("ping " + ping + " lag " + h, allowAt(state, h, -0.15, -10.0));
            }
            double h = 1.61;
            double y = -0.15;
            int moves = 4;
            while (state.open) {
                h *= 0.91;
                y = fall(y);
                moves++;
                assertTrue("ping " + ping + " move " + moves + " h " + h, allow(state, h, y, false, false));
            }
            assertTrue(h <= ElytraResidual.NORMAL_AIR_H);
        }
    }

    /** Moves accepted before the window rejects one (feeding the same shape until it does). */
    private static int acceptedUntilRejected(final ElytraResidual.State state, final double[] start,
                                             final java.util.function.UnaryOperator<double[]> next) {
        double[] move = start;
        int accepted = 0;
        while (accepted < 100) {
            move = next.apply(move);
            if (!allow(state, move[0], move[1], false, false)) {
                return accepted;
            }
            accepted++;
        }
        throw new AssertionError("never rejected");
    }

    @Test
    public void heldSpeedOrHoverEndsAfterTheLagAndLateGlide() {
        // A held level glide is what a client that is still gliding sends: accepted through the
        // lag phase and the late glide, then once as the first air tick, then rejected.
        final ElytraResidual.State held = new ElytraResidual.State();
        assertTrue(ElytraResidual.begin(held, 1.55, -0.15, 0, CAP));
        assertEquals(3, held.lagMoves);
        for (int i = 0; i < held.lagMoves + ElytraResidual.LATE_GLIDE_MOVES; i++) {
            assertTrue("move " + (i + 1), allow(held, 1.55, -0.15, false, false));
        }
        assertTrue("first air tick, once", allow(held, 1.55, -0.15, false, false));
        assertFalse("constant 1.55 after that", allow(held, 1.55, -0.15, false, false));
        assertFalse(held.open);

        // Hover at a constant speed (Flight): no glide pays for it. Without a rocket the start ceiling
        // is never above 0, so a hover that gains height is rejected at once and a hover at the
        // unequip height lasts through the lag phase and one air tick. With a rocket the start
        // ceiling (start + 0.15) holds it through the lag phase.
        final ElytraResidual.State rising = new ElytraResidual.State();
        assertTrue(ElytraResidual.begin(rising, 1.55, 0.0, 160, CAP));
        assertEquals(0, acceptedUntilRejected(rising, new double[] {1.55, 0.05}, m -> new double[] {1.55, 0.05}));
        final ElytraResidual.State level = new ElytraResidual.State();
        assertTrue(ElytraResidual.begin(level, 1.55, 0.0, 160, CAP));
        assertEquals(level.lagMoves + 1, acceptedUntilRejected(level, new double[] {1.55, 0.0}, m -> m));
        // With a rocket at pitch -10 (boost toward vy +0.3) the hover fits the boost through the lag
        // phase; after it, only the one air tick.
        final ElytraResidual.State rocketHover = new ElytraResidual.State();
        assertTrue(ElytraResidual.begin(rocketHover, 1.55, 0.0, 160, CAP, true));
        int hovered = 0;
        while (hovered < 20 && allowAt(rocketHover, 1.55, 0.15, -10.0)) {
            hovered++;
        }
        assertEquals(rocketHover.lagMoves + 1, hovered);

        // Whatever the shape and ping: never past move 10, never past the lag phase, the late glide and one air tick.
        for (final int ping : new int[] {0, 160, 300, 2000}) {
            final int limit = Math.min(ElytraResidual.MAX_LAG_MOVES, ElytraResidual.lagMoves(ping) + ElytraResidual.LATE_GLIDE_MOVES + 1);
            final java.util.List<java.util.function.UnaryOperator<double[]>> shapes = java.util.Arrays.asList(
                    m -> m,                                          // held speed and height
                    m -> new double[] {m[0] * 0.91, 0.0},            // hover, speed lost to drag pays for it
                    m -> new double[] {m[0] * 0.91, m[1]},           // held height, speed lost
                    m -> new double[] {m[0], fall(m[1])});           // held speed, falling
            for (int i = 0; i < shapes.size(); i++) {
                for (final boolean rocket : new boolean[] {false, true}) {
                    final ElytraResidual.State state = new ElytraResidual.State();
                    assertTrue(ElytraResidual.begin(state, 1.55, -0.15, ping, CAP, rocket));
                    final int accepted = acceptedUntilRejected(state, new double[] {1.55, -0.15}, shapes.get(i));
                    assertTrue("ping " + ping + " shape " + i + " rocket " + rocket + ": " + accepted + " > " + limit,
                            accepted <= limit);
                }
            }
        }
    }

    /** Lab M2 (cand-M23-r1, LabBM151625): Meteor Flight 0.1 right after a real glide and a swap. */
    @Test
    public void flightAfterSwapIsCaughtOnItsFirstMove() {
        final ElytraResidual.State state = new ElytraResidual.State();
        assertTrue(ElytraResidual.begin(state, 1.6245, -0.1588, 160, CAP));
        for (final double[] glideMove : new double[][] {
                {1.6242, -0.1577}, {1.6238, -0.1567}, {1.6233, -0.1559}, {1.6227, -0.1551}, {1.6221, -0.1545}}) {
            assertTrue(allow(state, glideMove[0], glideMove[1], false, false));
        }
        assertFalse("Flight 0.98 / 0.0", allow(state, 0.98, 0.0, false, false));
        assertFalse(state.open);
    }

    @Test
    public void lagIsCappedAtTheElytraLimitAndTenMoves() {
        final ElytraResidual.State state = new ElytraResidual.State();
        assertTrue(ElytraResidual.begin(state, 2.65, -0.15, 0, CAP));
        assertFalse("above the elytra cap", allow(state, 2.75, -0.15, false, false));
        assertFalse(state.open);

        final ElytraResidual.State clamped = new ElytraResidual.State();
        assertTrue(ElytraResidual.begin(clamped, 3.5, -0.15, 0, CAP));
        assertEquals(CAP, clamped.lastH, 0.0);
        assertFalse(allow(clamped, 2.71, -0.15, false, false));

        final ElytraResidual.State growth = new ElytraResidual.State();
        assertTrue(ElytraResidual.begin(growth, 1.0, -0.15, 0, CAP));
        assertFalse("more than 0.15 growth", allow(growth, 1.16, -0.15, false, false));

        assertEquals(3, ElytraResidual.lagMoves(0));
        assertEquals(3, ElytraResidual.lagMoves(-5));
        assertEquals(4, ElytraResidual.lagMoves(1));
        assertEquals(7, ElytraResidual.lagMoves(160));
        assertEquals(9, ElytraResidual.lagMoves(300));
        assertEquals(10, ElytraResidual.lagMoves(2000));
        assertEquals(10, ElytraResidual.lagMoves(Integer.MAX_VALUE));

        final ElytraResidual.State slow = new ElytraResidual.State();
        assertTrue(ElytraResidual.begin(slow, 1.55, -0.15, 2000, CAP));
        assertEquals(10, slow.lagMoves);
        for (int i = 0; i < 10; i++) {
            assertTrue("lag " + i, allow(slow, 1.55, -0.15, false, false));
        }
        assertFalse("11th held move", allow(slow, 1.55, fall(-0.15), false, false));
    }

    @Test
    public void levelWindowRejectsAFreshDiveAndAClimb() {
        final ElytraResidual.State dive = new ElytraResidual.State();
        assertTrue(ElytraResidual.begin(dive, 1.5, -0.15, 160, CAP));
        assertFalse("fresh -3.9", allow(dive, 1.4, -3.9, false, false));
        assertFalse(dive.open);

        // Glide gravity is at most 0.08 per tick: a vanilla pitch-90 tick is accepted, a faster drop is not.
        final ElytraResidual.State gravity = new ElytraResidual.State();
        assertTrue(ElytraResidual.begin(gravity, 1.5, -0.15, 160, CAP));
        assertTrue(allow(gravity, 1.5 * 0.99, fall(-0.15), false, false));
        assertFalse("faster than gravity", allow(gravity, 1.47, fall(-0.15) - 0.1, false, false));
        // A rocket tick has no gravity bound, but the old limit still holds: at most 0.30 below the
        // last move. A boost at pitch +60 from vy -0.15 pushes to about -0.84: rejected.
        final ElytraResidual.State steeper = new ElytraResidual.State();
        assertTrue(ElytraResidual.begin(steeper, 1.5, -0.15, 160, CAP, true));
        final double[] boosted = ElytraResidual.vanillaRocket(1.5, -0.15, 60.0);
        final double rocketY = ElytraResidual.vanillaGlide(boosted[0], boosted[2], 60.0)[1];
        assertTrue(rocketY < -0.15 - 0.30);
        assertFalse("more than 0.30 below the last move", allowAt(steeper, 1.0, rocketY - 0.01, 60.0));

        final ElytraResidual.State climb = new ElytraResidual.State();
        assertTrue(ElytraResidual.begin(climb, 1.5, -0.15, 160, CAP));
        assertFalse("climb above the start + 0.15 that 0.1 of speed cannot pay for", allow(climb, 1.4, 0.01, false, false));

        final ElytraResidual.State floor = new ElytraResidual.State();
        assertTrue(ElytraResidual.begin(floor, 1.5, -3.80, 160, CAP));
        assertFalse("below the -3.95 floor", allow(floor, 1.4, -3.96, false, false));
    }

    @Test
    public void oneWindowPerFortyMovesAndEveryEndCondition() {
        final ElytraResidual.State state = new ElytraResidual.State();
        assertTrue(ElytraResidual.begin(state, 1.5, -0.15, 0, CAP));
        assertFalse("already open", ElytraResidual.begin(state, 1.5, -0.15, 0, CAP));
        assertTrue(allow(state, 1.5, -0.15, false, false));
        ElytraResidual.end(state);
        assertFalse(ElytraResidual.begin(state, 1.5, -0.15, 0, CAP));
        // 1 counted so far; 38 more SurvivalFly moves plus one CreativeFly move make 40.
        for (int i = 0; i < 38; i++) {
            assertFalse(allow(state, 0.2, -0.08, false, false));
        }
        assertFalse("39 moves", ElytraResidual.begin(state, 1.5, -0.15, 0, CAP));
        ElytraResidual.creativeFlyMove(state);
        assertTrue("40 moves", ElytraResidual.begin(state, 1.5, -0.15, 0, CAP));

        // A CreativeFly move (re-equip) ends the window.
        final ElytraResidual.State reequip = new ElytraResidual.State();
        assertTrue(ElytraResidual.begin(reequip, 1.5, -0.15, 0, CAP));
        assertTrue(allow(reequip, 1.5, -0.15, false, false));
        ElytraResidual.creativeFlyMove(reequip);
        assertFalse(reequip.open);
        assertFalse(allow(reequip, 1.5, -0.15, false, false));

        // Ground ends it.
        final ElytraResidual.State ground = new ElytraResidual.State();
        assertTrue(ElytraResidual.begin(ground, 1.5, -0.15, 0, CAP));
        assertFalse(allow(ground, 1.0, 0.0, true, false));
        assertFalse(ground.open);
        assertFalse(allow(ground, 1.0, -0.15, false, false));

        // Liquid or climbable (reset condition) ends it.
        final ElytraResidual.State liquid = new ElytraResidual.State();
        assertTrue(ElytraResidual.begin(liquid, 1.5, -0.15, 0, CAP));
        assertFalse(allow(liquid, 1.0, -0.15, false, true));
        assertFalse(liquid.open);
        assertFalse(allow(liquid, 1.0, -0.15, false, false));

        // A move outside the window ends it for good.
        final ElytraResidual.State broken = new ElytraResidual.State();
        assertTrue(ElytraResidual.begin(broken, 1.5, -0.15, 0, CAP));
        assertFalse(allow(broken, 2.0, -0.15, false, false));
        assertFalse(allow(broken, 1.5, -0.15, false, false));

        // Unusable history never opens a window.
        assertFalse(ElytraResidual.begin(new ElytraResidual.State(), Double.NaN, -0.15, 0, CAP));
        assertFalse(ElytraResidual.begin(new ElytraResidual.State(), 1.5, Double.POSITIVE_INFINITY, 0, CAP));
        assertFalse(ElytraResidual.begin(new ElytraResidual.State(), 1.5, -0.15, 0, 0.0));
    }

    @Test
    public void coastCannotHoverAboveTheCloseThreshold() {
        // Drag plus margin settles near 0.44; the coast still ends after MAX_COAST_MOVES.
        final ElytraResidual.State state = new ElytraResidual.State();
        assertTrue(ElytraResidual.begin(state, 0.5, -0.15, 0, CAP));
        double h = 0.5;
        double y = -0.15;
        for (int i = 0; i < state.lagMoves; i++) {
            assertTrue(allow(state, h, y, false, false));
        }
        int coast = 0;
        while (true) {
            h = h * ElytraResidual.COAST_DRAG + ElytraResidual.COAST_H_MARGIN;
            y = fall(y);
            if (!allow(state, h, y, false, false)) {
                break;
            }
            coast++;
            assertTrue(coast <= ElytraResidual.MAX_COAST_MOVES);
        }
        assertEquals(ElytraResidual.MAX_COAST_MOVES, coast);
        assertFalse(state.open);
    }

    // Lab review 6 Oct (REVIEW-LAB.md problem 1): unequip while the glide still speeds up.

    /** Measured V2 (160 ms RTT) and V3 (300 ms RTT) swaps: {beginH, beginY}, then the window moves the client made. */
    private static final double[][][] LAB_V2 = {
            // cand-V2-r2 (LabBV150557): swaps at vx 0.6, 1.0 and 1.4, pitch 30.
            {{1.219320, -1.070664}, {1.281013, -1.002285}, {1.337519, -0.940299}, {1.389319, -0.884108}, {1.436845, -0.833172}},
            {{1.232017, -1.080362}, {1.294231, -1.011076}, {1.351193, -0.948268}, {1.403388, -0.891332}},
            {{1.520617, -0.745141}, {1.557544, -0.707198}, {1.591566, -0.672802}, {1.622949, -0.641623}, {1.651935, -0.613359}, {1.678742, -0.587737}},
            // fix2-V2-r1 (LabAV163056, slot A): this client kept gliding 8 moves, one more than the lag phase.
            {{1.219320, -1.070664}, {1.281013, -1.002285}, {1.337519, -0.940299}, {1.389319, -0.884108}, {1.436845, -0.833172},
                    {1.480492, -0.786998}, {1.520617, -0.745141}, {1.557544, -0.707198}, {1.591566, -0.672802}},
            // cand-V2-r3 (LabBV152354).
            {{1.151912, -1.146097}, {1.219320, -1.070664}, {1.281013, -1.002285}, {1.337519, -0.940299}, {1.389319, -0.884108}},
            {{1.219320, -1.070664}, {1.281013, -1.002285}, {1.337519, -0.940299}, {1.389319, -0.884108}},
            {{1.451256, -0.839720}, {1.495196, -0.792934}, {1.535571, -0.750522}, {1.572708, -0.712076}, {1.606904, -0.677224}},
    };

    private static final double[][][] LAB_V3 = {
            // cand-V3-r1 (LabBV150642).
            {{1.066545, -1.217268}, {1.139562, -1.135181}, {1.206364, -1.060769}, {1.267525, -0.993315}, {1.323567, -0.932167}},
            {{1.206364, -1.060769}, {1.267525, -0.993315}, {1.323567, -0.932167}, {1.374962, -0.876737}},
            {{1.505358, -0.739650}, {1.542070, -0.702220}, {1.575914, -0.668290}, {1.607152, -0.637533}, {1.636023, -0.609651}, {1.662741, -0.584376}},
            // cand-V3-r2 (LabBV150729).
            {{1.126960, -1.124042}, {1.193144, -1.050671}, {1.253762, -0.984161}, {1.309330, -0.923870}, {1.360313, -0.869215}},
            {{1.139562, -1.135181}, {1.206364, -1.060769}, {1.267525, -0.993315}, {1.323567, -0.932167}},
            {{1.520617, -0.745141}, {1.557544, -0.707198}, {1.591566, -0.672802}, {1.622949, -0.641623}, {1.651935, -0.613359}, {1.678742, -0.587737}},
    };

    @Test
    public void acceleratingUnequipFromTheLabIsAccepted() {
        for (final Object[] run : new Object[][] {{"V2", LAB_V2, 160}, {"V3", LAB_V3, 300}}) {
            final double[][][] swaps = (double[][][]) run[1];
            final int ping = (Integer) run[2];
            final int lateEnd = Math.min(ElytraResidual.MAX_LAG_MOVES, ElytraResidual.lagMoves(ping) + ElytraResidual.LATE_GLIDE_MOVES);
            for (int i = 0; i < swaps.length; i++) {
                final double[][] swap = swaps[i];
                // The client kept gliding for these moves; the lab setback hid the rest. Continue
                // the vanilla glide at pitch 30 for every length up to the end of the late glide.
                for (int extra = 0; swap.length - 1 + extra <= Math.min(lateEnd, ElytraResidual.MAX_LAG_MOVES - 1); extra++) {
                    final List<double[]> moves = new ArrayList<double[]>();
                    for (int j = 1; j < swap.length; j++) {
                        moves.add(swap[j]);
                    }
                    double[] last = swap[swap.length - 1];
                    for (int k = 0; k < extra; k++) {
                        last = glide(last[0], last[1], 30.0);
                        moves.add(last);
                    }
                    assertUnequipAccepted(run[0] + " swap " + i + " +" + extra, swap[0][0], swap[0][1], ping, moves);
                }
            }
        }
    }

    @Test
    public void vanillaUnequipAtAnyPitchIsAccepted() {
        for (final double pitch : new double[] {-40.0, -20.0, -10.0, 0.0, 15.0, 30.0, 45.0, 60.0}) {
            for (final double[] start : new double[][] {{0.6, -0.3}, {1.5, -0.2}, {1.0, -1.5}}) {
                double[] move = start;
                for (int tick = 1; tick <= 41; tick++) {
                    move = glide(move[0], move[1], pitch);
                    if (tick % 12 != 5) {
                        continue;
                    }
                    for (final int ping : new int[] {0, 160, 300}) {
                        final int lateEnd = Math.min(ElytraResidual.MAX_LAG_MOVES, ElytraResidual.lagMoves(ping) + ElytraResidual.LATE_GLIDE_MOVES);
                        for (int extra = 0; extra <= Math.min(lateEnd, ElytraResidual.MAX_LAG_MOVES - 1); extra++) {
                            final List<double[]> moves = new ArrayList<double[]>();
                            double[] next = move;
                            for (int k = 0; k < extra; k++) {
                                next = glide(next[0], next[1], pitch);
                                moves.add(next);
                            }
                            assertUnequipAccepted("pitch " + pitch + " tick " + tick + " ping " + ping + " +" + extra,
                                    move[0], move[1], ping, moves);
                        }
                    }
                }
            }
        }
    }

    @Test
    public void climbWithoutLosingHorizontalSpeedIsRejected() {
        // Level glide, then a climb that keeps its speed: never above the unequip height (vertical
        // speed 0). With a rocket, as far as the boost at the claimed pitch pushes.
        final ElytraResidual.State held = new ElytraResidual.State();
        assertTrue(ElytraResidual.begin(held, 1.5, -0.15, 160, CAP));
        assertTrue(allow(held, 1.5, -0.10, false, false));
        assertTrue(allow(held, 1.5, -0.05, false, false));
        assertTrue(allow(held, 1.5, 0.0, false, false));
        assertFalse("height with the speed held", allow(held, 1.5, 0.05, false, false));
        assertFalse(held.open);
        final ElytraResidual.State rocket = new ElytraResidual.State();
        assertTrue(ElytraResidual.begin(rocket, 1.5, 0.0, 160, CAP, true));
        assertTrue(allowAt(rocket, 1.5, 0.10, -10.0));
        assertTrue(allowAt(rocket, 1.5, 0.15, -10.0));
        assertFalse("above what the boost at pitch -10 gives", allowAt(rocket, 1.5, 0.40, -10.0));
        final ElytraResidual.State flat = new ElytraResidual.State();
        assertTrue(ElytraResidual.begin(flat, 1.5, 0.0, 160, CAP, true));
        assertFalse("a boost at pitch 0 adds no height", allowAt(flat, 1.5, 0.10, 0.0));

        // A vanilla pitch -60 zoom from the same speed pays for its climb and is accepted ...
        final double[] ys = new double[5];
        final double[] hs = new double[5];
        double[] move = {1.65, 0.0};
        final ElytraResidual.State zoom = new ElytraResidual.State();
        assertTrue(ElytraResidual.begin(zoom, 1.65, 0.0, 2000, CAP));
        for (int i = 0; i < 5; i++) {
            move = glide(move[0], move[1], -60.0);
            hs[i] = move[0];
            ys[i] = move[1];
            assertTrue("zoom " + i + " y " + ys[i], allow(zoom, hs[i], ys[i], false, false));
        }
        assertTrue(ys[4] > 0.15 + 0.3);
        // ... but the same climb with the speed held, slowly lost, or pumped up and down is not.
        for (final double[] cheat : new double[][] {
                {1.65, 1.65, 1.65, 1.65, 1.65},
                {1.64, 1.63, 1.62, 1.61, 1.60},
                {1.79, 1.65, 1.79, 1.65, 1.79}}) {
            final ElytraResidual.State state = new ElytraResidual.State();
            assertTrue(ElytraResidual.begin(state, 1.65, 0.0, 2000, CAP));
            assertFalse("zoom height with h " + cheat[0], allow(state, cheat[0], ys[0], false, false));
            assertFalse(state.open);
            // Also at the zoom's own pitch.
            final ElytraResidual.State pitched = new ElytraResidual.State();
            assertTrue(ElytraResidual.begin(pitched, 1.65, 0.0, 2000, CAP));
            assertFalse("pitch -60, zoom height with h " + cheat[0], allowAt(pitched, cheat[0], ys[0], -60.0));
        }
    }

    @Test
    public void speedGainedWithoutLiftCannotPayForALaterClimb() {
        // Climbing glide, then a speed gain no lift explains (as much as the envelope lets through),
        // then that speed is "spent" on a climb: the debt is repaid first.
        final ElytraResidual.State pumped = new ElytraResidual.State();
        assertTrue(ElytraResidual.begin(pumped, 1.65, 0.0, 2000, CAP));
        double[] move = {1.65, 0.0};
        for (int i = 0; i < 2; i++) {
            move = glide(move[0], move[1], -60.0);
            assertTrue(allow(pumped, move[0], move[1], false, false));
        }
        final double pumpH = move[0] + 0.06;
        final double pumpY = ElytraResidual.minGlideY(move[1], move[0], pumpH) + 0.001;
        assertTrue(pumpY <= ElytraResidual.maxGlideY(move[1], move[0], 0.0));
        assertTrue(allow(pumped, pumpH, pumpY, false, false));
        assertTrue(pumped.hDebt > 0.05);
        // Without the debt the same last move would pay for this climb.
        final double probeY = ElytraResidual.maxGlideY(pumpY, pumpH, pumpH + 0.9 * 0.1 * Math.max(0.0, 0.08 - pumpY) - move[0] / 0.99) - 0.002;
        assertTrue(probeY > pumpY + 0.05);
        final ElytraResidual.State fresh = new ElytraResidual.State();
        assertTrue(ElytraResidual.begin(fresh, pumpH, pumpY, 2000, CAP));
        assertTrue("no debt: paid", allow(fresh, move[0], probeY, false, false));
        assertFalse("the debt is repaid first", allow(pumped, move[0], probeY, false, false));
    }

    @Test
    public void chestElytraEndsTheWindowEvenWithoutGliding() {
        final ElytraResidual.State state = new ElytraResidual.State();
        assertTrue(ElytraResidual.begin(state, 1.5, -0.15, 160, CAP));
        assertTrue(allow(state, 1.5, -0.15, false, false));
        // Re-equipped but not gliding (SurvivalFly path): ends the window, keeps the re-arm count.
        assertFalse(ElytraResidual.allow(state, 1.5, -0.15, false, false, true));
        assertFalse(state.open);
        assertFalse("unequip again: no credit", allow(state, 1.5, -0.15, false, false));
        assertFalse("no new window inside 40 moves", ElytraResidual.begin(state, 1.5, -0.15, 160, CAP));
        assertEquals(3, state.sinceBegin);
    }

    @Test
    public void lagCapUsesCreativeFlyModifiersButNeverMore() {
        final double none = Double.NEGATIVE_INFINITY;
        final double unknown = Double.MAX_VALUE;
        assertEquals(fr.neatmonster.nocheatplus.checks.moving.magic.Magic.DEFAULT_WALKSPEED,
                ElytraResidual.DEFAULT_WALK_SPEED, 0.0);
        assertEquals(CAP, ElytraResidual.elytraCap(CAP, true, none, unknown, 0.2), 1e-12);
        assertEquals(CAP, ElytraResidual.elytraCap(CAP, true, none, 1.0, 0.2), 1e-12);
        // Decreasing modifiers lower the cap like CreativeFly does.
        assertEquals(1.35, ElytraResidual.elytraCap(CAP, true, none, unknown, 0.1), 1e-12);
        assertEquals(1.35, ElytraResidual.elytraCap(CAP, true, none, 0.5, 0.2), 1e-12);
        assertEquals(0.675, ElytraResidual.elytraCap(CAP, true, none, 0.5, 0.1), 1e-12);
        // Increasing ones never raise it above the model limit.
        assertEquals(CAP, ElytraResidual.elytraCap(CAP, true, 1.0, unknown, 0.2), 1e-12);
        assertEquals(CAP, ElytraResidual.elytraCap(CAP, true, none, 1.5, 0.4), 1e-12);
        assertEquals(2.7 * 1.4 * 0.5, ElytraResidual.elytraCap(CAP, true, 1.0, 0.5, 0.2), 1e-12);
        // A model without modifiers keeps its limit; unusable input opens no window.
        assertEquals(CAP, ElytraResidual.elytraCap(CAP, false, none, 0.5, 0.1), 0.0);
        assertEquals(0.0, ElytraResidual.elytraCap(CAP, true, none, unknown, 0.0), 0.0);
        assertEquals(0.0, ElytraResidual.elytraCap(CAP, true, none, unknown, Double.NaN), 0.0);
        assertEquals(0.0, ElytraResidual.elytraCap(Double.NaN, true, none, unknown, 0.2), 0.0);
        assertFalse(ElytraResidual.begin(new ElytraResidual.State(), 1.0, -0.15, 160,
                ElytraResidual.elytraCap(CAP, true, none, unknown, 0.0)));

        // REVIEW-CODE finding 2: walk speed 0.1, glide at 1.35. The lag phase may not grow past 1.35.
        final double slowCap = ElytraResidual.elytraCap(CAP, true, none, unknown, 0.1);
        final ElytraResidual.State slow = new ElytraResidual.State();
        assertTrue(ElytraResidual.begin(slow, 1.35, -0.15, 160, slowCap));
        assertFalse("1.50 is above what wearing the elytra allows", allow(slow, 1.50, -0.15, false, false));
        final ElytraResidual.State slow2 = new ElytraResidual.State();
        assertTrue(ElytraResidual.begin(slow2, 1.8, -0.15, 160, slowCap));
        assertEquals(1.35, slow2.lastH, 1e-12);
        assertTrue(allow(slow2, 1.35, -0.15, false, false));
        assertFalse(allow(slow2, 1.36, -0.15, false, false));
    }

    // Round 3 (REVIEW-CODE-2 finding 1 and 5, REVIEW-LAB-2 problem 2) -------------

    /** REVIEW-CODE-2 finding 1, REVIEW-2-envelope-probe.log: the late glide grew past the cap. */
    @Test
    public void lateGlideCannotPassTheCapReviewCounterexample() {
        for (final double cap : new double[] {CAP, 1.35}) {
            for (final boolean rocket : new boolean[] {false, true}) {
                final ElytraResidual.State state = new ElytraResidual.State();
                assertTrue(ElytraResidual.begin(state, cap, -2.0, 160, cap, rocket));
                assertEquals(7, state.lagMoves);
                for (int i = 0; i < 7; i++) {
                    assertTrue("cap " + cap + " move " + (i + 1), allow(state, cap, -2.0, false, false));
                }
                // The probe's move 8: h = (lastH + 0.9 x 0.1 x (0.08 - lastY)) x 0.99, y = -2.
                final double h8 = (cap + 0.9 * 0.1 * (0.08 + 2.0)) * 0.99;
                assertEquals(cap == CAP ? 2.858328 : 1.521828, h8, 1e-9);
                assertFalse("cap " + cap + " rocket " + rocket + ": h " + h8, allow(state, h8, -2.0, false, false));
                assertFalse(state.open);
            }
        }
    }

    @Test
    public void noWindowMoveIsFasterThanTheCapInAnyPhase() {
        // Lag phase.
        final ElytraResidual.State lag = new ElytraResidual.State();
        assertTrue(ElytraResidual.begin(lag, 1.30, -1.0, 160, 1.35));
        assertFalse(allow(lag, 1.36, -0.9, false, false));
        // Late glide: a vanilla pull-out tick that would pass 1.35.
        final ElytraResidual.State late = new ElytraResidual.State();
        assertTrue(ElytraResidual.begin(late, 1.35, -1.5, 0, 1.35));
        for (int i = 0; i < late.lagMoves; i++) {
            assertTrue(allow(late, 1.35, -1.5 + 0.05 * (i + 1), false, false));
        }
        final double[] pullOut = glide(1.35, late.lastY, 0.0);
        assertTrue(pullOut[0] > 1.35);
        assertFalse("late glide over the cap", allow(late, pullOut[0], pullOut[1], false, false));
        // The repeated first air tick with sprint input on top of a capped move.
        final ElytraResidual.State repeat = new ElytraResidual.State();
        assertTrue(ElytraResidual.begin(repeat, 1.35, -0.3, 160, 1.35));
        assertFalse(allow(repeat, 1.35 + 0.03, -0.3, false, false));
        // Coast: drag + margin can exceed a very small cap.
        final ElytraResidual.State coast = new ElytraResidual.State();
        assertTrue(ElytraResidual.begin(coast, 0.30, -0.15, 0, 0.30));
        for (int i = 0; i < coast.lagMoves; i++) {
            assertTrue(allow(coast, 0.30, fall(coast.lastY), false, false));
        }
        assertFalse(allow(coast, 0.30 * ElytraResidual.COAST_DRAG + 0.039, fall(coast.lastY), false, false));
    }

    /** REVIEW-CODE-2 finding 5: lift into horizontal speed must come out of the downward speed. */
    @Test
    public void liftIntoSpeedMustComeOutOfTheDescent() {
        final double[] vanilla = glide(2.0, -1.5, 0.0);
        assertTrue(vanilla[0] > 2.1 && vanilla[1] > -1.4);
        final ElytraResidual.State ok = new ElytraResidual.State();
        assertTrue(ElytraResidual.begin(ok, 2.0, -1.5, 160, CAP));
        assertTrue("vanilla pitch 0 tick", allow(ok, vanilla[0], vanilla[1], false, false));
        final ElytraResidual.State kept = new ElytraResidual.State();
        assertTrue(ElytraResidual.begin(kept, 2.0, -1.5, 160, CAP));
        assertFalse("full lift into h, descent kept", allow(kept, vanilla[0], -1.5, false, false));
        assertTrue(ElytraResidual.minGlideY(-1.5, 2.0, vanilla[0]) > -1.46);
        // At any pitch the vanilla tick is inside both bounds.
        for (double pitch = -90.0; pitch <= 90.0; pitch += 7.5) {
            for (final double[] from : new double[][] {{0.5, -0.4}, {1.6, -0.15}, {2.0, -2.0}, {1.2, 0.6}}) {
                final double[] next = glide(from[0], from[1], pitch);
                assertTrue("pitch " + pitch, next[1] >= ElytraResidual.minGlideY(from[1], from[0], next[0]));
                assertTrue("pitch " + pitch, next[1] <= ElytraResidual.maxGlideY(from[1], from[0],
                        Math.max(0.0, from[0] + 0.9 * 0.1 * Math.max(0.0, 0.08 - from[1]) - next[0] / 0.99)));
            }
        }
    }

    /** REVIEW-LAB-2 problem 2: a continued settled glide got 11 moves. */
    @Test
    public void glideLikeMovesNeverPassMoveTen() {
        for (final int ping : new int[] {0, 160, 300, 2000}) {
            for (final double pitch : new double[] {0.0, 30.0, -20.0}) {
                // Settle the glide first.
                double[] move = {1.6, -0.15};
                for (int i = 0; i < 200; i++) {
                    move = glide(move[0], move[1], pitch);
                }
                final ElytraResidual.State state = new ElytraResidual.State();
                assertTrue(ElytraResidual.begin(state, move[0], move[1], ping, CAP));
                int accepted = 0;
                while (accepted < 20) {
                    move = glide(move[0], move[1], pitch);
                    if (!allow(state, move[0], move[1], false, false)) {
                        break;
                    }
                    accepted++;
                }
                final int lateEnd = Math.min(ElytraResidual.MAX_LAG_MOVES, ElytraResidual.lagMoves(ping) + ElytraResidual.LATE_GLIDE_MOVES);
                assertTrue("ping " + ping + " pitch " + pitch + ": " + accepted, accepted <= lateEnd + 1);
                assertTrue("ping " + ping + " pitch " + pitch + ": " + accepted, accepted <= ElytraResidual.MAX_LAG_MOVES);
            }
        }
        // The repeat after a 9-move glide is move 10 and accepted; after a 10-move glide it is not.
        for (final int glideMoves : new int[] {9, 10}) {
            final ElytraResidual.State state = new ElytraResidual.State();
            double[] move = {1.6, -0.6};
            assertTrue(ElytraResidual.begin(state, move[0], move[1], 160, CAP));
            for (int i = 0; i < glideMoves; i++) {
                move = glide(move[0], move[1], 30.0);
                assertTrue(allow(state, move[0], move[1], false, false));
            }
            assertEquals(glideMoves == 9, allow(state, move[0] + 0.02, move[1], false, false));
        }
    }

    /** REVIEW-LAB-2 N1 (slot A, jar fd22ab39): measured pull-up and zoom unequips, all accepted there. */
    private static final double[][][] LAB_PULL_UPS = {
            // LabAV181301: unequip while pulling out of a dive (window of 10 moves logged).
            {{1.175876, -1.167279}, {1.244460, -1.089866}, {1.307184, -1.019691}, {1.364592, -0.956077}, {1.417175, -0.898412},
                    {1.465379, -0.846138}, {1.509607, -0.798751}, {1.550226, -0.755796}, {1.587569, -0.716856},
                    {1.621936, -0.681558}, {1.653601, -0.649560}},
            // LabAV182034: zoom from a dive, vy +0.85 at the swap, climbs to +1.21, then the coast (21 moves).
            {{1.686248, 0.854111}, {1.626890, 0.937598}, {1.569621, 1.014151}, {1.514369, 1.084092}, {1.461061, 1.147734},
                    {1.409630, 1.205375}, {1.409630, 1.205375}, {1.282763, 1.102867}, {1.167315, 1.002410}, {1.062256, 0.903962},
                    {0.966653, 0.807483}, {0.879654, 0.712933}, {0.800486, 0.620274}, {0.728442, 0.529469}, {0.662882, 0.440479},
                    {0.603223, 0.353270}, {0.548933, 0.267804}, {0.499529, 0.184048}, {0.454571, 0.101967}, {0.413660, 0.021528},
                    {0.376430, -0.057303}, {0.342552, -0.134556}},
            // LabAV182145: the same zoom from vy +0.77 (21 moves).
            {{1.793559, 0.772682}, {1.730423, 0.867316}, {1.669510, 0.954457}, {1.610741, 1.034453}, {1.554041, 1.107636},
                    {1.554041, 1.107636}, {1.414177, 1.007083}, {1.286901, 0.908541}, {1.171080, 0.811971}, {1.065683, 0.717331},
                    {0.969772, 0.624585}, {0.882492, 0.533693}, {0.803068, 0.444619}, {0.730792, 0.357327}, {0.665021, 0.271780},
                    {0.605169, 0.187945}, {0.550704, 0.105786}, {0.501140, 0.025270}, {0.456038, -0.053635}, {0.414994, -0.130963},
                    {0.377645, -0.206743}, {0.343657, -0.281009}},
    };

    /** The pitch each LAB_PULL_UPS client flew at. */
    private static final double[] LAB_PULL_UP_PITCH = {30.0, -45.0, -45.0};

    @Test
    public void labPullUpUnequipsStayAccepted() {
        for (int i = 0; i < LAB_PULL_UPS.length; i++) {
            final double[][] swap = LAB_PULL_UPS[i];
            for (final boolean withPitch : new boolean[] {false, true}) {
                final ElytraResidual.State state = new ElytraResidual.State();
                assertTrue(ElytraResidual.begin(state, swap[0][0], swap[0][1], 160, CAP));
                for (int j = 1; j < swap.length; j++) {
                    final double pitch = withPitch ? LAB_PULL_UP_PITCH[i] : Double.NaN;
                    assertTrue("pull-up " + i + " move " + j + " pitch " + pitch, allowAt(state, swap[j][0], swap[j][1], pitch));
                }
            }
        }
    }

    /** With a pitch, glide moves follow the vanilla tick at that pitch: a zoom is paid by the speed it loses. */
    @Test
    public void zoomFollowsVanillaAtItsPitchAndIsPaidBySpeed() {
        for (final double pitch : new double[] {-30.0, -45.0, -60.0, -75.0, -90.0}) {
            // A vanilla zoom from 2.7 b/t at vy +1.0 continues through the lag phase and the late glide.
            final ElytraResidual.State state = new ElytraResidual.State();
            assertTrue(ElytraResidual.begin(state, 2.7, 1.0, 160, CAP));
            double[] move = {2.7, 1.0};
            for (int i = 0; i < 9; i++) {
                move = glide(move[0], move[1], pitch);
                assertTrue("pitch " + pitch + " move " + (i + 1), ElytraResidual.allow(state, move[0], move[1], pitch, false, false, false));
            }
            // The same climb without losing the speed is not a glide at any pitch.
            for (double claimed = -90.0; claimed <= 90.0; claimed += 7.5) {
                final ElytraResidual.State held = new ElytraResidual.State();
                assertTrue(ElytraResidual.begin(held, 2.0, 0.3, 160, CAP));
                final double[] vanilla = glide(2.0, 0.3, pitch);
                assertFalse("claimed pitch " + claimed, ElytraResidual.allow(held, 2.0, vanilla[1] + 0.02, claimed, false, false, false));
            }
        }
        // Without a rocket a start at vy +1.0 cannot be held: only the one repeat is accepted.
        final ElytraResidual.State held = new ElytraResidual.State();
        assertTrue(ElytraResidual.begin(held, 1.6, 1.0, 160, CAP));
        assertTrue("first air tick", ElytraResidual.allow(held, 1.6, 1.0, -20.0, false, false, false));
        assertFalse("held vy +1.0", ElytraResidual.allow(held, 1.6, 1.0, -20.0, false, false, false));
    }

    // Round 4 (REVIEW-LAB-3 R1 and Z1, REVIEW-CODE-3 findings 1 and 2) -------------

    /**
     * REVIEW-LAB-3 R1 (smoke/rev3, jar 79cfb400): unequips 0.05-0.2 s after firing a rocket, each
     * {{beginH, beginY}, window moves...}, all set back by that jar at move 1 or 2.
     */
    private static final double[][][] LAB_ROCKET = {
            {{1.106954, 0.868272}, {1.164963, 1.014073}, {1.193014, 1.087854}},                          // R1-r1, pitch -40
            {{0.760906, 1.184001}, {0.772516, 1.325327}, {0.778084, 1.395207}},                          // R1-r2, pitch -60
            {{0.976011, 0.588090}, {1.101646, 0.871505}},                                                // R1-r3, pitch -40
            {{1.242759, 0.003056}, {1.434850, 0.156500}},                                                // R1-r3, pitch -10
            {{1.230201, 0.001370}, {1.212172, 0.007490}, {1.419804, 0.158339}},                          // R1S-r1, pitch -10, boost starts on move 2
            {{1.108204, 0.959999}, {1.165568, 1.059070}, {1.193306, 1.109927}, {1.206719, 1.135965}},    // R1S-r1, pitch -40
            {{0.716190, 0.031067}, {0.976011, 0.588090}},                                                // R1S-r1, pitch -40
            {{1.236227, 0.012199}, {1.431637, 0.160908}},                                                // R1S-r2, pitch -10
    };
    private static final double[] LAB_ROCKET_PITCH = {-40.0, -60.0, -40.0, -10.0, -10.0, -40.0, -40.0, -10.0};

    @Test
    public void labRocketUnequipsRightAfterFiringAreAccepted() {
        for (int i = 0; i < LAB_ROCKET.length; i++) {
            final double[][] swap = LAB_ROCKET[i];
            final ElytraResidual.State state = new ElytraResidual.State();
            assertTrue(ElytraResidual.begin(state, swap[0][0], swap[0][1], 160, CAP, 25));
            for (int j = 1; j < swap.length; j++) {
                assertTrue("rocket " + i + " move " + j, allowAt(state, swap[j][0], swap[j][1], LAB_ROCKET_PITCH[i]));
            }
            // Without the boost (no rocket fired, or it has expired) the same moves are not a glide.
            final ElytraResidual.State none = new ElytraResidual.State();
            assertTrue(ElytraResidual.begin(none, swap[0][0], swap[0][1], 160, CAP, 0));
            boolean all = true;
            for (int j = 1; j < swap.length; j++) {
                all &= allowAt(none, swap[j][0], swap[j][1], LAB_ROCKET_PITCH[i]);
            }
            assertFalse("rocket " + i + " without a boost", all);
        }
    }

    /** V1 rocket (all rounds): two rockets at once hold the glide at 1.6382 / 0.3070, then the first air tick and the coast. */
    @Test
    public void twoRocketsAtOnceAreAccepted() {
        final double[][] moves = {
                {1.638217868957895, 0.3070340032664376}, {1.6382178689579519, 0.30703400326649444},
                {1.6382178689579519, 0.30703400326649444}, {1.6382178689579519, 0.30703400326649444},
                {1.6561876518299528, 0.30108228600462894}, {1.507130806600486, 0.2166606445013599}};
        // NCP's boost counter showed 6 ticks left at the unequip.
        final ElytraResidual.State state = new ElytraResidual.State();
        assertTrue(ElytraResidual.begin(state, 1.638217868957895, 0.3070340032664376, 160, CAP, 6));
        for (int j = 0; j < moves.length; j++) {
            assertTrue("move " + (j + 1), allowAt(state, moves[j][0], moves[j][1], -10.0));
        }
        // The fixed point of boost, boost, glide at pitch -10 is that move.
        final double[] two = ElytraResidual.vanillaRocket(1.638217868957895, 0.3070340032664376, -10.0);
        assertTrue(two[0] >= 1.6382 && two[2] >= 0.30);
    }

    /** REVIEW-LAB-3 Z1: steep zooms at about 2 b/t (pitch -45 to -75), set back by the 0.5 rise cap. */
    private static final double[][][] LAB_ZOOM = {
            {{1.884463, 0.978853}, {1.800745, 1.113145}, {1.720746, 1.234607}, {1.644302, 1.343948}, {1.571253, 1.441839}, {1.501450, 1.528922}},
            {{2.079883, 0.386466}, {2.006669, 0.514221}, {1.936031, 0.632927}, {1.867881, 0.742994}, {1.802129, 0.844814}, {1.738692, 0.938766}},
            {{1.819027, 0.620444}, {1.738216, 0.753976}, {1.660995, 0.875046}, {1.587205, 0.984337}, {1.516693, 1.082503}, {1.449313, 1.170161}},
            {{2.166224, 0.344517}, {2.089970, 0.480769}, {2.016400, 0.607533}, {1.945420, 0.725236}, {1.876939, 0.834289}, {1.810868, 0.935087}},
            {{1.813376, 0.695441}, {1.749543, 0.793378}, {1.687957, 0.883694}, {1.628538, 0.966741}, {1.571212, 1.042856},
                    {1.515903, 1.112365}, {1.462541, 1.175577}, {1.411058, 1.232792}},
            {{1.980624, 0.681081}, {1.899688, 0.818915}, {1.822059, 0.945200}, {1.747602, 1.060527}, {1.676188, 1.165459}, {1.607692, 1.260534}},
            {{1.795896, 0.564435}, {1.716112, 0.696285}, {1.639874, 0.815830}, {1.567022, 0.923747}, {1.497406, 1.020679}, {1.430884, 1.107237}},
    };
    private static final double[] LAB_ZOOM_PITCH = {-75.0, -45.0, -75.0, -45.0, -45.0, -60.0, -75.0};

    @Test
    public void labSteepZoomsAreAccepted() {
        for (int i = 0; i < LAB_ZOOM.length; i++) {
            final double[][] swap = LAB_ZOOM[i];
            final ElytraResidual.State state = new ElytraResidual.State();
            assertTrue(ElytraResidual.begin(state, swap[0][0], swap[0][1], 160, CAP));
            for (int j = 1; j < swap.length; j++) {
                assertTrue("zoom " + i + " move " + j, allowAt(state, swap[j][0], swap[j][1], LAB_ZOOM_PITCH[i]));
            }
            // Continue the vanilla zoom to the end of the lag phase and late glide: still accepted.
            double[] move = swap[swap.length - 1];
            for (int j = swap.length; j < 10; j++) {
                move = glide(move[0], move[1], LAB_ZOOM_PITCH[i]);
                assertTrue("zoom " + i + " vanilla move " + j, allowAt(state, move[0], move[1], LAB_ZOOM_PITCH[i]));
            }
        }
    }

    /** REVIEW-CODE-3 finding 2 (review3-evidence/Rise.java): the repeat rose 0.0049 above the last move. */
    @Test
    public void theRepeatedAirTickCannotRise() {
        for (final double pitch : new double[] {Double.NaN, -60.0, 0.0}) {
            final ElytraResidual.State state = new ElytraResidual.State();
            assertTrue(ElytraResidual.begin(state, 2.7, 0.0, 500, CAP, false));
            final boolean first = allowAt(state, 2.5839, 0.265287999, pitch);
            final boolean second = first && allowAt(state, 2.465970804, 0.5, pitch);
            assertFalse("pitch " + pitch, second && allowAt(state, 2.465970804, 0.5049, pitch));
        }
        // Rising nothing is still the first air tick; falling it may be within rounding.
        final ElytraResidual.State exact = new ElytraResidual.State();
        assertTrue(ElytraResidual.begin(exact, 1.5, 0.3, 160, CAP));
        assertTrue(allowAt(exact, 1.5 + 0.026, 0.3, -20.0));
        // V5 level (smoke/rev2, LabAV173117): the worker handoff pause after a level glide.
        final ElytraResidual.State pause = new ElytraResidual.State();
        assertTrue(ElytraResidual.begin(pause, 1.3996588690327485, -0.17293064696175975, 160, CAP));
        assertTrue(allowAt(pause, 0.005135724080048476, -0.05083848129228841, 0.0));
        assertTrue(allowAt(pause, 0.008060171552472184, -0.046921061110168694, 0.0));
    }

    /** REVIEW-CODE-3 finding 1 (review3-evidence/Probe.java): late glide gained 0.2 per move from a deep descent. */
    @Test
    public void lateGlideGrowsNoFasterThanTheLagPhase() {
        // The probe itself: +0.15 per lag move while keeping vy -3.9 is lift without descent, rejected at once.
        final ElytraResidual.State probe = new ElytraResidual.State();
        assertTrue(ElytraResidual.begin(probe, 1.6, -3.9, 150, CAP, true));
        assertFalse(allow(probe, 1.75, -3.9, false, false));
        // A deep descent into the late glide: the late move may not grow more than +0.15 either.
        for (final double grow : new double[] {0.20, 0.15}) {
            final ElytraResidual.State t = new ElytraResidual.State();
            assertTrue(ElytraResidual.begin(t, 2.35, -3.9, 0, CAP));
            for (int i = 1; i <= t.lagMoves; i++) {
                assertTrue(allow(t, 2.35, -3.9, false, false));
            }
            final double h = 2.35 + grow;
            assertTrue(h <= (2.35 + 0.9 * 0.1 * (0.08 + 3.9)) * 0.99 + 0.005);
            assertEquals("late +" + grow, grow <= 0.15, allow(t, h, -3.5, false, false));
        }
    }

    @Test
    public void rocketTicksOnlyInTheLagPhaseWhileTheBoostLasts() {
        // Boost toward the look from (1.0, 0.0) at pitch -40: the tick a rocket gives.
        final double[] boosted = ElytraResidual.vanillaRocket(1.0, 0.0, -40.0);
        final double[] tick = ElytraResidual.vanillaGlide(boosted[0], boosted[1], -40.0);
        // Accepted while NCP's boost ticks last, not after.
        final ElytraResidual.State two = new ElytraResidual.State();
        assertTrue(ElytraResidual.begin(two, 1.0, 0.0, 300, CAP, 2));
        assertTrue(allowAt(two, tick[0], tick[1], -40.0));
        double[] next = ElytraResidual.vanillaRocket(tick[0], tick[1], -40.0);
        double[] tick2 = ElytraResidual.vanillaGlide(next[0], next[1], -40.0);
        assertTrue(allowAt(two, tick2[0], tick2[1], -40.0));
        next = ElytraResidual.vanillaRocket(tick2[0], tick2[1], -40.0);
        final double[] tick3 = ElytraResidual.vanillaGlide(next[0], next[1], -40.0);
        assertFalse("third boost after 2 boost ticks", allowAt(two, tick3[0], tick3[1], -40.0));
        // Never in the late glide (after the lag phase), and never without a pitch.
        final ElytraResidual.State late = new ElytraResidual.State();
        assertTrue(ElytraResidual.begin(late, 1.0, 0.0, 0, CAP, 30));
        for (int i = 0; i < late.lagMoves; i++) {
            assertTrue(allowAt(late, 1.0, -0.05 * (i + 1), 0.0));
        }
        final double[] lateBoost = ElytraResidual.vanillaRocket(1.0, late.lastY, -40.0);
        final double[] lateTick = ElytraResidual.vanillaGlide(lateBoost[0], lateBoost[1], -40.0);
        assertFalse("boost in the late glide", allowAt(late, lateTick[0], lateTick[1], -40.0));
        final ElytraResidual.State noPitch = new ElytraResidual.State();
        assertTrue(ElytraResidual.begin(noPitch, 1.0, 0.0, 300, CAP, 30));
        assertFalse(allow(noPitch, tick[0], tick[1], false, false));
    }

    // Source check -------------------------------------------------------------

    private static final String MOVING = "NCPCore/src/main/java/fr/neatmonster/nocheatplus/checks/moving/";

    /** Tokens of a stored credential, server-made motion, scheduling, clock or callback. */
    private static final String[] FORBIDDEN = {
            "PersistentDataContainer", "PersistentDataType", "NamespacedKey", "getPersistentDataContainer",
            "setVelocity", "getScheduler", "BukkitRunnable", "BukkitScheduler", "runTask", "runTaskLater",
            "RegionScheduler", "EntityScheduler", "Consumer<", "Runnable", "Callable",
            "ElytraResidualCarry", "currentTimeMillis", "nanoTime",
    };

    private static File root() {
        File dir = new File("").getAbsoluteFile();
        while (dir != null) {
            if (new File(dir, MOVING + "ElytraResidual.java").isFile()) {
                return dir;
            }
            dir = dir.getParentFile();
        }
        throw new AssertionError("NCPCore sources not found");
    }

    private static String read(final File root, final String path) throws IOException {
        return new String(Files.readAllBytes(new File(root, path).toPath()), StandardCharsets.UTF_8);
    }

    private static int count(final String text, final String token) {
        int n = 0;
        int i = text.indexOf(token);
        while (i >= 0) {
            n++;
            i = text.indexOf(token, i + token.length());
        }
        return n;
    }

    @Test
    public void elytraResidualHasNoStoredCredentialMotionSchedulerOrCallback() throws IOException {
        final String source = read(root(), MOVING + "ElytraResidual.java");
        for (final String token : FORBIDDEN) {
            assertEquals(token, 0, count(source, token));
        }
        for (final String token : new String[] {"import org.bukkit", "import java.util.UUID", "System."}) {
            assertEquals(token, 0, count(source, token));
        }
        // No static callback or other mutable static state: every static field is a final constant.
        for (final Field field : ElytraResidual.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                assertTrue(field.getName(), Modifier.isFinal(field.getModifiers()));
                assertTrue(field.getName(), field.getType().isPrimitive());
            }
        }
        for (final Field field : ElytraResidual.State.class.getDeclaredFields()) {
            assertFalse(field.getName(), Modifier.isStatic(field.getModifiers()));
            assertTrue(field.getName(), field.getType().isPrimitive());
        }
    }

    @Test
    public void wiringAddsNoStoredCredentialMotionSchedulerOrCallback() throws IOException, InterruptedException {
        final File root = root();
        // Pinned to the live base: the change may not add any of these to the files it touches.
        final String[][] pinned = {
                {"MovingListener.java", "currentTimeMillis", "7"},
                {"player/SurvivalFly.java", "setVelocity", "3"},
                {"player/SurvivalFly.java", "currentTimeMillis", "2"},
                {"MovingData.java", "setVelocity", "4"},
                {"MovingData.java", "currentTimeMillis", "3"},
                {"MovingData.java", "Callable", "1"},
        };
        for (final String file : new String[] {"MovingListener.java", "player/SurvivalFly.java", "MovingData.java"}) {
            final String source = read(root, MOVING + file);
            for (final String token : FORBIDDEN) {
                int expected = 0;
                for (final String[] pin : pinned) {
                    if (pin[0].equals(file) && pin[1].equals(token)) {
                        expected = Integer.parseInt(pin[2]);
                    }
                }
                assertEquals(file + " " + token, expected, count(source, token));
            }
        }
        // When this is a git checkout of the branch, check the added lines of the diff itself.
        final List<String> added = addedLinesSinceBase(root);
        if (added != null) {
            assertFalse("diff is empty", added.isEmpty());
            for (final String line : added) {
                for (final String token : FORBIDDEN) {
                    assertFalse(token + " in: " + line, line.contains(token));
                }
                assertFalse(line, line.contains("import org.bukkit.persistence"));
                assertFalse(line, line.contains("static Consumer") || line.contains("static Runnable"));
            }
        }
    }

    /** Added main-source lines since {@link #BASE}, or null without git or the base commit. */
    private static List<String> addedLinesSinceBase(final File root) throws IOException, InterruptedException {
        final Process process;
        try {
            process = new ProcessBuilder("git", "-C", root.getPath(), "diff", "--unified=0", BASE, "--",
                    "NCPCore/src/main", "NCPCompatBukkit/src/main", "NCPPlugin/src/main")
                    .redirectErrorStream(true).start();
        }
        catch (final IOException e) {
            return null;
        }
        final List<String> lines = new ArrayList<String>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("+") && !line.startsWith("+++")) {
                    lines.add(line);
                }
            }
        }
        if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
            return null;
        }
        return lines;
    }
}
