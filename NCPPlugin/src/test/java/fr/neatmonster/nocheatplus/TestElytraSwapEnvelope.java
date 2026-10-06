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

    /** A move without an elytra in the chest slot. */
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
        for (final int ping : new int[] {50, 160}) {
            final ElytraResidual.State state = new ElytraResidual.State();
            assertTrue(ElytraResidual.begin(state, 1.45, -0.15, ping, CAP));
            for (final double h : new double[] {1.47, 1.49, 1.51, 1.61}) {
                assertTrue("ping " + ping + " lag " + h, allow(state, h, -0.15, false, false));
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

        // Hover at y = 0 at a constant speed (Flight): no glide pays for it, so only the first air tick follows the lag.
        final ElytraResidual.State hover = new ElytraResidual.State();
        assertTrue(ElytraResidual.begin(hover, 1.55, 0.0, 160, CAP));
        assertEquals(hover.lagMoves + 1, acceptedUntilRejected(hover, new double[] {1.55, 0.0}, m -> m));

        // Whatever the shape and ping: never more than the lag phase, the late glide and one air tick.
        for (final int ping : new int[] {0, 160, 300, 2000}) {
            final int limit = Math.min(ElytraResidual.MAX_LAG_MOVES, ElytraResidual.lagMoves(ping) + ElytraResidual.LATE_GLIDE_MOVES) + 1;
            final java.util.List<java.util.function.UnaryOperator<double[]>> shapes = java.util.Arrays.asList(
                    m -> m,                                          // held speed and height
                    m -> new double[] {m[0] * 0.91, 0.0},            // hover, speed lost to drag pays for it
                    m -> new double[] {m[0] * 0.91, m[1]},           // held height, speed lost
                    m -> new double[] {m[0], fall(m[1])});           // held speed, falling
            for (int i = 0; i < shapes.size(); i++) {
                final ElytraResidual.State state = new ElytraResidual.State();
                assertTrue(ElytraResidual.begin(state, 1.55, -0.15, ping, CAP));
                final int accepted = acceptedUntilRejected(state, new double[] {1.55, -0.15}, shapes.get(i));
                assertTrue("ping " + ping + " shape " + i + ": " + accepted + " > " + limit, accepted <= limit);
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

        final ElytraResidual.State steeper = new ElytraResidual.State();
        assertTrue(ElytraResidual.begin(steeper, 1.5, -0.15, 160, CAP));
        assertTrue(allow(steeper, 1.5, -0.44, false, false));
        assertTrue(allow(steeper, 1.5, -0.73, false, false));
        assertFalse("more than 0.30 below the last move", allow(steeper, 1.5, -1.05, false, false));

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
                for (int extra = 0; swap.length - 1 + extra <= lateEnd; extra++) {
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
                        for (int extra = 0; extra <= lateEnd; extra++) {
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
        // Level glide, then a climb that keeps its speed: allowed only up to the start + 0.15.
        final ElytraResidual.State held = new ElytraResidual.State();
        assertTrue(ElytraResidual.begin(held, 1.5, -0.15, 160, CAP));
        assertTrue(allow(held, 1.5, -0.10, false, false));
        assertTrue(allow(held, 1.5, -0.05, false, false));
        assertTrue(allow(held, 1.5, 0.0, false, false));
        assertFalse("climb with the speed held", allow(held, 1.5, 0.05, false, false));
        assertFalse(held.open);

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
            assertTrue(allow(state, cheat[0], ys[0], false, false));
            assertFalse("climb above the start ceiling with h " + cheat[1], allow(state, cheat[1], ys[1], false, false));
            assertFalse(state.open);
        }
    }

    @Test
    public void speedGainedWithoutLiftCannotPayForALaterClimb() {
        // Climbing glide, then a speed gain no descent explains, then that speed is "spent" on a climb.
        final ElytraResidual.State pumped = new ElytraResidual.State();
        assertTrue(ElytraResidual.begin(pumped, 1.65, 0.0, 2000, CAP));
        double[] move = {1.65, 0.0};
        for (int i = 0; i < 2; i++) {
            move = glide(move[0], move[1], -60.0);
            assertTrue(allow(pumped, move[0], move[1], false, false));
        }
        assertTrue(allow(pumped, move[0] + 0.14, move[1] - 0.02, false, false));
        assertTrue(pumped.hDebt > 0.13);
        final double paidCeiling = ElytraResidual.maxGlideY(move[1] - 0.02, move[0] + 0.14, 0.13);
        assertTrue(paidCeiling > move[1] + 0.05);
        assertFalse("the debt is repaid first", allow(pumped, move[0], move[1] + 0.05, false, false));
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
