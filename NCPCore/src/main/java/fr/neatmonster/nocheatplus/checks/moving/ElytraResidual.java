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
package fr.neatmonster.nocheatplus.checks.moving;

/**
 * Short in-memory window after an elytra is unequipped mid-glide.
 * <p>
 * The client keeps gliding until the server's equipment update reaches it, then
 * coasts with vanilla air drag. SurvivalFly alone sets that back. The window has
 * two phases, both counted in moves (no clock, no global tick):
 * <ul>
 * <li><b>Lag</b>: {@link #lagMoves(int)} moves while the client may still be
 * gliding. Horizontal speed may grow by {@link #LAG_H_GROWTH} per move, never
 * above the CreativeFly elytra limit.</li>
 * <li><b>Coast</b>: horizontal speed must fall with air drag
 * ({@link #COAST_DRAG}) and the player must fall with gravity. The window closes
 * once the move is a normal air move ({@link #NORMAL_AIR_H}).</li>
 * </ul>
 * In both phases the player may not climb above the starting vertical speed plus
 * {@link #Y_SLACK} or drop more than {@link #Y_DESCEND_SLACK} below the last
 * accepted vertical speed. Any move outside the limits, ground, liquid or
 * climbable ends the window. A new window can open at most once per
 * {@link #REARM_MOVES} moves. State lives only in {@link MovingData}.
 */
public final class ElytraResidual {

    /** Lag phase, minimum moves. */
    public static final int MIN_LAG_MOVES = 3;

    /** Lag phase, maximum moves, whatever the ping. */
    public static final int MAX_LAG_MOVES = 10;

    /** One extra lag move per this much ping. */
    public static final int PING_MS_PER_MOVE = 50;

    /** Lag phase: growth over the last accepted horizontal distance, per move. */
    public static final double LAG_H_GROWTH = 0.15;

    /** Coast phase: vanilla horizontal air drag. */
    public static final double COAST_DRAG = 0.91;

    /** Coast phase: input acceleration and rounding above the drag. */
    public static final double COAST_H_MARGIN = 0.04;

    /** Coast phase: vanilla gravity per tick. */
    public static final double GRAVITY = 0.08;

    /** Coast phase: vanilla vertical drag. */
    public static final double VERTICAL_DRAG = 0.98;

    /** Coast phase: margin above the vanilla fall. */
    public static final double COAST_Y_MARGIN = 0.05;

    /** At or below this, the coast move is a normal air move and the window closes. */
    public static final double NORMAL_AIR_H = 0.35;

    /**
     * Coast phase, maximum moves. Drag plus margin settles near 0.44, so the
     * threshold above alone would not end a window that hovers above it.
     * A vanilla coast with forward input reaches 0.35 within about 39 moves from 2.7.
     */
    public static final int MAX_COAST_MOVES = 40;

    /** How far above the vertical speed at the start a move may be. */
    public static final double Y_SLACK = 0.15;

    /** How far below the last accepted vertical speed one move may go. */
    public static final double Y_DESCEND_SLACK = 0.30;

    /** Vanilla unboosted dive is about -3.92 blocks per tick. Deeper is never residual. */
    public static final double DIVE_MIN = -3.95;

    /** One new window cannot open until this many moves after the previous one opened. */
    public static final int REARM_MOVES = 40;

    public static final class State {
        /** A window is open. */
        public boolean open;
        /** Lag phase length of the open window. */
        public int lagMoves;
        /** Moves accepted in the open window. */
        public int moves;
        /** Moves counted since the last window opened. Saturates at {@link #REARM_MOVES}. */
        public int sinceBegin = REARM_MOVES;
        /** CreativeFly elytra limit at the start. */
        public double elytraCap;
        /** Last accepted horizontal distance. */
        public double lastH;
        /** Last accepted vertical distance. */
        public double lastY;
        /** Vertical upper bound, fixed from the glide that opened the window. */
        public double yCap;
    }

    private ElytraResidual() {}

    /** Lag phase length for a ping in milliseconds: 3 + ceil(ping / 50), at most 10. */
    public static int lagMoves(final int pingMs) {
        final long ping = Math.max(0L, (long) pingMs);
        final long moves = MIN_LAG_MOVES + (ping + PING_MS_PER_MOVE - 1L) / PING_MS_PER_MOVE;
        return (int) Math.min(MAX_LAG_MOVES, moves);
    }

    /**
     * Open a window from the last accepted glide move. Returns false when a window
     * is already open, fewer than {@link #REARM_MOVES} moves passed since the last
     * one opened, or the input is unusable.
     */
    public static boolean begin(final State state, final double lastH, final double lastY,
                                final int pingMs, final double elytraCap) {
        if (state.open || state.sinceBegin < REARM_MOVES) {
            return false;
        }
        if (!Double.isFinite(lastH) || !Double.isFinite(lastY) || !Double.isFinite(elytraCap)
                || lastH < 0.0 || elytraCap <= 0.0) {
            return false;
        }
        state.open = true;
        state.sinceBegin = 0;
        state.lagMoves = lagMoves(pingMs);
        state.moves = 0;
        state.elytraCap = elytraCap;
        state.lastH = Math.min(lastH, elytraCap);
        state.lastY = lastY;
        state.yCap = lastY + Y_SLACK;
        return true;
    }

    /**
     * Count one move and judge it against the open window. Returns true when the
     * move is inside the window. Ground, reset conditions (liquid, climbable and
     * similar) and any move outside the limits end the window.
     */
    public static boolean allow(final State state, final double hDistance, final double yDistance,
                                final boolean onGround, final boolean resetCond) {
        countMove(state);
        if (!state.open) {
            return false;
        }
        if (onGround || resetCond || !Double.isFinite(hDistance) || !Double.isFinite(yDistance)
                || hDistance < 0.0) {
            end(state);
            return false;
        }
        final int index = state.moves + 1;
        final boolean lag = index <= state.lagMoves;
        // Vertical, both phases: no climb above the start, no sudden dive.
        if (yDistance > state.yCap || yDistance < Math.max(DIVE_MIN, state.lastY - Y_DESCEND_SLACK)) {
            end(state);
            return false;
        }
        if (lag) {
            // The client may still be gliding. Never more than wearing the elytra allows.
            if (hDistance > Math.min(state.elytraCap, state.lastH + LAG_H_GROWTH)) {
                end(state);
                return false;
            }
        }
        else {
            // Vanilla coast: air drag horizontally, gravity vertically.
            if (index - state.lagMoves > MAX_COAST_MOVES
                    || hDistance > state.lastH * COAST_DRAG + COAST_H_MARGIN
                    || yDistance > (state.lastY - GRAVITY) * VERTICAL_DRAG + COAST_Y_MARGIN) {
                end(state);
                return false;
            }
        }
        state.moves = index;
        state.lastH = hDistance;
        state.lastY = yDistance;
        if (!lag && hDistance <= NORMAL_AIR_H) {
            // Normal air move from here on; SurvivalFly judges the rest.
            end(state);
        }
        return true;
    }

    /** Close the window. Does not re-arm early. */
    public static void end(final State state) {
        state.open = false;
    }

    /** A CreativeFly move (re-equip, levitation and similar): counts the move and ends the window. */
    public static void creativeFlyMove(final State state) {
        countMove(state);
        end(state);
    }

    private static void countMove(final State state) {
        if (state.sinceBegin < REARM_MOVES) {
            state.sinceBegin++;
        }
    }
}
