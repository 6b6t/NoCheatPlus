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
 * Short in-memory window after an elytra is unequipped or depleted mid-glide.
 * <p>
 * The client keeps gliding until the server's equipment update reaches it, then
 * coasts with vanilla air drag. SurvivalFly alone sets that back. The window has
 * two phases, both counted in moves (no clock, no global tick):
 * <ul>
 * <li><b>Lag</b>: {@link #lagMoves(int)} moves while the client may still be
 * gliding. A move must fit one vanilla glide tick: at the pitch it was made
 * with ({@link #vanillaGlide}) and, for any pitch, the envelope of
 * {@link #maxGlideY} (lift a descent gives, plus a pitch-up climb that the
 * horizontal speed lost in the same move pays for) and {@link #minGlideY}
 * (horizontal speed gained from lift comes out of the downward speed).
 * Horizontal speed grows by at most {@link #LAG_H_GROWTH} per move. While a
 * firework boost may still last, a move may instead fit the vanilla boost plus
 * glide tick at its pitch ({@link #vanillaRocket}). For
 * {@link #LATE_GLIDE_MOVES} more moves, a move the coast rejects is still
 * accepted if it fits a glide or boost tick (the server can be a few ticks
 * behind the client). Lag phase, late glide and the first air tick together
 * never pass move {@link #MAX_LAG_MOVES}.</li>
 * <li><b>Coast</b>: horizontal speed must fall with air drag
 * ({@link #COAST_DRAG}) and the player must fall with gravity, never above the
 * highest vertical speed the window accepted. The window closes once the move
 * is a normal air move ({@link #NORMAL_AIR_H}).</li>
 * </ul>
 * In both phases the player may not drop more than {@link #Y_DESCEND_SLACK} below
 * the last accepted vertical speed, and no move is faster than the elytra cap.
 * Any move outside the limits, ground, liquid, climbable or a usable elytra back in
 * the chest slot ends the window. A new window can
 * open at most once per {@link #REARM_MOVES} moves. State lives only in
 * {@link MovingData}.
 */
public final class ElytraResidual {

    /** Lag phase, minimum moves. */
    public static final int MIN_LAG_MOVES = 3;

    /** Lag phase, maximum moves, whatever the ping. */
    public static final int MAX_LAG_MOVES = 10;

    /**
     * Late glide: moves after the lag phase in which a strict vanilla glide tick is
     * still accepted (the server can be a few ticks behind the client). Never past
     * {@link #MAX_LAG_MOVES}.
     */
    public static final int LATE_GLIDE_MOVES = 3;

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

    /** The start ceiling is start + this, and never above 0: it absorbs non-glide wiggles while falling. */
    public static final double Y_SLACK = 0.15;

    /** Firework boost, FireworkRocketEntity#tick, per tick and component: v = v * 0.5 + look * 0.85. */
    public static final double ROCKET_KEEP = 0.5;

    /** Firework boost: the push along the look (0.1 + 1.5 * 0.5). */
    public static final double ROCKET_PUSH = 0.85;

    /**
     * Firework boost: where any number of rockets at once lead, look * 0.85 / (1 - 0.5).
     * Holding the use key fires several; their boosts apply one after the other each tick.
     */
    public static final double ROCKET_LIMIT = ROCKET_PUSH / (1.0 - ROCKET_KEEP);

    /** Rockets at once that are checked one by one; more count as the limit {@link #ROCKET_LIMIT}. */
    public static final int MAX_ROCKETS = 3;

    /** A repeated move (first air tick) may not be higher than the last move by more than this (rounding). */
    public static final double REPEAT_RISE = 1.0E-6;

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

    /** Margin above the vanilla glide bound for vertical distance (rounding). Real glides stay within 0.0003. */
    public static final double GLIDE_Y_MARGIN = 0.005;

    /** Margin above the vanilla glide bound for horizontal distance. Real glides stay 0.005 below the bound. */
    public static final double GLIDE_H_MARGIN = 0.005;

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
        /** The one move that repeats the last move (first air tick) was used. */
        public boolean repeated;
        /** Last move accepted as a glide (lag phase or late glide); the coast is counted from here. */
        public int glideEnd;
        /** Moves (lag phase only) in which a firework boost may still push the glide. */
        public int rocketMoves;
        /** The last accepted move fit a rocket-boosted glide tick (or the window opened boosted). */
        public boolean rocketLast;
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

    /** {@link #begin(State, double, double, int, double, boolean)} without a rocket boost. */
    public static boolean begin(final State state, final double lastH, final double lastY,
                                final int pingMs, final double elytraCap) {
        return begin(state, lastH, lastY, pingMs, elytraCap, false);
    }

    /**
     * Open a window from the last accepted glide move. Returns false when a window
     * is already open, fewer than {@link #REARM_MOVES} moves passed since the last
     * one opened, or the input is unusable.
     *
     * @param rocket a firework rocket boost is still active (tests: the whole lag phase)
     */
    public static boolean begin(final State state, final double lastH, final double lastY,
                                final int pingMs, final double elytraCap, final boolean rocket) {
        return begin(state, lastH, lastY, pingMs, elytraCap, rocket ? MAX_LAG_MOVES : 0);
    }

    /**
     * Open a window from the last accepted glide move.
     *
     * @param rocketTicks server ticks the last firework boost may still last (0: none).
     *            Rocket-boosted glide ticks are accepted only in the lag phase and
     *            only for this many moves.
     */
    public static boolean begin(final State state, final double lastH, final double lastY,
                                final int pingMs, final double elytraCap, final int rocketTicks) {
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
        state.glideEnd = 0;
        state.rocketMoves = Math.max(0, rocketTicks);
        state.rocketLast = state.rocketMoves > 0;
        return true;
    }

    /**
     * Count one move and judge it against the open window. Returns true when the
     * move is inside the window. Ground, reset conditions (liquid, climbable and
     * similar), a usable elytra in the chest slot again and any move outside the limits
     * end the window.
     */
    public static boolean allow(final State state, final double hDistance, final double yDistance,
                                final boolean onGround, final boolean resetCond, final boolean wearingUsableElytra) {
        return allow(state, hDistance, yDistance, Double.NaN, onGround, resetCond, wearingUsableElytra);
    }

    /**
     * As above, with the pitch the move was made with (degrees, the move's
     * to-rotation, which is the look vanilla used for that tick). A finite pitch
     * adds the exact vanilla tick at that pitch as a bound and allows
     * rocket-boosted ticks; without one only the pitch-free envelope applies.
     */
    public static boolean allow(final State state, final double hDistance, final double yDistance,
                                final double pitch, final boolean onGround, final boolean resetCond,
                                final boolean wearingUsableElytra) {
        countMove(state);
        if (!state.open) {
            return false;
        }
        if (wearingUsableElytra || onGround || resetCond || !Double.isFinite(hDistance) || !Double.isFinite(yDistance)
                || hDistance < 0.0) {
            end(state);
            return false;
        }
        // Every move: never faster than wearing the elytra allows. CreativeFly's own
        // limit is max(cap, last h x 0.98 friction); lastH never exceeds the cap
        // (begin clamps it, every accepted move stays under it), so that is the cap.
        if (hDistance > state.elytraCap) {
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
        // Lag phase, late glide and the first air tick never pass move MAX_LAG_MOVES.
        final int lateEnd = Math.min(MAX_LAG_MOVES, state.lagMoves + LATE_GLIDE_MOVES);
        int kind = REJECT;
        if (lag) {
            // The client may still be gliding (and a firework may still push it).
            kind = glideLike(state, hDistance, yDistance, pitch, index, false);
            if (kind == REJECT) {
                end(state);
                return false;
            }
        }
        else {
            // Vanilla coast: air drag horizontally, gravity vertically. Rising must
            // follow vanilla gravity exactly; the 0.05 margin is for falling only.
            final double yMargin = state.lastY > 0.0 ? GLIDE_Y_MARGIN : COAST_Y_MARGIN;
            final boolean coast = yDistance <= state.yHigh
                    && index - Math.max(state.lagMoves, state.glideEnd) <= MAX_COAST_MOVES
                    && hDistance <= state.lastH * COAST_DRAG + COAST_H_MARGIN
                    && yDistance <= (state.lastY - GRAVITY) * VERTICAL_DRAG + yMargin;
            if (!coast) {
                // Late glide: the server can be a few moves behind the client, so a client can
                // still glide after the lag phase (with a boost while it may still last). A
                // plain glide move is no faster than drag and lift allow and grows by at most
                // +0.15 (as in the lag phase); after the late glide only the first air tick.
                if (index <= lateEnd) {
                    kind = glideLike(state, hDistance, yDistance, pitch, index, true);
                }
                else if (index <= Math.min(lateEnd + 1, MAX_LAG_MOVES)) {
                    if (isRepeat(state, hDistance, yDistance, pitch, false)) {
                        kind = REPEAT;
                    }
                    else if (Double.isFinite(pitch) && index <= state.rocketMoves + 1
                            && isRepeat(state, hDistance, yDistance, pitch, true)) {
                        kind = ROCKET_REPEAT;
                    }
                }
                if (kind == REJECT) {
                    end(state);
                    return false;
                }
            }
        }
        final boolean glide = kind != REJECT;
        // Rocket ticks add speed the glide did not pay for; they neither add nor repay debt.
        final double hDebt = kind == GLIDE || kind == REPEAT ? debtAfter(state, hDistance) : state.hDebt;
        state.moves = index;
        state.lastH = hDistance;
        state.lastY = yDistance;
        state.hDebt = hDebt;
        state.repeated |= kind == REPEAT || kind == ROCKET_REPEAT;
        state.rocketLast = kind == ROCKET;
        if (glide) {
            state.yHigh = Math.max(state.yHigh, yDistance);
            state.glideEnd = index;
        }
        else if (hDistance <= NORMAL_AIR_H) {
            // Normal air move from here on; SurvivalFly judges the rest.
            end(state);
        }
        return true;
    }

    /**
     * Horizontal speed this move spent beyond glide drag (positive), or gained
     * beyond the most lift could give (negative, becomes debt).
     */
    static double spent(final State state, final double hDistance) {
        return state.lastH + GLIDE_STEER_KEEP * maxLift(state.lastY) - hDistance / GLIDE_H_DRAG;
    }

    /** Debt this move repays. */
    private static double repaid(final State state, final double hDistance) {
        return Math.min(state.hDebt, Math.max(0.0, spent(state, hDistance)));
    }

    /** Debt after this move: unexplained speed gains add to it, spent speed repays it first. */
    private static double debtAfter(final State state, final double hDistance) {
        final double spent = spent(state, hDistance);
        return spent < 0.0 ? state.hDebt - spent : state.hDebt - repaid(state, hDistance);
    }

    /** Speed this move spent that may pay for a climb: what is left after repaying the debt. */
    static double pay(final State state, final double hDistance) {
        return Math.max(0.0, spent(state, hDistance)) - repaid(state, hDistance);
    }

    private static final int REJECT = 0;
    private static final int GLIDE = 1;
    private static final int REPEAT = 2;
    private static final int ROCKET = 3;
    private static final int ROCKET_REPEAT = 4;

    /**
     * Judge a move the client may still make while gliding. Accepted as
     * <ul>
     * <li>a glide tick: no faster than +{@link #LAG_H_GROWTH}, inside the
     * pitch-free envelope ({@link #maxGlideY}, {@link #minGlideY}) and, with a
     * pitch, no higher and no faster than the vanilla tick at that pitch
     * ({@link #vanillaGlide}). A fall at or below min(start + {@link #Y_SLACK}, 0)
     * also passes the upper bounds (a worker handoff pause, a client stopping);</li>
     * <li>an air tick (the client stopped gliding);</li>
     * <li>a rocket tick, while the boost may still last (lag phase or late glide):
     * no higher and no faster than the vanilla boost plus glide tick at that pitch;</li>
     * <li>once per window, the first air tick: the last move again (after a rocket
     * tick: the last move with the boost applied), never higher.</li>
     * </ul>
     */
    private static int glideLike(final State state, final double hDistance, final double yDistance,
                                 final double pitch, final int index, final boolean late) {
        final boolean hasPitch = Double.isFinite(pitch);
        if (hasPitch && index <= state.rocketMoves) {
            // One, two, three or (in the limit) many rockets at once: the move must fit one of them.
            for (int rockets = 1; rockets <= MAX_ROCKETS + 1; rockets++) {
                final double[] boosted = vanillaRocket(state.lastH, state.lastY, pitch, rockets > MAX_ROCKETS ? 0 : rockets);
                final double[] tick = vanillaGlide(boosted[0], boosted[1], pitch);
                if (hDistance <= tick[0] + GLIDE_H_MARGIN && yDistance <= tick[1] + GLIDE_Y_MARGIN) {
                    return ROCKET;
                }
            }
        }
        if (hDistance <= state.lastH + LAG_H_GROWTH && yDistance >= minGlideY(state.lastY, state.lastH, hDistance)
                && (!late || hDistance <= (state.lastH + GLIDE_STEER_KEEP * maxLift(state.lastY)) * GLIDE_H_DRAG + GLIDE_H_MARGIN)) {
            double upper = maxGlideY(state.lastY, state.lastH, pay(state, hDistance));
            boolean hOk = true;
            if (hasPitch) {
                final double[] tick = vanillaGlide(state.lastH, state.lastY, pitch);
                upper = Math.min(upper, tick[1] + GLIDE_Y_MARGIN);
                hOk = hDistance <= tick[0] + GLIDE_H_MARGIN;
            }
            if (hOk && yDistance <= upper || yDistance <= Math.min(state.yCap, 0.0)) {
                return GLIDE;
            }
            // Air tick: the client stopped gliding a tick ago.
            if (hDistance <= state.lastH * COAST_DRAG + COAST_H_MARGIN
                    && yDistance <= (state.lastY - GRAVITY) * VERTICAL_DRAG + GLIDE_Y_MARGIN) {
                return GLIDE;
            }
        }
        if (isRepeat(state, hDistance, yDistance, pitch, false)) {
            return REPEAT;
        }
        if (hasPitch && index <= state.rocketMoves + 1 && isRepeat(state, hDistance, yDistance, pitch, true)) {
            return ROCKET_REPEAT;
        }
        return REJECT;
    }

    /**
     * One vanilla glide tick (LivingEntity#updateFallFlyingMovement) at this
     * pitch, flying along the look: {h, vy}. Steering only ever lowers h when the
     * look is not along the motion, so h is an upper bound for any yaw.
     */
    public static double[] vanillaGlide(final double h0, final double vy0, final double pitchDegrees) {
        final double pitch = Math.toRadians(pitchDegrees);
        final double cos2 = Math.cos(pitch) * Math.cos(pitch);
        double vy = vy0 + GRAVITY * (-1.0 + cos2 * 0.75);
        double h = h0;
        if (vy < 0.0) {
            final double lift = -vy * GLIDE_LIFT * cos2;
            vy += lift;
            h += lift;
        }
        if (pitch < 0.0) {
            final double climb = h0 * -Math.sin(pitch) * GLIDE_CLIMB;
            h -= climb;
            vy += climb * GLIDE_CLIMB_LIFT;
        }
        h += (h0 - h) * (1.0 - GLIDE_STEER_KEEP);
        return new double[] {h * GLIDE_H_DRAG, vy * VERTICAL_DRAG};
    }

    /**
     * Firework boosts (FireworkRocketEntity#tick) on a glide: {h, vy} after this
     * many rockets at once, each v = v * 0.5 + look * 0.85, so after n of them
     * v * 0.5^n + look * 1.7 * (1 - 0.5^n). rockets 0: the limit of many, look * 1.7.
     * h is an upper bound for any yaw.
     */
    public static double[] vanillaRocket(final double h0, final double vy0, final double pitchDegrees, final int rockets) {
        final double pitch = Math.toRadians(pitchDegrees);
        final double keep = rockets <= 0 ? 0.0 : Math.pow(ROCKET_KEEP, rockets);
        return new double[] {h0 * keep + ROCKET_LIMIT * (1.0 - keep) * Math.cos(pitch),
                vy0 * keep - ROCKET_LIMIT * (1.0 - keep) * Math.sin(pitch)};
    }

    /**
     * Lowest vertical distance a glide tick can have when it turned descent into
     * this much horizontal speed. Vanilla adds the same lift to h (90 % kept after
     * steering) and to vy, and gravity is at most 0.08; a pitch-up climb only
     * raises vy. So: (lastY - 0.08 + lift) * 0.98, with lift at least the
     * horizontal gain over drag / 0.9, minus {@link #GLIDE_Y_MARGIN}. The first
     * {@link #COAST_H_MARGIN} of gain is air input (an air tick after the glide
     * stops), not lift.
     */
    public static double minGlideY(final double lastY, final double lastH, final double hDistance) {
        final double lift = Math.max(0.0, hDistance / GLIDE_H_DRAG - lastH - COAST_H_MARGIN) / GLIDE_STEER_KEEP;
        return (lastY - GRAVITY + lift) * VERTICAL_DRAG - GLIDE_Y_MARGIN;
    }

    /**
     * The first tick after the glide stops moves by the last glide velocity plus
     * input (vanilla applies air drag and gravity after the move). After a rocket
     * tick that velocity has the boost applied. Once per window, and never higher
     * than that velocity (while falling, within {@link #GLIDE_Y_MARGIN}: rounding,
     * and a worker handoff pause; never above 0).
     */
    private static boolean isRepeat(final State state, final double hDistance, final double yDistance,
                                    final double pitch, final boolean rocket) {
        if (state.repeated) {
            return false;
        }
        if (!rocket) {
            return repeats(state.lastH, state.lastY, hDistance, yDistance);
        }
        if (!state.rocketLast) {
            return false;
        }
        for (int rockets = 1; rockets <= MAX_ROCKETS + 1; rockets++) {
            final double[] boosted = vanillaRocket(state.lastH, state.lastY, pitch, rockets > MAX_ROCKETS ? 0 : rockets);
            if (repeats(boosted[0], boosted[1], hDistance, yDistance)) {
                return true;
            }
        }
        return false;
    }

    /** The move is this velocity again (plus air input): never higher; falling, within rounding (and a worker handoff pause). */
    private static boolean repeats(final double h, final double y, final double hDistance, final double yDistance) {
        final boolean notHigher = yDistance <= y + REPEAT_RISE || yDistance <= 0.0 && yDistance <= y + GLIDE_Y_MARGIN;
        return notHigher && yDistance >= y - GLIDE_Y_MARGIN && hDistance <= h + COAST_H_MARGIN;
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
