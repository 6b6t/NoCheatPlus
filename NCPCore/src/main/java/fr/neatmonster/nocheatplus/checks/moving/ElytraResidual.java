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
 * above the CreativeFly elytra limit. Vertical speed may rise only as far as
 * vanilla glide physics allows ({@link #maxGlideY}): the lift a descent gives,
 * plus a pitch-up climb that the horizontal speed lost in the same move pays
 * for. A move may also stay at or below the starting vertical speed plus
 * {@link #Y_SLACK} (a rocket holds the glide there).</li>
 * <li><b>Coast</b>: horizontal speed must fall with air drag
 * ({@link #COAST_DRAG}) and the player must fall with gravity, never above the
 * highest vertical speed the window accepted. The window closes once the move
 * is a normal air move ({@link #NORMAL_AIR_H}).</li>
 * </ul>
 * In both phases the player may not drop more than {@link #Y_DESCEND_SLACK} below
 * the last accepted vertical speed. Any move outside the limits, ground, liquid,
 * climbable or an elytra back in the chest slot ends the window. A new window can
 * open at most once per {@link #REARM_MOVES} moves. State lives only in
 * {@link MovingData}.
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

    /** Coast phase: margin above the vanilla fall (at or below the start ceiling or level). */
    public static final double COAST_Y_MARGIN = 0.05;

    /** At or below this, the coast move is a normal air move and the window closes. */
    public static final double NORMAL_AIR_H = 0.35;

    /**
     * Coast phase, maximum moves. Drag plus margin settles near 0.44, so the
     * threshold above alone would not end a window that hovers above it.
     * A vanilla coast with forward input reaches 0.35 within about 39 moves from 2.7.
     */
    public static final int MAX_COAST_MOVES = 40;

    /** How far above the vertical speed at the start a move may be, whatever the physics. */
    public static final double Y_SLACK = 0.15;

    // Vanilla glide, LivingEntity#updateFallFlyingMovement, per tick (c = cos^2(pitch), s = sin(-pitch)):
    //   vy += 0.08 * (-1 + 0.75 c)                      gravity, least at pitch 0
    //   lift = 0.1 c * -vy, if vy < 0: vy += lift, h += lift   (descent turned into speed)
    //   climb = 0.04 * h0 * s, if pitch < 0: h -= climb, vy += 3.2 climb
    //   h is steered 10 % back to h0 along the look, then h *= 0.99, vy *= 0.98.

    /** Glide gravity at pitch 0: 0.08 * (1 - 0.75). Any other pitch pulls down harder. */
    public static final double GLIDE_GRAVITY = 0.02;

    /** Glide gravity added per sin^2(-pitch) when pitching up: 0.08 * 0.75. */
    public static final double GLIDE_GRAVITY_PITCH = 0.06;

    /** Lift: share of the downward speed turned into horizontal speed, per tick. */
    public static final double GLIDE_LIFT = 0.1;

    /** Pitch-up climb: share of the horizontal speed spent, per tick at sin(-pitch) = 1. */
    public static final double GLIDE_CLIMB = 0.04;

    /** Pitch-up climb: vertical speed gained per unit of horizontal speed spent. */
    public static final double GLIDE_CLIMB_LIFT = 3.2;

    /** Steering keeps this share of a change in horizontal speed (it pulls 10 % back). */
    public static final double GLIDE_STEER_KEEP = 0.9;

    /** Glide drag on horizontal speed. */
    public static final double GLIDE_H_DRAG = 0.99;

    /** Lag phase: margin above the vanilla glide bound (rounding). Real glides stay within 0.0003. */
    public static final double GLIDE_Y_MARGIN = 0.005;

    /** CreativeFly: default walk speed, the base of the walk speed ratio (Magic.DEFAULT_WALKSPEED). */
    public static final double DEFAULT_WALK_SPEED = 0.2;

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
        /** Coast phase vertical upper bound: yCap or the highest accepted lag move. */
        public double yHigh;
        /**
         * Horizontal speed gained in the lag phase beyond what vanilla lift explains.
         * Must be spent again before any climb is paid for.
         */
        public double hDebt;
        /** The one move that repeats the last vertical distance (first air tick) was used. */
        public boolean repeated;
    }

    private ElytraResidual() {}

    /** Lag phase length for a ping in milliseconds: 3 + ceil(ping / 50), at most 10. */
    public static int lagMoves(final int pingMs) {
        final long ping = Math.max(0L, (long) pingMs);
        final long moves = MIN_LAG_MOVES + (ping + PING_MS_PER_MOVE - 1L) / PING_MS_PER_MOVE;
        return (int) Math.min(MAX_LAG_MOVES, moves);
    }

    /**
     * Lag phase speed cap: the CreativeFly elytra limit with the same modifiers
     * CreativeFly applies to a gliding player (speed effect, movement speed
     * attribute, walk speed ratio), but never more than the configured model
     * limit. Returns 0 (no window) for unusable input.
     *
     * @param modelCap the model's limit, horizontal mod speed / 100 * 0.6
     * @param applyModifiers the model applies modifiers (live elytra model: yes)
     * @param speedAmplifier speed effect amplifier, infinite for none
     * @param attributeMultiplier movement speed attribute multiplier, Double.MAX_VALUE if unknown
     * @param walkSpeed the player's walk speed (default 0.2)
     */
    public static double elytraCap(final double modelCap, final boolean applyModifiers,
                                   final double speedAmplifier, final double attributeMultiplier,
                                   final double walkSpeed) {
        if (!Double.isFinite(modelCap) || modelCap <= 0.0) {
            return 0.0;
        }
        if (!applyModifiers) {
            return modelCap;
        }
        // Same as CreativeFly.hDist for a player that is not flying.
        double factor = Double.isInfinite(speedAmplifier) ? 1.0 : 1.0 + 0.2 * (speedAmplifier + 1.0);
        if (attributeMultiplier != Double.MAX_VALUE) {
            factor *= attributeMultiplier;
        }
        factor *= walkSpeed / DEFAULT_WALK_SPEED;
        if (!Double.isFinite(factor) || factor <= 0.0) {
            return 0.0;
        }
        // The smaller of the two: a slower player never gets the full model limit.
        return Math.min(modelCap, modelCap * factor);
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
        state.yHigh = state.yCap;
        state.hDebt = 0.0;
        state.repeated = false;
        return true;
    }

    /**
     * Count one move and judge it against the open window. Returns true when the
     * move is inside the window. Ground, reset conditions (liquid, climbable and
     * similar), an elytra in the chest slot again and any move outside the limits
     * end the window.
     */
    public static boolean allow(final State state, final double hDistance, final double yDistance,
                                final boolean onGround, final boolean resetCond, final boolean wearingElytra) {
        countMove(state);
        if (!state.open) {
            return false;
        }
        if (wearingElytra || onGround || resetCond || !Double.isFinite(hDistance) || !Double.isFinite(yDistance)
                || hDistance < 0.0) {
            end(state);
            return false;
        }
        final int index = state.moves + 1;
        final boolean lag = index <= state.lagMoves;
        // Vertical, both phases: no sudden dive.
        if (yDistance < Math.max(DIVE_MIN, state.lastY - Y_DESCEND_SLACK)) {
            end(state);
            return false;
        }
        double hDebt = state.hDebt;
        boolean repeat = false;
        if (lag) {
            // The client may still be gliding. Never more than wearing the elytra allows.
            if (hDistance > Math.min(state.elytraCap, state.lastH + LAG_H_GROWTH)) {
                end(state);
                return false;
            }
            // Horizontal speed this move spent beyond drag (positive) or gained beyond lift (negative).
            final double spent = state.lastH + GLIDE_STEER_KEEP * maxLift(state.lastY) - hDistance / GLIDE_H_DRAG;
            final double paid;
            if (spent < 0.0) {
                hDebt -= spent;
                paid = 0.0;
            }
            else {
                final double repaid = Math.min(hDebt, spent);
                hDebt -= repaid;
                paid = spent - repaid;
            }
            // Glide physics, or no higher than the start (rocket, level glide).
            if (yDistance > Math.max(state.yCap, maxGlideY(state.lastY, state.lastH, paid))) {
                // The first tick after the glide stops moves by the last glide velocity
                // (vanilla applies air drag and gravity after the move). Once per window.
                if (state.repeated || yDistance > state.lastY + GLIDE_Y_MARGIN
                        || hDistance > state.lastH + COAST_H_MARGIN) {
                    end(state);
                    return false;
                }
                repeat = true;
            }
        }
        else {
            // Vanilla coast: air drag horizontally, gravity vertically. Rising above the
            // old ceiling (only after a paid climb) must follow vanilla gravity exactly.
            final double yMargin = state.lastY > Math.max(state.yCap, 0.0) ? GLIDE_Y_MARGIN : COAST_Y_MARGIN;
            if (yDistance > state.yHigh
                    || index - state.lagMoves > MAX_COAST_MOVES
                    || hDistance > state.lastH * COAST_DRAG + COAST_H_MARGIN
                    || yDistance > (state.lastY - GRAVITY) * VERTICAL_DRAG + yMargin) {
                end(state);
                return false;
            }
        }
        state.moves = index;
        state.lastH = hDistance;
        state.lastY = yDistance;
        state.hDebt = hDebt;
        state.repeated |= repeat;
        if (lag) {
            state.yHigh = Math.max(state.yHigh, yDistance);
        }
        else if (hDistance <= NORMAL_AIR_H) {
            // Normal air move from here on; SurvivalFly judges the rest.
            end(state);
        }
        return true;
    }

    /** Most horizontal speed one glide tick can gain from lift, at any pitch: 0.1 * (0.08 - vy). */
    static double maxLift(final double lastY) {
        return GLIDE_LIFT * Math.max(0.0, GRAVITY - lastY);
    }

    /**
     * Highest vertical distance one vanilla glide tick can reach from the last
     * move, at any pitch, plus {@link #GLIDE_Y_MARGIN}.
     * <p>
     * Without paid speed (pitch 0 or down) the best is pitch 0: gravity -0.02 and
     * lift 0.1 * (0.02 - vy). Pitching up by s = sin(-pitch) adds 3.2 * 0.04 * h * s,
     * costs extra gravity 0.06 * s^2 and lift, and spends 0.9 * 0.04 * h * s of
     * horizontal speed. So s is limited to what {@code paid} covers.
     *
     * @param lastY last accepted vertical distance
     * @param lastH last accepted horizontal distance
     * @param paid horizontal speed this move lost beyond glide drag and lift, after any debt
     */
    public static double maxGlideY(final double lastY, final double lastH, final double paid) {
        final double sMax = paid > 0.0 && lastH > 0.0
                ? Math.min(1.0, paid / (GLIDE_STEER_KEEP * GLIDE_CLIMB * lastH)) : 0.0;
        final double change;
        if (sMax > 0.0) {
            // Upper bound for any pitch-up s: gravity -0.02 - 0.06 s^2, lift <= (1 - s^2) * maxLift.
            final double lift = maxLift(lastY);
            final double climb = GLIDE_CLIMB * GLIDE_CLIMB_LIFT * lastH;
            final double curve = GLIDE_GRAVITY_PITCH + lift;
            final double s = Math.min(sMax, climb / (2.0 * curve));
            change = -GLIDE_GRAVITY + lift + climb * s - curve * s * s;
        }
        else {
            change = -GLIDE_GRAVITY + GLIDE_LIFT * Math.max(0.0, GLIDE_GRAVITY - lastY);
        }
        return (lastY + change) * VERTICAL_DRAG + GLIDE_Y_MARGIN;
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
