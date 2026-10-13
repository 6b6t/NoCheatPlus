/*
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package fr.neatmonster.nocheatplus.checks.moving.velocity;

import java.util.Iterator;
import java.util.LinkedList;

/**
 * Knockback the server applies without a PlayerVelocityEvent: explosion knockback
 * that does not hurt (2nd/3rd crystal inside the hurt cooldown, shield blocks, the
 * attacker's own Wind Burst) and the vertical reset of a mace smash.
 * <p>
 * Unlike ordinary velocity entries (a speed without direction, usable by any move
 * for many ticks) a push here can only be used by a move that looks like vanilla's
 * reaction to it:
 * <ul>
 * <li>vertical: the move must equal vanilla's first move after the push (last
 * motion with gravity and drag + the impulse), within a few thousandths, and the
 * same move must also show the push sideways (in its direction, as far as the
 * player's own input allows, or stopped by a block on that side): a client that
 * ignores knockback horizontally cannot take just the lift; the credit is that
 * single move, later moves follow NCP's normal gravity model;</li>
 * <li>horizontal: only the part of the move the push vector explains, in its
 * direction ({@code |move| - |move - push|}), and only if the rest is an ordinary
 * move; afterwards the remainder decays with vanilla friction (air 0.91, ground
 * 0.546) and ends a few moves after landing;</li>
 * <li>each push is taken once, within a short window after the server applied it
 * (ping-based, 4-12 ticks), otherwise dropped; pushes of the same tick add up like
 * in vanilla, older untaken pushes are dropped when a newer one is taken.</li>
 * </ul>
 * Pure bookkeeping, no Bukkit access (unit-testable). Ticks are the player's own
 * entity ticks.
 */
public class NativePushTracker {

    public static final double GRAVITY = 0.08;
    public static final double DRAG = 0.98;
    /** Vertical motion after a tick on the ground: (0 - gravity) * drag. */
    public static final double GROUND_MOTION_Y = (0.0 - GRAVITY) * DRAG;
    public static final double AIR_FRICTION = 0.91;
    public static final double GROUND_FRICTION = 0.6 * 0.91;
    /** Vertical tolerance above vanilla's value (rounding only). */
    public static final double V_TOL_ABOVE = 0.005;
    /** Vertical tolerance below vanilla's value. */
    public static final double V_TOL_BELOW = 0.03;
    /** Pushes with a smaller vertical part are matched horizontally only. */
    public static final double MIN_VERTICAL = 0.05;
    /** Mace smash: the server sets the attacker's vertical motion to 0.01F. */
    public static final double MACE_RESET_Y = 0.01F;
    /** Tolerance for the exact mace-smash shapes. */
    public static final double MACE_TOL = 0.003;
    public static final int MIN_WINDOW = 4;
    public static final int MAX_WINDOW = 12;
    /** Moves that start and end on the ground before a horizontal remainder ends. */
    public static final int MAX_GROUND_MOVES = 2;
    static final int MAX_PENDING = 4;
    private static final double MIN_ACTIVE = 0.005;

    private static final class Push {
        final int tick;
        final int window;
        /** Vertical motion the push is added to (NaN: derive from the last move). */
        final double baseY;
        double x, y, z;
        Push(int tick, int window, double baseY, double x, double y, double z) {
            this.tick = tick; this.window = window; this.baseY = baseY;
            this.x = x; this.y = y; this.z = z;
        }
    }

    private final LinkedList<Push> pending = new LinkedList<Push>();
    /** Horizontal remainder of taken pushes, already decayed for the next move. */
    private double activeX, activeZ;
    private int groundMoves;
    private int lastMoveTick = Integer.MIN_VALUE;
    private int lastVelocityEventTick = Integer.MIN_VALUE;
    private int lastImpulseTick = Integer.MIN_VALUE;
    private boolean takenThisMove;
    /** This move's horizontal shape (set by beginMove, used when a push is taken). */
    private double curDx, curDz, curLx, curLz, curFMin = GROUND_FRICTION, curFMax = GROUND_FRICTION;
    private int curBlocked;
    private int maceTick = Integer.MIN_VALUE;
    private int maceWindow;
    private double maceFollowUp = Double.NaN;
    /** The previous move was a mace-stop shape (a Wind Burst push may follow on top of it). */
    private boolean lastMaceShape;
    /** After a credited rise: vanilla's exact next vertical move (gravity, drag), else NaN. */
    private double riseNext = Double.NaN;
    /** Tolerance for the exact gravity continuation of a credited rise. */
    public static final double RISE_TOL = 0.003;

    // Horizontal shape of the first move after a push (round 3): vanilla moves
    // last motion * friction + push + own input (+ sprint-jump boost), clipped by blocks.
    /** Own input acceleration on the ground per tick (sprinting 0.13 x 0.98 input), before Speed effects. */
    public static final double GROUND_ACCEL = 0.13;
    /** Own input acceleration in the air per tick (sprinting flying speed 0.026). */
    public static final double AIR_ACCEL = 0.026;
    /** Horizontal boost of a sprint jump in the same tick. */
    public static final double JUMP_BOOST_H = 0.2;
    /** A jump in the same tick sets the vertical motion to at least this (0.42, a little lower for safety). */
    public static final double JUMP_MIN_DY = 0.4;
    /** Vanilla jump motion (0.42F): a jump in the same tick as a push keeps max(jump, motion + push). */
    public static final double JUMP_Y = 0.42F;
    /** Extra room for entity pushing and rounding. */
    public static final double H_MARGIN = 0.06;
    /** Highest block friction (blue ice 0.989) * 0.91: used when the last move touched ice or slime. */
    public static final double SLIPPERY_FRICTION = 0.989 * 0.91;
    /** Sides of the box at the end of the move with a solid block right next to it (vanilla clips the push there). */
    public static final int BLOCKED_POS_X = 1, BLOCKED_NEG_X = 2, BLOCKED_POS_Z = 4, BLOCKED_NEG_Z = 8;

    /** Window in ticks for a push to show up in the moves: about 1.5 x ping + 3 ticks, 4..12. */
    public static int window(final int pingMs) {
        final int ping = Math.max(0, pingMs);
        return Math.max(MIN_WINDOW, Math.min(MAX_WINDOW, 3 + (int) Math.ceil(1.5 * ping / 50.0)));
    }

    /**
     * A native knockback impulse (Paper EntityKnockbackEvent, cause EXPLOSION).
     * @param afterMaceReset the server set the vertical motion to 0.01 just before
     *            (Wind Burst inside the same mace attack): the client adds the push to
     *            0.01, not to its own falling motion.
     * @return false if not stored (invalid, or a PlayerVelocityEvent of the same tick
     *         already credits this explosion).
     */
    public boolean addPush(final int now, final double x, final double y, final double z,
            final boolean afterMaceReset, final int window) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return false;
        if (x == 0.0 && y == 0.0 && z == 0.0) return false;
        lastImpulseTick = now;
        if (Math.abs(now - lastVelocityEventTick) <= 1) {
            // Hurting explosion: the velocity event carries the server motion incl. this push.
            return false;
        }
        final double baseY = afterMaceReset ? MACE_RESET_Y : Double.NaN;
        final Push last = pending.peekLast();
        if (last != null && Math.abs(now - last.tick) <= 1 && sameBase(last.baseY, baseY)) {
            // Same tick: the client receives both and adds them to its motion together.
            last.x += x; last.y += y; last.z += z;
            return true;
        }
        pending.add(new Push(now, window, baseY, x, y, z));
        while (pending.size() > MAX_PENDING) pending.removeFirst();
        return true;
    }

    private static boolean sameBase(final double a, final double b) {
        return Double.isNaN(a) ? Double.isNaN(b) : a == b;
    }

    /** A PlayerVelocityEvent: drop native pushes of the same tick (credited by the event). */
    public void onVelocityEvent(final int now) {
        lastVelocityEventTick = now;
        final Iterator<Push> it = pending.iterator();
        while (it.hasNext()) {
            if (Math.abs(now - it.next().tick) <= 1) it.remove();
        }
    }

    /** A mace smash: the server resets the attacker's vertical motion to 0.01. */
    public void onMaceSmash(final int now, final int window) {
        maceTick = now;
        maceWindow = window;
        maceFollowUp = Double.NaN;
    }

    private void clearMace() {
        maceTick = Integer.MIN_VALUE;
        maceFollowUp = Double.NaN;
    }

    /** Entity tick of the last native impulse (stored or not). */
    public int getLastImpulseTick() {
        return lastImpulseTick;
    }

    public boolean hasPending() {
        return !pending.isEmpty();
    }

    public double getActiveX() { return activeX; }
    public double getActiveZ() { return activeZ; }

    public void clear() {
        pending.clear();
        activeX = activeZ = 0.0;
        groundMoves = 0;
        takenThisMove = false;
        lastMaceShape = false;
        riseNext = Double.NaN;
        clearMace();
    }

    /**
     * Start of a SurvivalFly move: expire old pushes, decay the horizontal remainder
     * for the previous move, test the vertical match (a push, the mace-smash stop, or the
     * exact gravity continuation of a credited rise up to its apex).
     * @param fromOnGround this move starts on the ground (vertical motion is then -0.0784)
     * @param lastValid lastMove.toIsValid
     * @param lastDy lastMove.yDistance
     * @param lastFromOnGround lastMove.from.onGround (friction applied after it)
     * @param lastToOnGround lastMove.to.onGround
     * @param lastHeadObstructed lastMove.headObstructed
     * @param toGround this move ends on the ground (a fall may be shortened by landing)
     * @param ceiling this move hit the ceiling (a rise may be shortened by it)
     * @param dx this move's x distance
     * @param dz this move's z distance
     * @param lastDx last move's x distance (ignored if not valid)
     * @param lastDz last move's z distance (ignored if not valid)
     * @param lastSlippery the last move touched ice or a bouncy block (other friction)
     * @param speedAmplifier Speed effect amplifier (not finite or negative: none)
     * @param blocked BLOCKED_* bits for the box at the end of this move
     * @return the vertical velocity value this move may use (exactly its own dy), or NaN.
     */
    public double beginMove(final int now, final double dy, final boolean fromOnGround,
            final boolean lastValid, final double lastDy, final boolean lastFromOnGround,
            final boolean lastToOnGround, final boolean lastHeadObstructed, final boolean toGround,
            final boolean ceiling, final double dx, final double dz, final double lastDx, final double lastDz,
            final boolean lastSlippery, final double speedAmplifier, final int blocked) {
        final boolean clipped = toGround || ceiling;
        takenThisMove = false;
        final boolean prevMaceShape = lastMaceShape;
        lastMaceShape = false;
        final double rise = riseNext;
        riseNext = Double.NaN;
        // Horizontal remainder: vanilla friction of the previous move, ends on the ground.
        if (activeX != 0.0 || activeZ != 0.0) {
            if (!lastValid || lastMoveTick == Integer.MIN_VALUE || now - lastMoveTick > 3 || now < lastMoveTick) {
                activeX = activeZ = 0.0;
            }
            else {
                final double f = lastFromOnGround ? GROUND_FRICTION : AIR_FRICTION;
                activeX *= f;
                activeZ *= f;
                if (lastFromOnGround && lastToOnGround) groundMoves ++;
                if (groundMoves > MAX_GROUND_MOVES || Math.sqrt(activeX * activeX + activeZ * activeZ) < MIN_ACTIVE) {
                    activeX = activeZ = 0.0;
                }
            }
        }
        if (activeX == 0.0 && activeZ == 0.0) groundMoves = 0;
        final boolean gap = lastMoveTick == Integer.MIN_VALUE || now - lastMoveTick > 3 || now < lastMoveTick;
        lastMoveTick = now;
        // Horizontal shape: last motion (0 after a gap or without a valid last move: friction has
        // stopped it), the friction applied after it, and what the player's own input can change.
        final boolean lastKnown = lastValid && !gap;
        final double lx = lastKnown ? lastDx : 0.0, lz = lastKnown ? lastDz : 0.0;
        final double fMin = lastFromOnGround ? GROUND_FRICTION : AIR_FRICTION;
        final double fMax = lastSlippery ? Math.max(fMin, SLIPPERY_FRICTION) : fMin;
        final boolean groundStart = fromOnGround || lastValid && lastToOnGround;
        // Sprint-jump boost only when this move can be a jump: rising at least jump height, or a rise
        // cut short by the ceiling. Never for a move that ends on the ground.
        final boolean jumpPossible = !toGround && (dy >= JUMP_MIN_DY || ceiling && dy > 0.0);
        final double ownRoom = H_MARGIN + (groundStart
                ? GROUND_ACCEL * speedFactor(speedAmplifier) + (jumpPossible ? JUMP_BOOST_H : 0.0)
                : AIR_ACCEL);
        curDx = dx; curDz = dz; curLx = lx; curLz = lz; curFMin = fMin; curFMax = fMax; curBlocked = blocked;
        // Expire pushes no move took in time (a client that ignores knockback cannot bank them).
        final Iterator<Push> it = pending.iterator();
        while (it.hasNext()) {
            final Push p = it.next();
            if (now - p.tick > p.window || now < p.tick) it.remove();
        }
        if (maceTick != Integer.MIN_VALUE && (now - maceTick > maceWindow || now < maceTick)) clearMace();
        // 1. A push: vanilla's first move after it. On the ground the client's vertical motion is
        // (0 - gravity) * drag, in the air it follows from the last move.
        final double ownBase = fromOnGround || lastValid && (lastToOnGround || lastHeadObstructed && lastDy > 0.0)
                ? GROUND_MOTION_Y : lastValid ? (lastDy - GRAVITY) * DRAG : Double.NaN;
        final Iterator<Push> pit = pending.iterator();
        while (pit.hasNext()) {
            final Push p = pit.next();
            if (Math.abs(p.y) < MIN_VERTICAL) continue;
            boolean match = false;
            if (Double.isNaN(p.baseY)) {
                match = !Double.isNaN(ownBase) && verticalMatch(dy, ownBase + p.y, toGround, ceiling);
            }
            else {
                // Wind Burst on top of the mace reset (0.01): the client may apply the reset 0-2 ticks
                // before the push, and those ticks can be merged into this move event.
                double v = p.baseY, sum = 0.0;
                for (int k = 0; k <= 2 && !match; k++) {
                    match = verticalMatch(dy, sum + v + p.y, toGround, ceiling);
                    sum += v;
                    v = (v - GRAVITY) * DRAG;
                }
                // The reset ticks came as their own move event just before.
                if (!match && prevMaceShape && !Double.isNaN(ownBase)) {
                    match = verticalMatch(dy, ownBase + p.y, toGround, ceiling);
                }
            }
            // A jump in the same tick from the ground: vanilla keeps the jump (0.42) when it is higher
            // than motion + push; the push still moves the player sideways. No vertical credit is
            // needed (NCP's jump model), only the sideways part, and only if the move shows it.
            final boolean jumpShape = !match && Double.isNaN(p.baseY) && groundStart && !toGround && !ceiling
                    && !Double.isNaN(ownBase) && ownBase + p.y < JUMP_Y - V_TOL_ABOVE
                    && Math.abs(dy - JUMP_Y) <= V_TOL_ABOVE;
            if ((match || jumpShape) && !showsPush(p.x, p.z, dx, dz, lx, lz, fMin, fMax, ownRoom, blocked)) {
                // The lift (or jump) without the sideways push (Speed, horizontal anti-knockback): no
                // credit, and the push is used up (no later move can take it).
                pit.remove();
                continue;
            }
            if (jumpShape) {
                take(p);
                return Double.NaN;
            }
            if (match) {
                if (!Double.isNaN(p.baseY)) clearMace();
                take(p);
                if (dy > 0.0 && !clipped) riseNext = (dy - GRAVITY) * DRAG;
                return dy;
            }
        }
        // 2. Mace smash: the reset move, possibly merged with the next ticks by the move event threshold.
        if (!Double.isNaN(maceFollowUp)) {
            final double v = maceFollowUp;
            maceFollowUp = Double.NaN;
            if (Math.abs(dy - v) <= MACE_TOL) return dy;
        }
        if (maceTick != Integer.MIN_VALUE) {
            double v = MACE_RESET_Y, sum = 0.0;
            for (int n = 1; n <= 3; n++) {
                sum += v;
                v = (v - GRAVITY) * DRAG;
                if (Math.abs(dy - sum) <= MACE_TOL) {
                    maceTick = Integer.MIN_VALUE; // Keep pending pushes (Wind Burst) of this smash.
                    maceFollowUp = v; // The next single tick.
                    lastMaceShape = true;
                    return dy;
                }
            }
        }
        // 3. Rising after a credited push: exactly vanilla's gravity continuation, until the apex.
        if (!Double.isNaN(rise) && rise > 0.0 && !gap && !clipped && Math.abs(dy - rise) <= RISE_TOL) {
            riseNext = (dy - GRAVITY) * DRAG;
            return dy;
        }
        return Double.NaN;
    }

    /** Speed effect: +20 % movement speed per level. */
    static double speedFactor(final double amplifier) {
        return Double.isFinite(amplifier) && amplifier >= 0.0 ? 1.0 + 0.2 * (amplifier + 1.0) : 1.0;
    }

    /**
     * Does the move show the push sideways, like vanilla's first move after it? Vanilla moves
     * last motion * friction + push + own input; an axis with a block next to the box on the
     * push's side is clipped (hole fights, walls) and not required. Along the push direction the
     * move (minus the last motion's carry-over) must cover the push up to {@code ownRoom}, the most
     * the player's own input can take away (for a push shorter than that: the move must not go
     * against it by more than the rest of the room).
     * @param fMin friction after the last move (vanilla's for its ground state)
     * @param fMax highest possible friction after the last move (ice/slime), else fMin
     */
    public static boolean showsPush(double px, double pz, double dx, double dz, double lastDx, double lastDz,
            final double fMin, final double fMax, final double ownRoom, final int blocked) {
        if (px > 0.0 && (blocked & BLOCKED_POS_X) != 0 || px < 0.0 && (blocked & BLOCKED_NEG_X) != 0) {
            px = dx = lastDx = 0.0;
        }
        if (pz > 0.0 && (blocked & BLOCKED_POS_Z) != 0 || pz < 0.0 && (blocked & BLOCKED_NEG_Z) != 0) {
            pz = dz = lastDz = 0.0;
        }
        final double len = Math.sqrt(px * px + pz * pz);
        if (len < 1.0e-6) return true;
        final double ux = px / len, uz = pz / len;
        final double lastAlong = lastDx * ux + lastDz * uz;
        // Carry-over of the last motion: the friction in range that favours the player.
        final double f = lastAlong < 0.0 ? fMax : fMin;
        final double along = (dx - f * lastDx) * ux + (dz - f * lastDz) * uz;
        return along >= len - ownRoom;
    }

    /**
     * dy equals vanilla's predicted first move after the push. A rise may only be cut short by
     * the ceiling, a fall only by landing. A move that ends on the ground never matches a rise
     * (a client that stays on the ground did not take the lift).
     */
    /**
     * The part of the push (px, pz) this move showed: the move minus the last motion's carry-over,
     * projected on the push direction, between 0 and the push length (friction in range that favours
     * the server); blocked axes give nothing.
     */
    public static double[] shownPart(double px, double pz, double dx, double dz, double lastDx, double lastDz,
            final double fMin, final double fMax, final int blocked) {
        if (px > 0.0 && (blocked & BLOCKED_POS_X) != 0 || px < 0.0 && (blocked & BLOCKED_NEG_X) != 0) {
            px = dx = lastDx = 0.0;
        }
        if (pz > 0.0 && (blocked & BLOCKED_POS_Z) != 0 || pz < 0.0 && (blocked & BLOCKED_NEG_Z) != 0) {
            pz = dz = lastDz = 0.0;
        }
        final double len = Math.sqrt(px * px + pz * pz);
        if (len < 1.0e-6) return new double[] {0.0, 0.0};
        final double ux = px / len, uz = pz / len;
        final double lastAlong = lastDx * ux + lastDz * uz;
        final double f = lastAlong < 0.0 ? fMin : fMax;
        final double along = Math.max(0.0, Math.min(len, (dx - f * lastDx) * ux + (dz - f * lastDz) * uz));
        return new double[] {along * ux, along * uz};
    }

    private static boolean verticalMatch(final double dy, final double predicted, final boolean toGround,
            final boolean ceiling) {
        double lo = predicted - V_TOL_BELOW, hi = predicted + V_TOL_ABOVE;
        if (predicted >= 0.0) {
            if (toGround) return false;
            if (ceiling) lo = Math.min(lo, -V_TOL_ABOVE);
        }
        else if (toGround) hi = Math.max(hi, V_TOL_ABOVE);
        return dy >= lo && dy <= hi;
    }

    /**
     * Take a push: older untaken pushes are dropped. Only the sideways part this move really showed
     * joins the remainder for later moves (a blocked axis keeps nothing, as vanilla's collision
     * zeroes it): a client that did not show the push cannot spend it later.
     */
    private void take(final Push p) {
        final double[] shown = shownPart(p.x, p.z, curDx, curDz, curLx, curLz, curFMin, curFMax, curBlocked);
        activeX += shown[0];
        activeZ += shown[1];
        groundMoves = 0;
        takenThisMove = true;
        while (!pending.isEmpty()) {
            if (pending.removeFirst() == p) break;
        }
    }

    /**
     * Horizontal credit for this move, only if it explains all of the excess: from the remainder of
     * pushes taken with their vertical shape, or a pending push without a vertical part.
     * @param needed distance above NCP's ordinary limit (must be > 0)
     * @return the credit (>= needed) or 0.0.
     */
    public double horizontalCredit(final double dx, final double dz, final double needed) {
        if (!(needed > 0.0)) return 0.0;
        curDx = dx;
        curDz = dz;
        double c = credit(dx, dz, activeX, activeZ);
        if (c >= needed) return c;
        if (takenThisMove) return 0.0;
        for (final Push p : pending) {
            // A push with a vertical part is only taken together with its vertical shape (beginMove):
            // a client that ignored the lift (anti-knockback) cannot use the push sideways alone.
            if (Math.abs(p.y) >= MIN_VERTICAL) continue;
            c = credit(dx, dz, activeX + p.x, activeZ + p.z);
            if (c >= needed) {
                take(p);
                return c;
            }
        }
        return 0.0;
    }

    /** The part of the move (dx, dz) that the push (px, pz) explains: |m| - |m - p|, at least 0. */
    public static double credit(final double dx, final double dz, final double px, final double pz) {
        if (px == 0.0 && pz == 0.0) return 0.0;
        final double rx = dx - px, rz = dz - pz;
        return Math.max(0.0, Math.sqrt(dx * dx + dz * dz) - Math.sqrt(rx * rx + rz * rz));
    }
}
