package fr.neatmonster.nocheatplus;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.junit.Test;

import fr.neatmonster.nocheatplus.checks.moving.MovingConfig;
import fr.neatmonster.nocheatplus.checks.moving.MovingData;
import fr.neatmonster.nocheatplus.checks.moving.MovingListener;
import fr.neatmonster.nocheatplus.checks.moving.velocity.NativePushTracker;

/**
 * Knockback without a PlayerVelocityEvent (crystal spam, shield, Wind Burst, mace smash),
 * round 2: credited once, only to vanilla-shaped moves, direction-bound, short window;
 * round 3: the lift only together with the sideways push (or a block on that side);
 * round 4: ground moves (no jump room, no lift match), only the shown sideways part is kept.
 * Numbers are from the 8 Oct lab (vanilla 1.21.11 client, Meteor build 86).
 */
public class TestNativeExplosionVelocity {

    private static final double G = NativePushTracker.GRAVITY, D = NativePushTracker.DRAG;
    private static final double ON_GROUND_Y = (0.0 - G) * D; // -0.0784
    private static final double EPS = 1e-9;

    /** Single crystal 4 blocks away (lab crystal_4): kb -0.618/0.250/0. */
    private static final double KX = -0.6179133622451748, KY = 0.2502549124459059;

    private static final double NO_SPEED = Double.NEGATIVE_INFINITY;

    /**
     * A tracker that has seen a standing ground move at {@code tick} (mid-session: the next move's
     * last-move distance is used; on a tracker's very first move it is not).
     */
    private static NativePushTracker warm(final int tick) {
        final NativePushTracker t = new NativePushTracker();
        t.beginMove(tick, 0.0, true, true, 0.0, true, true, false, true, false, 0.0, 0.0, 0.0, 0.0, false,
                NO_SPEED, 0);
        return t;
    }

    /** A move without horizontal distance (pure vertical pushes, or moves that must not match anyway). */
    private static double begin(NativePushTracker t, int now, double dy, double lastDy,
            boolean lastFromGround, boolean lastToGround) {
        return beginH(t, now, dy, lastDy, lastFromGround, lastToGround, 0.0, 0.0, 0.0, 0.0);
    }

    /** A move with its horizontal distance (dx, dz) and the last move's (lastDx, lastDz). */
    private static double beginH(NativePushTracker t, int now, double dy, double lastDy,
            boolean lastFromGround, boolean lastToGround, double dx, double dz, double lastDx, double lastDz) {
        return beginB(t, now, dy, lastDy, lastFromGround, lastToGround, dx, dz, lastDx, lastDz, 0);
    }

    /**
     * As beginH, with blocks next to the box at the end of the move (BLOCKED_* bits). Flags as
     * SurvivalFly sets them: a move that starts on the ground and does not rise ends on the ground.
     */
    private static double beginB(NativePushTracker t, int now, double dy, double lastDy,
            boolean lastFromGround, boolean lastToGround, double dx, double dz, double lastDx, double lastDz,
            int blocked) {
        // The move starts where the last one ended.
        return t.beginMove(now, dy, lastToGround, true, lastDy, lastFromGround, lastToGround, false,
                lastToGround && dy <= 0.0, false, dx, dz, lastDx, lastDz, false, NO_SPEED, blocked);
    }

    @Test public void windowFollowsPingWithinFourToTwelveTicks() {
        assertEquals(4, NativePushTracker.window(0));
        assertEquals(4, NativePushTracker.window(-5));
        assertEquals(6, NativePushTracker.window(100));
        assertEquals(9, NativePushTracker.window(200));
        assertEquals(12, NativePushTracker.window(300));
        assertEquals(12, NativePushTracker.window(5000));
    }

    @Test public void vanillaCrystalOnGroundGetsExactlyThePushOnce() {
        final NativePushTracker t = new NativePushTracker();
        assertTrue(t.addPush(10, KX, KY, 0.0, false, 4));
        // First move after the push, from standing on the ground (lab: dy 0.172, dxz 0.618).
        final double dy1 = ON_GROUND_Y + KY;
        assertEquals(dy1, beginH(t, 11, dy1, 0.0, true, true, KX, 0.0, 0.0, 0.0), EPS);
        assertFalse(t.hasPending());
        // Horizontal: the push explains the whole move (standing player): credit = |push|.
        assertEquals(-KX, t.horizontalCredit(KX, 0.0, -KX - 0.28), 1e-9);
        // Next move: only vanilla's exact gravity continuation of the rise ...
        final double dy2 = (dy1 - G) * D;
        assertEquals(dy2, begin(t, 12, dy2, dy1, true, false), EPS);
        // ... horizontal remainder after ground friction (lab: 0.337), then air friction (lab: 0.307).
        assertEquals(-KX * 0.546, t.horizontalCredit(KX * 0.546, 0.0, 0.05), 1e-9);
        begin(t, 13, (dy2 - G) * D, dy2, false, false);
        assertEquals(-KX * 0.546 * 0.91, t.horizontalCredit(KX * 0.546 * 0.91, 0.0, 0.05), 1e-9);
    }

    @Test public void standingStillAfterATeleportUsesTheGroundMotion() {
        // No valid last move (the player stood still since a teleport/set-back), standing on the ground.
        final NativePushTracker t = new NativePushTracker();
        t.addPush(10, KX, KY, 0.0, false, 4);
        assertTrue(Double.isNaN(t.beginMove(11, ON_GROUND_Y + KY, false, false, 0.0, false, false, false, false, false,
                KX, 0.0, 0.0, 0.0, false, NO_SPEED, 0)));
        // The stale last move's distance is ignored (no valid last move): the push explains the move.
        assertEquals(ON_GROUND_Y + KY, t.beginMove(12, ON_GROUND_Y + KY, true, false, 0.0, false, false, false, false,
                false, KX, 0.0, 0.5, 0.0, false, NO_SPEED, 0), EPS);
    }

    @Test public void creditedRiseContinuesOnlyWithExactGravityUpToTheApex() {
        final NativePushTracker t = new NativePushTracker();
        t.addPush(10, 0.0, 2.2, 0.0, true, 4); // Wind Burst III on top of the mace reset
        double dy = NativePushTracker.MACE_RESET_Y + 2.2, last = -0.9;
        assertEquals(dy, begin(t, 11, dy, last, false, false), EPS);
        int n = 0;
        for (int now = 12; ; now++) {
            last = dy;
            dy = (dy - G) * D;
            if (dy <= 0.0) {
                // Past the apex: nothing more (NCP's ordinary fall model).
                assertTrue(Double.isNaN(begin(t, now, dy, last, false, false)));
                break;
            }
            assertEquals(dy, begin(t, now, dy, last, false, false), EPS);
            n++;
        }
        assertTrue(n > 20);
        // A cheat that rises 0.01 more than gravity allows, or hovers, ends the chain at once.
        final NativePushTracker c = new NativePushTracker();
        c.addPush(10, 0.0, 2.2, 0.0, true, 4);
        final double l = NativePushTracker.MACE_RESET_Y + 2.2;
        begin(c, 11, l, -0.9, false, false);
        assertTrue(Double.isNaN(begin(c, 12, (l - G) * D + 0.01, l, false, false)));
        final double l2 = (l - G) * D + 0.01;
        assertTrue(Double.isNaN(begin(c, 13, (l2 - G) * D, l2, false, false)));
    }

    @Test public void windBurstWhenTheClientAppliedTheResetATickEarlier() {
        final double v1 = NativePushTracker.MACE_RESET_Y, v2 = (v1 - G) * D;
        // Reset tick and push tick merged into one move event (lab: dy 1.142 for Wind Burst I).
        NativePushTracker t = new NativePushTracker();
        t.onMaceSmash(40, 4);
        t.addPush(40, 0.0, 1.2, 0.0, true, 4);
        assertEquals(v1 + v2 + 1.2, begin(t, 41, v1 + v2 + 1.2, -0.905, false, false), EPS);
        assertEquals(1.1414, v1 + v2 + 1.2, 0.001);
        // Reset tick as its own move event, then the push.
        t = new NativePushTracker();
        t.onMaceSmash(40, 4);
        t.addPush(40, 0.0, 1.2, 0.0, true, 4);
        assertEquals(v1, begin(t, 41, v1, -0.905, false, false), EPS);
        assertEquals(v2 + 1.2, begin(t, 42, v2 + 1.2, v1, false, false), EPS);
        // Never more than vanilla's highest variant (push added to 0.01 in the same tick).
        t = new NativePushTracker();
        t.onMaceSmash(40, 4);
        t.addPush(40, 0.0, 1.2, 0.0, true, 4);
        assertTrue(Double.isNaN(begin(t, 41, v1 + 1.2 + 0.01, -0.905, false, false)));
    }

    @Test public void secondCrystalInTheAirAddsToTheRisingMotion() {
        // Lab crystal3_3, 2nd crystal: last move dy 0.031 in the air, kb y 0.394 -> next dy 0.346;
        // sideways the last move (-0.298, air) carries over with 0.91 and the push adds to it.
        final NativePushTracker t = new NativePushTracker();
        t.addPush(20, -0.5987313733304562, 0.3940740815867454, 0.0, false, 4);
        final double dy = (0.031 - G) * D + 0.3940740815867454;
        final double dx = -0.298 * NativePushTracker.AIR_FRICTION - 0.5987313733304562;
        assertEquals(dy, beginH(t, 21, dy, 0.031, false, false, dx, 0.0, -0.298, 0.0), EPS);
        assertEquals(0.346, dy, 0.001);
    }

    @Test public void meteorFlightRidingABurstGetsNothing() {
        // Lab Flight + crystals: last accepted move dy 0.30 (jump-sized), push -0.626/0.401/0.073,
        // the flying client then moves dy 0.48, dxz 0.187 forward (against the push).
        final NativePushTracker t = new NativePushTracker();
        t.addPush(147, -0.626278567405401, 0.40081828662350705, 0.07254560792438323, false, 4);
        assertTrue(Double.isNaN(begin(t, 148, 0.48, 0.30, true, false)));
        assertEquals(0.0, t.horizontalCredit(0.187, 0.0, 0.087), 0.0);
        // Any smaller "up to" value is no match either: the push gives exactly vanilla's value.
        assertTrue(Double.isNaN(begin(t, 149, 0.30, 0.30, false, false)));
        assertTrue(Double.isNaN(begin(t, 150, 0.0, 0.30, false, false)));
        // A move slightly above vanilla's value is refused as well.
        final double vanilla = (0.30 - G) * D + 0.40081828662350705;
        assertTrue(Double.isNaN(begin(t, 151, vanilla + 0.01, 0.30, false, false)));
    }

    @Test public void horizontalCreditIsBoundToThePushDirection() {
        // Perpendicular: |m| - |m - p| < 0 -> nothing.
        assertEquals(0.0, NativePushTracker.credit(0.0, 0.6, -0.6, 0.0), 0.0);
        // Opposite: nothing.
        assertEquals(0.0, NativePushTracker.credit(0.6, 0.0, -0.6, 0.0), 0.0);
        // Along the push but further than the push: only the push length.
        assertEquals(0.6, NativePushTracker.credit(-0.9, 0.0, -0.6, 0.0), 1e-12);
        // Along the push, shorter: the move itself minus what the push does not explain.
        assertEquals(0.2, NativePushTracker.credit(-0.4, 0.0, -0.6, 0.0), 1e-12);
        // Never more than the push, whatever the move.
        for (double a = 0; a < 2 * Math.PI; a += 0.1) {
            for (double r = 0.05; r < 3.0; r += 0.2) {
                assertTrue(NativePushTracker.credit(r * Math.cos(a), r * Math.sin(a), -0.6, 0.2)
                        <= Math.hypot(0.6, 0.2) + 1e-12);
            }
        }
    }

    @Test public void partialExplanationGivesNoCreditAtAll() {
        final NativePushTracker t = new NativePushTracker();
        t.addPush(10, KX, KY, 0.0, false, 4);
        // A Speed-like move along the push that is longer than push + ordinary limit.
        assertEquals(0.0, t.horizontalCredit(KX - 0.5, 0.0, 0.618 + 0.5 - 0.28), 0.0);
        assertTrue(t.hasPending()); // not taken by a move it does not explain
    }

    @Test public void velocityThenFlightAfterTheWindowGetsNothing() {
        // Anti-knockback client ignores the push (no move shows it), Flight on 5 ticks later (lab: 0.25 s).
        final NativePushTracker t = new NativePushTracker();
        t.addPush(100, KX, KY, 0.0, false, NativePushTracker.window(0));
        assertTrue(Double.isNaN(begin(t, 105, ON_GROUND_Y + KY, 0.0, true, true)));
        assertEquals(0.0, t.horizontalCredit(KX, 0.0, 0.3), 0.0);
        assertFalse(t.hasPending());
    }

    @Test public void delayedUseInsideTheWindowIsTheVanillaPushOnlyAndOnce() {
        // A laggy legit client (ping 150 ms, window 3 + 5 = 8): ordinary moves first, then the push.
        final NativePushTracker t = new NativePushTracker();
        final int w = NativePushTracker.window(150);
        assertEquals(8, w);
        t.addPush(200, KX, KY, 0.0, false, w);
        for (int i = 1; i <= 4; i++) {
            assertTrue(Double.isNaN(begin(t, 200 + i, 0.0, 0.0, true, true)));
            assertEquals(0.0, t.horizontalCredit(0.2, 0.0, 0.0), 0.0);
        }
        assertEquals(ON_GROUND_Y + KY, beginH(t, 205, ON_GROUND_Y + KY, 0.0, true, true, KX, 0.0, 0.0, 0.0), EPS);
        // The same move again (a cheat replaying it): no second credit.
        assertTrue(Double.isNaN(begin(t, 206, ON_GROUND_Y + KY, 0.0, true, true)));
    }

    @Test public void ignoredExplosionsDoNotStack() {
        // Three crystals 5 ticks apart that the client ignores: never more than one push per move.
        final NativePushTracker t = new NativePushTracker();
        final int w = NativePushTracker.window(250); // 11 ticks, all three alive at once
        t.addPush(300, KX, KY, 0.0, false, w);
        t.addPush(305, KX, KY, 0.0, false, w);
        t.addPush(310, KX, KY, 0.0, false, w);
        // (In the air: on the ground 2 x KY would be a 0.42 jump shape, which also needs the sideways push.)
        assertTrue(Double.isNaN(begin(t, 311, ON_GROUND_Y + 3 * KY, 0.0, false, false)));
        assertTrue(Double.isNaN(begin(t, 312, ON_GROUND_Y + 2 * KY, 0.0, false, false)));
        assertEquals(0.0, t.horizontalCredit(3 * KX, 0.0, 3 * 0.618 - 0.28), 0.0);
        assertEquals(0.0, t.horizontalCredit(2 * KX, 0.0, 2 * 0.618 - 0.28), 0.0);
        // One push per move, each exactly vanilla's (the first one expired, window 11).
        assertEquals(ON_GROUND_Y + KY, beginH(t, 313, ON_GROUND_Y + KY, 0.0, true, true, KX, 0.0, 0.0, 0.0), EPS);
        assertTrue(t.hasPending());
        assertEquals(ON_GROUND_Y + KY, beginH(t, 314, ON_GROUND_Y + KY, 0.0, true, true,
                KX + KX * NativePushTracker.GROUND_FRICTION, 0.0, KX, 0.0), EPS);
        assertFalse(t.hasPending());
        assertTrue(Double.isNaN(begin(t, 315, ON_GROUND_Y + KY, 0.0, true, true)));
    }

    @Test public void explosionsOfTheSameTickAddUpLikeInVanilla() {
        final NativePushTracker t = new NativePushTracker();
        t.addPush(50, -0.3, 0.2, 0.0, false, 4);
        t.addPush(50, -0.2, 0.15, 0.1, false, 4);
        final double dy = ON_GROUND_Y + 0.35;
        assertEquals(dy, beginH(t, 51, dy, 0.0, true, true, -0.5, 0.1, 0.0, 0.0), EPS);
        assertEquals(Math.hypot(0.5, 0.1), t.horizontalCredit(-0.5, 0.1, 0.2), 1e-12);
    }

    @Test public void hurtingExplosionIsCreditedOnceByItsVelocityEvent() {
        final NativePushTracker t = new NativePushTracker();
        // Native knockback first, velocity event at the end of the same tick: the native push goes.
        t.addPush(70, KX, KY, 0.0, false, 4);
        t.onVelocityEvent(70);
        assertFalse(t.hasPending());
        // Velocity event one tick before (other order / tick boundary): the native push is not stored.
        t.onVelocityEvent(80);
        assertFalse(t.addPush(81, KX, KY, 0.0, false, 4));
        assertFalse(t.hasPending());
        // An older non-hurting crystal of the same burst stays.
        t.addPush(90, KX, KY, 0.0, false, 12);
        t.addPush(95, KX, KY, 0.0, false, 12);
        t.onVelocityEvent(95);
        assertTrue(t.hasPending());
        assertEquals(ON_GROUND_Y + KY, beginH(t, 96, ON_GROUND_Y + KY, 0.0, true, true, KX, 0.0, 0.0, 0.0), EPS);
    }

    @Test public void windBurstAddsThePushToTheMaceReset() {
        final NativePushTracker t = new NativePushTracker();
        t.onMaceSmash(40, 4);
        t.addPush(40, 0.0, 1.2, 0.0, true, 4);
        final double launch = NativePushTracker.MACE_RESET_Y + 1.2;
        // Falling at -0.9 before the smash: vanilla launches from 0.01, not from the fall speed.
        assertTrue(Double.isNaN(begin(t, 41, (-0.9 - G) * D + 1.2, -0.9, false, false)));
        assertEquals(launch, begin(t, 42, launch, -0.9, false, false), EPS);
    }

    @Test public void plainMaceSmashCreditsOnlyTheExactStop() {
        final double v1 = NativePushTracker.MACE_RESET_Y, v2 = (v1 - G) * D, v3 = (v2 - G) * D;
        // Two ticks merged into one move event (lab: dy -0.0586), then the next single tick.
        NativePushTracker t = new NativePushTracker();
        t.onMaceSmash(60, 4);
        assertEquals(v1 + v2, begin(t, 61, v1 + v2, -0.905, false, false), EPS);
        assertEquals(v3, begin(t, 62, v3, v1 + v2, false, false), EPS);
        assertTrue(Double.isNaN(begin(t, 63, (v3 - G) * D, v3, false, false)));
        // One tick (+0.01) on its own.
        t = new NativePushTracker();
        t.onMaceSmash(60, 4);
        assertEquals(v1, begin(t, 61, v1, -0.905, false, false), EPS);
        // A cheat that rises or hovers after the smash gets nothing.
        for (double dy : new double[] {0.3, 0.1, 0.0, -0.03, -0.12}) {
            t = new NativePushTracker();
            t.onMaceSmash(60, 4);
            assertTrue(Double.isNaN(begin(t, 61, dy, -0.905, false, false)));
        }
        // Too late.
        t = new NativePushTracker();
        t.onMaceSmash(60, 4);
        assertTrue(Double.isNaN(begin(t, 65, v1 + v2, -0.905, false, false)));
    }

    @Test public void horizontalRemainderEndsAfterLanding() {
        final NativePushTracker t = new NativePushTracker();
        t.addPush(10, KX, KY, 0.0, false, 4);
        assertEquals(ON_GROUND_Y + KY, beginH(t, 11, ON_GROUND_Y + KY, 0.0, true, true, KX, 0.0, 0.0, 0.0), EPS);
        begin(t, 12, 0.0, 0.17, true, false);  // air
        begin(t, 13, -0.06, 0.09, false, true); // landing move
        begin(t, 14, 0.0, -0.06, false, true);  // first ground move (from air: air friction)
        assertTrue(t.getActiveX() != 0.0);
        begin(t, 15, 0.0, 0.0, true, true);
        begin(t, 16, 0.0, 0.0, true, true);
        begin(t, 17, 0.0, 0.0, true, true);
        assertEquals(0.0, t.getActiveX(), 0.0);
        assertEquals(0.0, t.horizontalCredit(0.3, 0.0, 0.05), 0.0);
    }

    @Test public void gapInMovesOrResetClearsEverything() {
        final NativePushTracker t = new NativePushTracker();
        t.addPush(10, KX, KY, 0.0, false, 4);
        assertEquals(ON_GROUND_Y + KY, beginH(t, 11, ON_GROUND_Y + KY, 0.0, true, true, KX, 0.0, 0.0, 0.0), EPS);
        begin(t, 20, 0.0, 0.17, true, false); // 9 ticks without a checked move
        assertEquals(0.0, t.getActiveX(), 0.0);
        t.addPush(30, KX, KY, 0.0, false, 4);
        t.clear();
        assertFalse(t.hasPending());
    }

    @Test public void invalidOrZeroImpulsesAreIgnored() {
        final NativePushTracker t = new NativePushTracker();
        assertFalse(t.addPush(1, Double.NaN, 1, 0, false, 4));
        assertFalse(t.addPush(1, 0, Double.POSITIVE_INFINITY, 0, false, 4));
        assertFalse(t.addPush(1, 0, 0, 0, false, 4));
        assertFalse(t.hasPending());
    }

    // ---- Round 3: the lift only with the sideways push ----

    /** Lab sp_c3b (Meteor Speed + 3 crystals, b9b97306a): the three pushes, player faces +x, crystals at +x. */
    private static final double[][] SPEED_RUN = {
        {-0.6648393247736379, 0.4142460420398065},
        {-0.5742861387218948, 0.441385201569959},
        {-0.45662550402626856, 0.5594435249631016},
    };

    @Test public void speedClientTakingOnlyTheLiftGetsNothing() {
        // Speed keeps 0.28 b/t forward (+x) on every move and only takes the vertical part.
        final NativePushTracker t = new NativePushTracker();
        // 1st crystal, on the ground: lab dy 0.336, dxz 0.280.
        t.addPush(1461, SPEED_RUN[0][0], SPEED_RUN[0][1], 0.0, false, 4);
        final double dy1 = ON_GROUND_Y + SPEED_RUN[0][1];
        assertEquals(0.336, dy1, 0.001);
        assertTrue(Double.isNaN(beginH(t, 1462, dy1, 0.0, true, true, 0.28, 0.0, 0.28, 0.0)));
        assertFalse(t.hasPending()); // used up: no later move can take it
        assertEquals(0.0, t.horizontalCredit(0.28, 0.0, 0.01), 0.0);
        // No rise continuation either (the lab chain 0.251, 0.167, 0.086 ...).
        assertTrue(Double.isNaN(beginH(t, 1463, (dy1 - G) * D, dy1, true, false, 0.28, 0.0, 0.28, 0.0)));
        // 2nd crystal in the air: last move dy 0.005, lab dy 0.368.
        t.addPush(1466, SPEED_RUN[1][0], SPEED_RUN[1][1], 0.0, false, 4);
        final double dy2 = (0.005 - G) * D + SPEED_RUN[1][1];
        assertEquals(0.368, dy2, 0.001);
        assertTrue(Double.isNaN(beginH(t, 1467, dy2, 0.005, false, false, 0.28, 0.0, 0.28, 0.0)));
        // 3rd crystal: last move dy 0.035, lab dy 0.516.
        t.addPush(1471, SPEED_RUN[2][0], SPEED_RUN[2][1], 0.0, false, 4);
        final double dy3 = (0.035 - G) * D + SPEED_RUN[2][1];
        assertEquals(0.516, dy3, 0.001);
        assertTrue(Double.isNaN(beginH(t, 1472, dy3, 0.035, false, false, 0.28, 0.0, 0.28, 0.0)));
        // Same for Speed running away from the crystal (-x, along the push but at its own 0.28).
        final NativePushTracker a = new NativePushTracker();
        a.addPush(10, SPEED_RUN[0][0], SPEED_RUN[0][1], 0.0, false, 4);
        assertTrue(Double.isNaN(beginH(a, 11, dy1, 0.0, true, true, -0.28, 0.0, -0.28, 0.0)));
        // ... or sideways (z).
        final NativePushTracker z = new NativePushTracker();
        z.addPush(10, SPEED_RUN[0][0], SPEED_RUN[0][1], 0.0, false, 4);
        assertTrue(Double.isNaN(beginH(z, 11, dy1, 0.0, true, true, 0.0, 0.28, 0.0, 0.28)));
    }

    @Test public void vanillaClientsInTheSameSpotGetTheLift() {
        // Standing still (lab crystal3_3 first push): the move is the push.
        NativePushTracker t = new NativePushTracker();
        t.addPush(10, SPEED_RUN[0][0], SPEED_RUN[0][1], 0.0, false, 4);
        final double dy1 = ON_GROUND_Y + SPEED_RUN[0][1];
        assertEquals(dy1, beginH(t, 11, dy1, 0.0, true, true, SPEED_RUN[0][0], 0.0, 0.0, 0.0), EPS);
        // Sprinting towards the crystal: last move 0.28 forward on the ground (friction 0.546) and
        // the sprint input (0.13 x 0.98) still forward, the push on top.
        t = warm(10);
        t.addPush(10, SPEED_RUN[0][0], SPEED_RUN[0][1], 0.0, false, 4);
        double dx = 0.28 * NativePushTracker.GROUND_FRICTION + SPEED_RUN[0][0] + 0.13 * 0.98;
        assertEquals(dy1, beginH(t, 11, dy1, 0.0, true, true, dx, 0.0, 0.28, 0.0), EPS);
        // Same with Speed II (input 0.13 x 1.4).
        t = warm(10);
        t.addPush(10, SPEED_RUN[0][0], SPEED_RUN[0][1], 0.0, false, 4);
        dx = 0.39 * NativePushTracker.GROUND_FRICTION + SPEED_RUN[0][0] + 0.13 * 1.4 * 0.98;
        assertEquals(dy1, t.beginMove(11, dy1, true, true, 0.0, true, true, false, false, false,
                dx, 0.0, 0.39, 0.0, false, 1.0, 0), EPS);
        // In the air (2nd crystal), sprinting forward against the push (air input 0.026).
        t = warm(20);
        t.addPush(20, SPEED_RUN[1][0], SPEED_RUN[1][1], 0.0, false, 4);
        final double dy2 = (0.005 - G) * D + SPEED_RUN[1][1];
        dx = -0.3 * NativePushTracker.AIR_FRICTION + SPEED_RUN[1][0] + 0.026;
        assertEquals(dy2, beginH(t, 21, dy2, 0.005, false, false, dx, 0.0, -0.3, 0.0), EPS);
        // Sprint jump in the same tick against a strong push (vertical = the push, >= 0.42).
        t = warm(30);
        t.addPush(30, -0.7, 0.6, 0.0, false, 4);
        final double dyj = ON_GROUND_Y + 0.6;
        dx = 0.28 * NativePushTracker.GROUND_FRICTION - 0.7 + 0.13 * 0.98 + 0.2;
        assertEquals(dyj, beginH(t, 31, dyj, 0.0, true, true, dx, 0.0, 0.28, 0.0), EPS);
    }

    @Test public void horizontalOnlyAntiKnockbackGetsNoLift() {
        // Velocity with 0 % horizontal / 100 % vertical, standing: the lift alone.
        NativePushTracker t = new NativePushTracker();
        t.addPush(10, KX, KY, 0.0, false, 4);
        assertTrue(Double.isNaN(beginH(t, 11, ON_GROUND_Y + KY, 0.0, true, true, 0.0, 0.0, 0.0, 0.0)));
        // Half the sideways push in the air (2nd crystal): too little for vanilla's air input.
        t = warm(20);
        t.addPush(20, SPEED_RUN[1][0], SPEED_RUN[1][1], 0.0, false, 4);
        final double dy2 = (0.005 - G) * D + SPEED_RUN[1][1];
        final double half = -0.3 * NativePushTracker.AIR_FRICTION + 0.5 * SPEED_RUN[1][0];
        assertTrue(Double.isNaN(beginH(t, 21, dy2, 0.005, false, false, half, 0.0, -0.3, 0.0)));
        // A sprint-jump allowance is not given when the vertical shows that no jump happened (dy < 0.42).
        t = new NativePushTracker();
        t.addPush(10, SPEED_RUN[0][0], SPEED_RUN[0][1], 0.0, false, 4);
        final double dy1 = ON_GROUND_Y + SPEED_RUN[0][1];
        final double dxJumpRoom = SPEED_RUN[0][0] + 0.13 * 0.98 + 0.2;
        assertTrue(Double.isNaN(beginH(t, 11, dy1, 0.0, true, true, dxJumpRoom, 0.0, 0.0, 0.0)));
    }

    @Test public void pushIntoAWallOrHoleEdgeStillGetsTheLift() {
        // In a hole: the push goes into the wall on -x, vanilla moves 0 sideways and rises.
        NativePushTracker t = new NativePushTracker();
        t.addPush(10, KX, KY, 0.0, false, 4);
        assertEquals(ON_GROUND_Y + KY, beginB(t, 11, ON_GROUND_Y + KY, 0.0, true, true, 0.0, 0.0, 0.0, 0.0,
                NativePushTracker.BLOCKED_NEG_X), EPS);
        // A block on the other side does not explain a missing -x push.
        t = new NativePushTracker();
        t.addPush(10, KX, KY, 0.0, false, 4);
        assertTrue(Double.isNaN(beginB(t, 11, ON_GROUND_Y + KY, 0.0, true, true, 0.0, 0.0, 0.0, 0.0,
                NativePushTracker.BLOCKED_POS_X | NativePushTracker.BLOCKED_POS_Z | NativePushTracker.BLOCKED_NEG_Z)));
        // Diagonal push, x blocked: z must still show.
        t = new NativePushTracker();
        t.addPush(10, -0.5, KY, 0.5, false, 4);
        assertEquals(ON_GROUND_Y + KY, beginB(t, 11, ON_GROUND_Y + KY, 0.0, true, true, 0.0, 0.5, 0.0, 0.0,
                NativePushTracker.BLOCKED_NEG_X), EPS);
        t = new NativePushTracker();
        t.addPush(10, -0.5, KY, 0.5, false, 4);
        assertTrue(Double.isNaN(beginB(t, 11, ON_GROUND_Y + KY, 0.0, true, true, 0.0, -0.28, 0.0, -0.28,
                NativePushTracker.BLOCKED_NEG_X)));
    }

    @Test public void smallSidewaysPartsAreCoveredByTheOwnInputRoom() {
        // Crystal almost below, strong lift (a sprint jump is possible): 0.1 sideways is inside what the
        // player's own input can change, even running against it.
        final NativePushTracker t = new NativePushTracker();
        t.addPush(10, -0.1, 0.6, 0.0, false, 4);
        assertEquals(ON_GROUND_Y + 0.6, beginH(t, 11, ON_GROUND_Y + 0.6, 0.0, true, true, 0.28, 0.0, 0.28, 0.0), EPS);
        // Wind Burst is straight up: any sideways move.
        final NativePushTracker w = new NativePushTracker();
        w.addPush(40, 0.0, 1.2, 0.0, true, 4);
        assertEquals(NativePushTracker.MACE_RESET_Y + 1.2, beginH(w, 41, NativePushTracker.MACE_RESET_Y + 1.2, -0.9,
                false, false, 0.28, 0.0, 0.28, 0.0), EPS);
    }

    @Test public void iceCarryOverUsesIceFriction() {
        // Sliding at 0.6 towards +x on ice, pushed -0.66: vanilla keeps 0.6 x 0.98 x 0.91 of the slide.
        final double px = -0.66, py = 0.4, dy = ON_GROUND_Y + py;
        final double dx = 0.6 * 0.98 * 0.91 + px;
        NativePushTracker t = warm(10);
        t.addPush(10, px, py, 0.0, false, 4);
        assertEquals(dy, t.beginMove(11, dy, true, true, 0.0, true, true, false, false, false,
                dx, 0.0, 0.6, 0.0, true, NO_SPEED, 0), EPS);
        // The same numbers without ice under the last move are not vanilla's (stone keeps 0.546).
        t = warm(10);
        t.addPush(10, px, py, 0.0, false, 4);
        assertTrue(Double.isNaN(t.beginMove(11, dy, true, true, 0.0, true, true, false, false, false,
                dx, 0.0, 0.6, 0.0, false, NO_SPEED, 0)));
    }

    @Test public void showsPushIsSymmetricAndNeverAsksMoreThanThePush() {
        final double f = NativePushTracker.GROUND_FRICTION, room = 0.19;
        for (double a = 0; a < 2 * Math.PI; a += 0.3) {
            final double px = 0.7 * Math.cos(a), pz = 0.7 * Math.sin(a);
            // Exactly the push (standing): yes; the push minus the room: yes; less: no.
            assertTrue(NativePushTracker.showsPush(px, pz, px, pz, 0, 0, f, f, room, 0));
            assertTrue(NativePushTracker.showsPush(px, pz, px * (1 - room / 0.7) + 1e-9 * px, pz * (1 - room / 0.7)
                    + 1e-9 * pz, 0, 0, f, f, room, 0));
            assertFalse(NativePushTracker.showsPush(px, pz, px * 0.5, pz * 0.5, 0, 0, f, f, room, 0));
            // Opposite: no.
            assertFalse(NativePushTracker.showsPush(px, pz, -px, -pz, 0, 0, f, f, room, 0));
            // All four sides blocked: nothing to show.
            assertTrue(NativePushTracker.showsPush(px, pz, 0, 0, 0, 0, f, f, room, 15));
        }
    }

    // ---- Round 4: moves on the ground, jump room, only the shown sideways part is kept ----

    /** A move as SurvivalFly passes it, with explicit ground/ceiling flags and Speed amplifier. */
    private static double move(NativePushTracker t, int now, double dy, boolean fromGround, double lastDy,
            boolean lastFromGround, boolean lastToGround, boolean toGround, boolean ceiling,
            double dx, double dz, double lastDx, double lastDz, double speedAmp, int blocked) {
        return t.beginMove(now, dy, fromGround, true, lastDy, lastFromGround, lastToGround, false, toGround, ceiling,
                dx, dz, lastDx, lastDz, false, speedAmp, blocked);
    }

    @Test public void standingAntiKnockbackOnTheGroundTakesNothing() {
        // Review case: a client that stays on the ground (dy 0, no sideways move) while a 0.39 push
        // arrives. Round 3 gave this move sprint-jump room and matched dy 0 as a "clipped" lift.
        for (double amp : new double[] {NO_SPEED, 0.0, 1.0}) {
            final double px = amp == 1.0 ? -0.44 : -0.39;
            final NativePushTracker t = new NativePushTracker();
            t.addPush(10, px, 0.25, 0.0, false, 4);
            assertTrue(Double.isNaN(move(t, 11, 0.0, true, 0.0, true, true, true, false, 0.0, 0.0, 0.0, 0.0, amp, 0)));
            assertEquals(0.0, t.getActiveX(), 0.0);
            // Next ground moves: nothing banked to spend (round 3: ~0.21 above the limit).
            assertTrue(Double.isNaN(move(t, 12, 0.0, true, 0.0, true, true, true, false, 0.0, 0.0, 0.0, 0.0, amp, 0)));
            assertEquals(0.0, t.horizontalCredit(0.21 * Math.signum(px), 0.0, 0.21), 0.0);
            assertEquals(0.0, t.getActiveX(), 0.0);
            // The push expires unused.
            move(t, 15, 0.0, true, 0.0, true, true, true, false, 0.0, 0.0, 0.0, 0.0, amp, 0);
            assertFalse(t.hasPending());
        }
    }

    @Test public void sprintingAntiKnockbackOnTheGroundTakesNothing() {
        // Steady sprint (0.28 b/t) along the push direction, ignoring a 0.52 push, staying on the ground.
        NativePushTracker t = new NativePushTracker();
        t.addPush(10, -0.52, 0.3, 0.0, false, 4);
        assertTrue(Double.isNaN(move(t, 11, 0.0, true, 0.0, true, true, true, false, -0.28, 0.0, -0.28, 0.0, NO_SPEED, 0)));
        assertEquals(0.0, t.getActiveX(), 0.0);
        assertEquals(0.0, t.horizontalCredit(-0.28, 0.0, 0.05), 0.0);
        // Same with Speed II (0.39 b/t) and a 0.61 push.
        t = new NativePushTracker();
        t.addPush(10, -0.61, 0.3, 0.0, false, 4);
        assertTrue(Double.isNaN(move(t, 11, 0.0, true, 0.0, true, true, true, false, -0.39, 0.0, -0.39, 0.0, 1.0, 0)));
        assertEquals(0.0, t.getActiveX(), 0.0);
        // Sprinting along the push and taking only the lift (airborne, dy < 0.4): no jump room either.
        t = new NativePushTracker();
        t.addPush(10, -0.52, 0.3, 0.0, false, 4);
        final double dy = ON_GROUND_Y + 0.3;
        assertTrue(Double.isNaN(move(t, 11, dy, true, 0.0, true, true, false, false, -0.28, 0.0, -0.28, 0.0, NO_SPEED, 0)));
        assertFalse(t.hasPending());
        assertEquals(0.0, t.getActiveX(), 0.0);
    }

    @Test public void aGroundMoveNeverMatchesALift() {
        // Positive predicted rise, move ends on the ground: no match, whatever the sideways move.
        final NativePushTracker t = new NativePushTracker();
        t.addPush(10, KX, KY, 0.0, false, 4);
        assertTrue(Double.isNaN(move(t, 11, 0.0, true, 0.0, true, true, true, false, KX, 0.0, 0.0, 0.0, NO_SPEED, 0)));
        assertTrue(t.hasPending());
        // The vanilla move one tick later (lag) still gets it.
        assertEquals(ON_GROUND_Y + KY, move(t, 12, ON_GROUND_Y + KY, true, 0.0, true, true, false, false,
                KX, 0.0, 0.0, 0.0, NO_SPEED, 0), EPS);
        // A rise cut by the ceiling may be shorter (and may be a jump): dy 0.1 under a low ceiling.
        final NativePushTracker c = new NativePushTracker();
        c.addPush(10, KX, KY, 0.0, false, 4);
        assertEquals(0.1, move(c, 11, 0.1, true, 0.0, true, true, false, true, KX, 0.0, 0.0, 0.0, NO_SPEED, 0), EPS);
        // A fall shortened by landing still matches (push while falling, predicted < 0).
        final NativePushTracker l = new NativePushTracker();
        l.addPush(20, -0.5, 0.3, 0.0, false, 4);
        final double lastDy = -0.4, predicted = (lastDy - G) * D + 0.3;
        assertTrue(predicted < 0.0);
        assertEquals(-0.05, move(l, 21, -0.05, false, lastDy, false, false, true, false,
                -0.2 * 0.91 - 0.5, 0.0, -0.2, 0.0, NO_SPEED, 0), EPS);
    }

    @Test public void jumpRoomOnlyForARealJump() {
        final double px = -0.35;
        // Lift below jump height (dy 0.27): room 0.19, a client that shows nothing sideways gets nothing.
        NativePushTracker t = new NativePushTracker();
        t.addPush(10, px, 0.35, 0.0, false, 4);
        assertTrue(Double.isNaN(move(t, 11, ON_GROUND_Y + 0.35, true, 0.0, true, true, false, false,
                0.0, 0.0, 0.0, 0.0, NO_SPEED, 0)));
        // Lift of jump height (dy >= 0.42 possible as a sprint jump): room 0.39 covers 0.35 for the lift,
        // but nothing of the sideways part is kept for later moves.
        t = new NativePushTracker();
        t.addPush(10, px, 0.55, 0.0, false, 4);
        assertEquals(ON_GROUND_Y + 0.55, move(t, 11, ON_GROUND_Y + 0.55, true, 0.0, true, true, false, false,
                0.0, 0.0, 0.0, 0.0, NO_SPEED, 0), EPS);
        assertEquals(0.0, t.getActiveX(), 0.0);
        assertEquals(0.0, t.horizontalCredit(-0.2, 0.0, 0.05), 0.0);
    }

    @Test public void onlyTheShownSidewaysPartIsKept() {
        // Small push (0.12) with a strong lift: a client standing still gets the lift, keeps nothing sideways.
        NativePushTracker t = new NativePushTracker();
        t.addPush(10, -0.12, 0.5, 0.0, false, 4);
        assertEquals(ON_GROUND_Y + 0.5, beginH(t, 11, ON_GROUND_Y + 0.5, 0.0, true, true, 0.0, 0.0, 0.0, 0.0), EPS);
        assertEquals(0.0, t.getActiveX(), 0.0);
        // The vanilla client moves with it and keeps it.
        t = new NativePushTracker();
        t.addPush(10, -0.12, 0.5, 0.0, false, 4);
        assertEquals(ON_GROUND_Y + 0.5, beginH(t, 11, ON_GROUND_Y + 0.5, 0.0, true, true, -0.12, 0.0, 0.0, 0.0), EPS);
        assertEquals(-0.12, t.getActiveX(), 1e-12);
        // Small push still bound to its direction: Speed running against it at 0.28 gets no lift.
        t = new NativePushTracker();
        t.addPush(10, -0.15, 0.3, 0.0, false, 4);
        assertTrue(Double.isNaN(beginH(t, 11, ON_GROUND_Y + 0.3, 0.0, true, true, 0.28, 0.0, 0.28, 0.0)));
        // Hole: the push goes into the wall, vanilla's collision zeroes that axis: nothing kept.
        t = new NativePushTracker();
        t.addPush(10, KX, KY, 0.0, false, 4);
        assertEquals(ON_GROUND_Y + KY, beginB(t, 11, ON_GROUND_Y + KY, 0.0, true, true, -0.2, 0.0, 0.0, 0.0,
                NativePushTracker.BLOCKED_NEG_X), EPS);
        assertEquals(0.0, t.getActiveX(), 0.0);
        // shownPart never exceeds the push and is 0 against it.
        final double f = NativePushTracker.GROUND_FRICTION;
        assertArrayEquals(new double[] {-0.6, 0.0}, NativePushTracker.shownPart(-0.6, 0.0, -0.9, 0.0, 0.0, 0.0, f, f, 0), 1e-12);
        assertArrayEquals(new double[] {0.0, 0.0}, NativePushTracker.shownPart(-0.6, 0.0, 0.3, 0.0, 0.0, 0.0, f, f, 0), 1e-12);
        assertArrayEquals(new double[] {-0.3, 0.0}, NativePushTracker.shownPart(-0.6, 0.0, -0.3, 0.0, 0.0, 0.0, f, f, 0), 1e-12);
    }

    @Test public void antiKnockbackCannotUseThePushSidewaysAlone() {
        // Velocity 0 %/0 % plus a fast Speed (0.45 b/t) along the push direction, staying on the ground:
        // the push (with a lift) is never taken by a sideways move alone (B/round 3 credited 0.45 here).
        final NativePushTracker t = warm(10);
        t.addPush(10, -0.45, 0.2, 0.0, false, 4);
        assertTrue(Double.isNaN(move(t, 11, 0.0, true, 0.0, true, true, true, false, -0.45, 0.0, 0.0, 0.0, NO_SPEED, 0)));
        assertEquals(0.0, t.horizontalCredit(-0.45, 0.0, 0.16), 0.0);
        assertTrue(Double.isNaN(move(t, 12, 0.0, true, 0.0, true, true, true, false, -0.45, 0.0, -0.45, 0.0, NO_SPEED, 0)));
        assertEquals(0.0, t.horizontalCredit(-0.45, 0.0, 0.16), 0.0);
        assertEquals(0.0, t.getActiveX(), 0.0);
        // A push without a vertical part can still be taken sideways (nothing vertical to show).
        final NativePushTracker h = warm(10);
        h.addPush(10, -0.5, 0.02, 0.0, false, 4);
        assertTrue(Double.isNaN(move(h, 11, 0.0, true, 0.0, true, true, true, false, -0.5, 0.0, 0.0, 0.0, NO_SPEED, 0)));
        assertEquals(0.5, h.horizontalCredit(-0.5, 0.0, 0.2), 1e-12);
        assertEquals(-0.5, h.getActiveX(), 1e-12);
    }

    @Test public void jumpInTheSameTickKeepsTheSidewaysPush() {
        // Vanilla: a jump from the ground in the push tick keeps max(0.42, motion + push) = 0.42 and
        // moves sideways by push + sprint input + sprint-jump boost.
        NativePushTracker t = warm(10);
        t.addPush(10, -0.6, 0.3, 0.0, false, 4);
        final double dx = -0.6 - 0.2 - 0.13 * 0.98;
        assertTrue(Double.isNaN(move(t, 11, NativePushTracker.JUMP_Y, true, 0.0, true, true, false, false,
                dx, 0.0, 0.0, 0.0, NO_SPEED, 0)));
        assertFalse(t.hasPending());
        assertEquals(-0.6, t.getActiveX(), 1e-12);
        assertEquals(0.6, t.horizontalCredit(dx, 0.0, 0.5), 1e-9);
        // Jumping while ignoring the push sideways (running against it): nothing, push used up.
        t = warm(10);
        t.addPush(10, -0.6, 0.3, 0.0, false, 4);
        assertTrue(Double.isNaN(move(t, 11, NativePushTracker.JUMP_Y, true, 0.0, true, true, false, false,
                0.28 + 0.2, 0.0, 0.28, 0.0, NO_SPEED, 0)));
        assertFalse(t.hasPending());
        assertEquals(0.0, t.getActiveX(), 0.0);
        assertEquals(0.0, t.horizontalCredit(-0.3, 0.0, 0.05), 0.0);
    }

    @Test public void firstMoveOfATrackerDoesNotUseAnUnseenLastMove() {
        // Integer.MIN_VALUE start: the gap test must fire (no overflow), the last move's carry is not used.
        final NativePushTracker t = new NativePushTracker();
        t.addPush(10, KX, KY, 0.0, false, 4);
        // With a carry of 0.8 against the push, a client that does not move sideways would pass if used.
        assertTrue(Double.isNaN(move(t, 11, ON_GROUND_Y + KY, true, 0.0, true, true, false, false,
                0.0, 0.0, 0.8, 0.0, NO_SPEED, 0)));
    }

    private static Player player(final double serverVy, final int ticks, final boolean vehicle) {
        final Player player = mock(Player.class);
        when(player.getVelocity()).thenReturn(new Vector(0.0, serverVy, 0.0));
        when(player.getTicksLived()).thenReturn(ticks);
        when(player.isInsideVehicle()).thenReturn(vehicle);
        return player;
    }

    @Test public void listenerStoresAPushWithTheServerTickAndPing() {
        final NativePushTracker t = new NativePushTracker();
        MovingListener.acceptNativeExplosionVelocity(player(-0.0784, 40, false), t, new Vector(KX, KY, 0.0), 0);
        assertTrue(t.hasPending());
        assertEquals(40, t.getLastImpulseTick());
        assertTrue(Double.isNaN(begin(t, 45, ON_GROUND_Y + KY, 0.0, true, true))); // window 4 at 0 ms
        // Wind Burst: server vertical motion is exactly 0.01F at the explosion.
        final NativePushTracker w = new NativePushTracker();
        MovingListener.acceptNativeExplosionVelocity(player(NativePushTracker.MACE_RESET_Y, 12, false), w,
                new Vector(0.0, 1.2, 0.0), 0);
        assertEquals(NativePushTracker.MACE_RESET_Y + 1.2,
                begin(w, 13, NativePushTracker.MACE_RESET_Y + 1.2, -0.9, false, false), EPS);
    }

    @Test public void listenerIgnoresVehiclesAndNull() {
        final NativePushTracker t = new NativePushTracker();
        MovingListener.acceptNativeExplosionVelocity(player(0, 1, true), t, new Vector(0, 1, 0), 0);
        MovingListener.acceptNativeExplosionVelocity(player(0, 1, false), t, null, 0);
        assertFalse(t.hasPending());
    }

    @Test public void attributedWindChargeStaysOnItsOwnPathAndReportsIt() {
        final Player player = player(0.0, 23, false);
        final MovingData data = mock(MovingData.class);
        data.windChargeImpulseEntityTick = 23;
        data.windChargeImpulseSource = java.util.UUID.randomUUID();
        assertTrue(MovingListener.acceptNativeWindChargeVelocity(player, data, mock(MovingConfig.class), new Vector(0, 1, 0)));
        // Unattributed explosion impulse: not a wind charge.
        final MovingData other = mock(MovingData.class);
        assertFalse(MovingListener.acceptNativeWindChargeVelocity(player, other, mock(MovingConfig.class), new Vector(0, 1, 0)));
        verifyNoInteractions(other);
    }

    @Test public void maceSmashListenerHelperArmsTheExactStopOnly() {
        final NativePushTracker t = new NativePushTracker();
        MovingListener.acceptMaceSmash(player(-0.7, 3, false), t, 0);
        final double v1 = NativePushTracker.MACE_RESET_Y, v2 = (v1 - G) * D;
        assertEquals(v1 + v2, begin(t, 4, v1 + v2, -0.7, false, false), EPS);
    }
}
