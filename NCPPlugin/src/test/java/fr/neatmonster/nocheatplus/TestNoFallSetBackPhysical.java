package fr.neatmonster.nocheatplus;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.junit.Test;
import fr.neatmonster.nocheatplus.checks.moving.MovingConfig;
import fr.neatmonster.nocheatplus.checks.moving.MovingData;
import fr.neatmonster.nocheatplus.checks.moving.model.MoveTrace;
import fr.neatmonster.nocheatplus.checks.moving.model.PlayerMoveData;
import fr.neatmonster.nocheatplus.checks.moving.player.NoFall;
import fr.neatmonster.nocheatplus.checks.net.NetData;
import fr.neatmonster.nocheatplus.compat.Bridge1_13;
import fr.neatmonster.nocheatplus.compat.Bridge1_9;
import fr.neatmonster.nocheatplus.compat.versions.ServerVersion;
import fr.neatmonster.nocheatplus.players.IPlayerData;

/**
 * REVIEW-LAB-7 #2/#3: after NCP's own correction (SurvivalFly vdistrel set-back)
 * the fall must never cost more than the physical drop. Short glide r7C-lat-crit-en
 * short-2: set back to the fixture start y 93 with FD 6.594 kept, then the whole
 * 12-block fall again: 15 instead of vanilla's 9. The owed distance after a
 * correction is the accepted descent above the target only, and native distance
 * (which survives the correction teleport) is written to match.
 * <p>
 * REVIEW-LAB-7 #1 (LiquidBounce Verus under jitter, 0 damage): a FALL event this
 * check cancelled while airborne stays owed through the native glide limit.
 */
public class TestNoFallSetBackPhysical {
    Player p; MovingData d; NoFall nf; MovingConfig cc; IPlayerData pd; MoveTrace trace;
    float[] fd = {0}; double[] pos = {93}; int[] tick = {0}; boolean[] gliding = {false};
    PlayerMoveData last;

    void setUp(org.mockito.MockedStatic<Bridge1_9> bridge) throws Exception {
        p = mock(Player.class); World w = mock(World.class);
        d = mock(MovingData.class, CALLS_REAL_METHODS);
        nf = mock(NoFall.class, CALLS_REAL_METHODS); cc = mock(MovingConfig.class); pd = mock(IPlayerData.class);
        when(pd.getGenericInstance(MovingConfig.class)).thenReturn(cc);
        when(pd.getGenericInstance(NetData.class)).thenReturn(TestNoFallGlideReversal.netData());
        trace = mock(MoveTrace.class); TestClientNoFallSequences.field(d, MovingData.class, "playerMoves", trace);
        d.clearWindChargeImpulse(); d.clearGlideDebt(); d.clearNoFallData();
        bridge.when(() -> Bridge1_9.isGlidingWithElytra(p)).thenAnswer(c -> gliding[0]);
        bridge.when(() -> Bridge1_9.getLevitationAmplifier(p)).thenReturn(Double.NEGATIVE_INFINITY);
        when(p.getLocation()).thenAnswer(c -> new Location(w, 16, pos[0], 16));
        when(p.getFallDistance()).thenAnswer(c -> fd[0]);
        when(p.getTicksLived()).thenAnswer(c -> tick[0]);
        when(p.getVelocity()).thenReturn(new Vector(0, -1, 0));
        doAnswer(c -> { fd[0] = c.getArgument(0); return null; }).when(p).setFallDistance(anyFloat());
    }

    PlayerMoveData moveData(double from, double to, boolean ground) {
        PlayerMoveData m = new PlayerMoveData();
        m.from.setLocation("world", 16, from, 16, 0, 0); m.to.setLocation("world", 16, to, 16, 0, 0);
        m.toIsValid = true; m.yDistance = to - from; m.to.onGround = ground;
        return m;
    }

    /** One accepted move through NoFall.check; native adds the descent after the event. */
    void move(double from, double to, boolean ground) {
        tick[0]++;
        PlayerMoveData m = moveData(from, to, ground);
        when(trace.getCurrentMove()).thenReturn(m); when(trace.getFirstPastMove()).thenReturn(last);
        var start = mock(fr.neatmonster.nocheatplus.utilities.location.PlayerLocation.class);
        var end = mock(fr.neatmonster.nocheatplus.utilities.location.PlayerLocation.class);
        when(end.isOnGround(anyDouble())).thenReturn(ground);
        nf.check(p, start, end, Double.NEGATIVE_INFINITY, d, cc, pd);
        last = m; pos[0] = to;
        if (to < from) fd[0] += (float) (from - to); else if (to > from) fd[0] = 0f;
    }

    /** Vanilla fall positions (gravity 0.08, drag 0.98) from y down to the floor. */
    void fall(double y, double floor) {
        double v = 0;
        while (true) {
            v = (v - 0.08) * 0.98;
            if (y + v <= floor) { move(y, floor, true); return; }
            move(y, y + v, false); y += v;
        }
    }

    /** A rejected move (SurvivalFly vdistrel) from the current position, then NCP's correction to target. */
    void correction(double rejectedTo, double target) {
        PlayerMoveData m = moveData(pos[0], rejectedTo, false);
        when(trace.getCurrentMove()).thenReturn(m); when(trace.getFirstPastMove()).thenReturn(last);
        nf.checkDamage(p, Math.min(pos[0], rejectedTo), d, pd);
        // Native fall distance survives the correction teleport (SURVIVALFLY-NOTE).
        pos[0] = target;
        NoFall.afterSetBack(p, new Location(null, 16, target, 16), d);
        last = null;
    }

    double damage() { return Math.max(0, NoFall.getDamage(fd[0])); }

    double run(double target) throws Exception {
        String old = ServerVersion.getMinecraftVersion(); ServerVersion.setMinecraftVersion("26.2");
        try (var bridge = mockStatic(Bridge1_9.class); var b13 = TestNoFallGlideReversal.slowFalling()) {
            setUp(bridge);
            pos[0] = 93; move(93, 93, true); // standing at the fixture start
            // Fall to 86.406 (FD 6.594), then the rejected move and the correction.
            double y = 93, v = 0;
            while (y > 86.41) { v = (v - 0.08) * 0.98; double to = Math.max(86.406, y + v); move(y, to, false); y = to; }
            assertEquals("FD before the correction", 6.594, fd[0], 0.01);
            correction(85.5, target);
            fall(target, 81.0);
            return damage();
        } finally { ServerVersion.setMinecraftVersion(old); }
    }

    @Test public void correctionToTheStartOfTheFallPaysItOnce() throws Exception {
        // Vanilla: 93 -> 81 = 12 = 9. Before: 6.594 + 12 = 18.59 = 15.
        assertEquals(9, run(93.0), 0);
        assertEquals(12.0, fd[0], 0.01);
    }

    @Test public void correctionMidFallPaysTheDropAboveTheTargetOnly() throws Exception {
        // Fell 93 -> 86.4, put back to 90: 3 above the target + 9 below = 12 = 9.
        assertEquals(9, run(90.0), 0);
        assertEquals(12.0, fd[0], 0.01);
    }

    @Test public void correctionDownwardKeepsTheAcceptedDescent() throws Exception {
        // Put below the rejected position (84): 6.594 accepted + 3 = FD 9.594, as before.
        run(84.0);
        assertEquals(9.594, fd[0], 0.01);
    }

    /**
     * f8bT-lat-mount-a on-2 (REVIEW-LAB-7 #3): after a boat dismount the
     * accepted descent is 0.84 (not damaging, so no set-back debt); the
     * correction to the boat top 89.4992 resets native distance; the fall from
     * there is 7.94. Before: NoFall restored 0.84 + 7.94 = 8.78 (5, native 4).
     */
    @Test public void nonDamagingDescentIsNotAddedToTheFallFromTheTarget() throws Exception {
        String old = ServerVersion.getMinecraftVersion(); ServerVersion.setMinecraftVersion("26.2");
        try (var bridge = mockStatic(Bridge1_9.class); var b13 = TestNoFallGlideReversal.slowFalling()) {
            setUp(bridge);
            pos[0] = 89.4992; move(89.4992, 89.4992, true);
            double y = 89.4992, v = 0;
            while (y > 88.66) { v = (v - 0.08) * 0.98; double to = Math.max(88.6592, y + v); move(y, to, false); y = to; }
            assertEquals(0.84, d.noFallFallDistance, 0.01);
            PlayerMoveData m = moveData(88.6592, 88.5808, false);
            when(trace.getCurrentMove()).thenReturn(m); when(trace.getFirstPastMove()).thenReturn(last);
            fd[0] = 0f; pos[0] = 89.4992; // the correction teleport reset native distance here
            NoFall.afterSetBack(p, new Location(null, 16, 89.4992, 16), d);
            last = null;
            fall(89.4992, 81.56);
            assertEquals("the physical fall from the target", 7.94, fd[0], 0.01);
        } finally { ServerVersion.setMinecraftVersion(old); }
    }

    /**
     * Cheat side: SpoofLanding's rejected jump after the dive is set back to its
     * pre-jump position (81.15, far below the top 93): the dive stays owed.
     */
    @Test public void glidingReversalCorrectionStillOwesTheDive() throws Exception {
        String old = ServerVersion.getMinecraftVersion(); ServerVersion.setMinecraftVersion("26.2");
        try (var bridge = mockStatic(Bridge1_9.class); var b13 = TestNoFallGlideReversal.slowFalling()) {
            setUp(bridge);
            gliding[0] = true;
            double[] dive = TestNoFallGlideReversal.DIVE;
            pos[0] = dive[0];
            for (int i = 1; i < dive.length; i++) move(dive[i - 1], dive[i], false);
            d.noFallLastMovePackets = 1; d.noFallMovePackets = 1;
            final double a = dive[dive.length - 1];
            fd[0] = 1f; // the native glide limit
            PlayerMoveData m = moveData(a, 81.33319999363422, false);
            when(trace.getCurrentMove()).thenReturn(m); when(trace.getFirstPastMove()).thenReturn(last);
            d.noFallLastMoveX = 16; d.noFallLastMoveY = a; d.noFallLastMoveZ = 16;
            // checkDamage recomputes the span from received packets; give it the rejected packet.
            NetData net = TestNoFallGlideReversal.netData();
            net.addNoFallPacket(new fr.neatmonster.nocheatplus.checks.net.model.DataPacketFlying(false, 16, a, 16, 1));
            net.addNoFallPacket(new fr.neatmonster.nocheatplus.checks.net.model.DataPacketFlying(false, 16, 81.33319999363422, 16, 2));
            when(pd.getGenericInstance(NetData.class)).thenReturn(net);
            d.noFallMatchedPacketSequence = 1;
            nf.checkDamage(p, a, d, pd);
            assertEquals("set-back debt = the dive", 11.846, d.noFallSetBackFallDebt, 0.01);
            NoFall.afterSetBack(p, new Location(null, 16, a, 16), d);
            assertEquals("still owed after the correction below the top", 11.846, d.noFallSetBackFallDebt, 0.01);
            assertEquals(11.846, fd[0], 0.01);
        } finally { ServerVersion.setMinecraftVersion(old); }
    }

    /**
     * REVIEW-LAB-7 #1, r7C-lat-lb3 Verus: the spoofed FALL at 84.95 is cancelled
     * (debt 8.048, native distance restored), the glide goes on, and the native
     * glide limit (server velocity above -0.5) cut FD 10.079 to 1 on an accepted
     * descending move: landing with 0. The cancelled contact stays owed.
     */
    @Test public void cancelledAirborneFallSurvivesTheGlideLimit() throws Exception {
        String old = ServerVersion.getMinecraftVersion(); ServerVersion.setMinecraftVersion("26.2");
        try (var bridge = mockStatic(Bridge1_9.class); var b13 = TestNoFallGlideReversal.slowFalling()) {
            setUp(bridge);
            gliding[0] = true;
            pos[0] = 93;
            double[] path = {92.9, 92.5, 91.8, 90.9, 89.8, 88.5, 87.0, 85.5, 84.95};
            double from = 93;
            for (double to : path) { move(from, to, false); from = to; }
            // The FALL handler cancelled the spoofed airborne FALL here and restored native distance.
            d.noFallCancelledAirFallDebt = 8.048f; fd[0] = 8.048f;
            when(p.getVelocity()).thenReturn(new Vector(0, -0.45, 0));
            double[] rest = {84.43, 83.84, 83.19, 82.54, 81.89, 81.605};
            for (double to : rest) { move(from, to, false); from = to; }
            assertEquals("cancelled contact still owed after the limit", 8.048, d.noFallCancelledAirFallDebt, 0.001);
            fd[0] = 1f; // native limit
            move(from, 81.0, true);
            assertEquals("landing charges the cancelled contact (FD)", 8.048, fd[0], 0.01);
        } finally { ServerVersion.setMinecraftVersion(old); }
    }
}
