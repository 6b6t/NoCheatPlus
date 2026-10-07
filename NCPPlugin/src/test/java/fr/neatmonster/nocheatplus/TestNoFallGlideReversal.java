package fr.neatmonster.nocheatplus;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.lang.reflect.Method;
import java.util.*;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import fr.neatmonster.nocheatplus.checks.moving.MovingConfig;
import fr.neatmonster.nocheatplus.checks.moving.MovingData;
import fr.neatmonster.nocheatplus.checks.moving.model.MoveTrace;
import fr.neatmonster.nocheatplus.checks.moving.model.PlayerMoveData;
import fr.neatmonster.nocheatplus.checks.moving.player.NoFall;
import fr.neatmonster.nocheatplus.compat.Bridge1_9;
import fr.neatmonster.nocheatplus.compat.versions.ServerVersion;
import fr.neatmonster.nocheatplus.players.IPlayerData;
import fr.neatmonster.nocheatplus.checks.moving.velocity.SimpleAxisVelocity;
import fr.neatmonster.nocheatplus.checks.moving.velocity.SimpleEntry;

/**
 * FIX-6 P2 (round 7): under 80 +- 40 ms jitter the server's own velocity is
 * already 0 at the last dive move (its simulation met the ground), so the native
 * glide limit (velocity above -0.5) is pending. LiquidBounce PacketJump's +1e-9
 * split move (f6D-dbg-pj-1 r10) and SpoofLanding's jump after an absorbed landing
 * packet (f6D-lat-sl r1) then let that limit forgive the whole 11.85-block dive:
 * 0 damage. Replays the measured coordinates through NoFall.check. A vertical
 * speed gain beyond glide physics without an impulse is not a pull-up, so the
 * forgiven descent stays owed at the next contact. Legit pull-ups, rockets,
 * knockback, the native landing race and checkpoint restores keep the limit.
 */
@RunWith(Parameterized.class)
public class TestNoFallGlideReversal {
    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> cases() {
        return Arrays.asList(new Object[][] {
            // name, landing damage range. A straight landing after this dive is FD 12.0 = 9 here
            // (the LB lab fixture's vanilla is 8). The cheats owe the dive at the reversal
            // point (11.85 = 8) plus nothing of the last 0.15 block: 8 or 9, never 0.
            {"vanilla-landing", 9, 9}, {"native-race-landing", 9, 9},
            {"LB/PacketJump-jitter", 8, 9}, {"LB/SpoofLanding-jitter", 8, 9}, {"LB/SpoofLanding-glide-stop", 8, 9},
            {"legit-pull-up", 0, 0}, {"rocket-reversal", 0, 0}, {"knockback-reversal", 0, 0}, {"knockback-queued", 0, 0},
            {"checkpoint-restore", 0, 0},
        });
    }

    // Measured native dive (TestNoFallDiveSpoofs), ending at 81.1536 above the pad top 81.
    static final double[] DIVE = {93, 92.92159999847412, 92.69107262956116, 92.38675580210362, 92.01012530386497,
        91.56262740688155, 91.04567945777657, 90.46067045626782, 89.8089616221052, 89.0918869506696, 88.31075375745972,
        87.46684321168922, 86.56141085921195, 85.59568713498858, 84.57087786530408, 83.48816475994072,
        82.34870589450762, 81.15363618312385};
    // f6D-lat-sl r1 wire positions after the absorbed 81.0 landing packet.
    static final double[] SPOOF_LANDING_AFTER = {81.33319999363422, 81.58133599222516, 81.74610927405124,
        81.8291870918577, 81.83220335336675, 81.75675928817726, 81.60442410132671, 81.37673561378173,
        81.07520089011895, 81.0};

    private final String name;
    private final int lo, hi;
    public TestNoFallGlideReversal(String name, int lo, int hi) { this.name = name; this.lo = lo; this.hi = hi; }

    // Test state.
    Player p; MovingData d; NoFall nf; MovingConfig cc; IPlayerData pd; MoveTrace trace;
    float[] fd = {0}; double[] pos = {93}; int[] tick = {0}; boolean[] gliding = {true};
    PlayerMoveData last;

    void move(double from, double to, boolean ground) {
        PlayerMoveData m = new PlayerMoveData();
        m.from.setLocation("world", 16, from, 16, 0, 0);
        m.to.setLocation("world", 16, to, 16, 0, 0);
        m.toIsValid = true; m.yDistance = to - from; m.to.onGround = ground;
        if (name.equals("knockback-reversal") && to > from && last != null && last.yDistance < -1) {
            m.verVelUsed = new SimpleEntry(0.4, 3);
        }
        when(trace.getCurrentMove()).thenReturn(m);
        when(trace.getFirstPastMove()).thenReturn(last);
        fr.neatmonster.nocheatplus.utilities.location.PlayerLocation start = mock(fr.neatmonster.nocheatplus.utilities.location.PlayerLocation.class);
        fr.neatmonster.nocheatplus.utilities.location.PlayerLocation end = mock(fr.neatmonster.nocheatplus.utilities.location.PlayerLocation.class);
        when(end.isOnGround(anyDouble())).thenReturn(ground);
        nf.check(p, start, end, Double.NEGATIVE_INFINITY, d, cc, pd);
        last = m;
        pos[0] = to;
    }

    /** Invoke the glide stop the way the movement listener does (with its move when supported). */
    static void stopGlide(Player p, MovingData d, double yDistance) throws Exception {
        try {
            Method m = NoFall.class.getMethod("stopGlide", Player.class, MovingData.class, double.class);
            m.invoke(null, p, d, yDistance);
        } catch (NoSuchMethodException older) {
            NoFall.stopGlide(p, d);
        }
    }

    @Test public void forgivenDiveNeedsAPlausiblePullUp() throws Exception {
        String old = ServerVersion.getMinecraftVersion(); ServerVersion.setMinecraftVersion("26.2");
        try (var bridge = mockStatic(Bridge1_9.class)) {
            p = mock(Player.class); World w = mock(World.class);
            d = mock(MovingData.class, CALLS_REAL_METHODS);
            nf = mock(NoFall.class, CALLS_REAL_METHODS); cc = mock(MovingConfig.class); pd = mock(IPlayerData.class);
            trace = mock(MoveTrace.class); TestClientNoFallSequences.field(d, MovingData.class, "playerMoves", trace);
            d.clearWindChargeImpulse(); // field initialisers do not run on this mock
            // As on the server: the previous landing's fly-check transition
            // (MovingListener.workaroundFlyCheckTransition) queued its own entries,
            // still valid during this dive (20 moves / 140 ticks; f7M-dbg-pj-1 r1).
            SimpleAxisVelocity axis = new SimpleAxisVelocity();
            axis.add(new SimpleEntry(0, -1.25, 20)); axis.add(new SimpleEntry(0, 0.0, 20));
            if (name.equals("knockback-queued")) axis.add(new SimpleEntry(0, 0.4, 20));
            TestClientNoFallSequences.field(d, MovingData.class, "verVel", axis);
            bridge.when(() -> Bridge1_9.isGlidingWithElytra(p)).thenAnswer(c -> gliding[0]);
            when(p.getLocation()).thenAnswer(c -> new Location(w, 16, pos[0], 16));
            when(p.getFallDistance()).thenAnswer(c -> fd[0]);
            when(p.getTicksLived()).thenAnswer(c -> tick[0]);
            doAnswer(c -> { fd[0] = c.getArgument(0); return null; }).when(p).setFallDistance(anyFloat());

            // The dive. The server's own velocity runs about one tick ahead of the
            // client's move (f6D-dbg-pj-1: 92.92 move with velocity -0.4475); native
            // limits FD at a tick after a velocity above -0.5.
            double velocity = 0;
            for (int i = 1; i < DIVE.length; i++) {
                tick[0] = i; double dy = DIVE[i] - DIVE[i - 1];
                if (i > 1 && velocity > -0.5) fd[0] = Math.min(1, fd[0]);
                // At the last dive move the server simulation already met the pad: velocity 0.
                velocity = i == DIVE.length - 1 ? 0 : dy - 0.07;
                when(p.getVelocity()).thenReturn(new Vector(0, velocity, 0));
                move(DIVE[i - 1], DIVE[i], false);
                fd[0] += (float) -dy;
            }
            assertEquals("measured dive (lab nf 11.846363)", 11.846, d.noFallFallDistance, 0.01);
            final double a = DIVE[DIVE.length - 1];
            tick[0]++;
            switch (name) {
                case "vanilla-landing":
                case "native-race-landing": {
                    // Vanilla: the next tick lands. In the race native's tick limit
                    // forgave its own FD first (vanilla charges 0 then); NCP never forgives a landing.
                    if (name.equals("native-race-landing")) fd[0] = 1f;
                    move(a, 81.0, true); fd[0] += (float) (a - 81.0);
                    break;
                }
                case "LB/PacketJump-jitter": {
                    // +1e-9 split move (native resets FD on any upward move), then the
                    // landing split move in the same tick.
                    fd[0] = 0f; move(a, a + 1e-9, false);
                    move(a + 1e-9, 81.0, true); fd[0] += (float) (a + 1e-9 - 81.0);
                    break;
                }
                case "LB/SpoofLanding-jitter":
                case "LB/SpoofLanding-glide-stop": {
                    // The 81.0 landing packet (ground false) got no event; native added its
                    // descent and its tick limit cut FD to 1. Next event: the jump off the pad.
                    fd[0] = 1f;
                    double from = a;
                    for (int i = 0; i < SPOOF_LANDING_AFTER.length; i++) {
                        double to = SPOOF_LANDING_AFTER[i]; boolean ground = i == SPOOF_LANDING_AFTER.length - 1;
                        when(p.getVelocity()).thenReturn(new Vector(0, Math.max(-0.45, to - from), 0));
                        if (i > 0) tick[0]++;
                        if (to > from) fd[0] = 0f;
                        if (name.endsWith("glide-stop") && i == 0) {
                            // The listener ends the glide on that same move.
                            gliding[0] = false;
                            PlayerMoveData m = new PlayerMoveData();
                            m.from.setLocation("world", 16, from, 16, 0, 0); m.to.setLocation("world", 16, to, 16, 0, 0);
                            m.toIsValid = true; m.yDistance = to - from;
                            when(trace.getCurrentMove()).thenReturn(m); when(trace.getFirstPastMove()).thenReturn(last);
                            stopGlide(p, d, to - from);
                        }
                        move(from, to, ground);
                        if (to < from) fd[0] += (float) (from - to);
                        from = to;
                    }
                    break;
                }
                case "legit-pull-up":
                case "rocket-reversal":
                case "knockback-reversal":
                case "knockback-queued":
                case "checkpoint-restore": {
                    double[] path;
                    if (name.equals("legit-pull-up")) {
                        // Elytra lift/climb gains under 0.5 b/t per tick: -1.195 to above zero takes ticks.
                        path = pullUp(a);
                    } else if (name.equals("checkpoint-restore")) {
                        // A worker join restores the glide with a stale steep previous move.
                        NoFall.onGlidingMove(p, d, 0.0);
                        assertEquals(0f, d.noFallContactFallDebt, 0);
                        path = gentleDescent(a);
                    } else {
                        // A rocket (or knockback) turns the dive into a climb in one tick.
                        if (name.equals("rocket-reversal")) d.fireworksBoostDuration = 20;
                        path = rocketClimb(a);
                    }
                    double from = a;
                    for (int i = 0; i < path.length; i++) {
                        double to = path[i]; boolean ground = i == path.length - 1;
                        if (i > 0) tick[0]++;
                        // Native limit: the previous tick's velocity was above -0.5.
                        if (i > 0 && path[i - 1] - (i > 1 ? path[i - 2] : a) > -0.5) fd[0] = Math.min(1, fd[0]);
                        if (i == 0) fd[0] = Math.min(1, fd[0]);
                        if (to > from) fd[0] = 0f;
                        when(p.getVelocity()).thenReturn(new Vector(0, to - from, 0));
                        move(from, to, ground);
                        if (to < from) fd[0] += (float) (from - to);
                        from = to;
                    }
                    if (name.equals("rocket-reversal")) d.fireworksBoostDuration = 0;
                    break;
                }
                default: fail(name);
            }
            final double damage = Math.max(0, NoFall.getDamage(fd[0]));
            assertTrue(name + ": landing damage " + damage + " (FD " + fd[0] + ") not in [" + lo + ", " + hi + "]",
                    damage >= lo && damage <= hi);
        } finally { ServerVersion.setMinecraftVersion(old); }
    }

    /** Pull-up from the dive: vertical speed rises 0.2-0.3 per tick, then a slow glide down onto a pad 1 block up. */
    static double[] pullUp(double start) {
        double[] dy = {-0.95, -0.7, -0.45, -0.2, 0.05, 0.25, 0.3, 0.2, 0.1, 0.0, -0.1, -0.2, -0.3, -0.35, -0.4, -0.4, -0.4};
        return integrate(start, dy);
    }
    /** One-tick reversal (rocket boost or knockback), then a slow glide down. */
    static double[] rocketClimb(double start) {
        double[] dy = {0.15, 0.4, 0.5, 0.4, 0.2, 0.0, -0.2, -0.35, -0.4, -0.4, -0.4, -0.4};
        return integrate(start, dy);
    }
    static double[] gentleDescent(double start) {
        double[] dy = {0.2, 0.1, -0.1, -0.2, -0.3, -0.3};
        return integrate(start, dy);
    }
    static double[] integrate(double start, double[] dy) {
        double[] out = new double[dy.length + 1]; double y = start;
        for (int i = 0; i < dy.length; i++) { y += dy[i]; out[i] = y; }
        out[dy.length] = Math.floor(y); // land on the block top below
        if (out[dy.length] >= y) out[dy.length] = y - 0.1;
        return out;
    }

    /** A gliding player's rejected impossible reversal keeps its descent (SurvivalFly set-back path). */
    @Test public void rejectedReversalWhileGlidingKeepsSetBackDebtAndOrdinaryRejectionDoesNot() throws Exception {
        if (!name.equals("vanilla-landing")) return; // once per run
        String old = ServerVersion.getMinecraftVersion(); ServerVersion.setMinecraftVersion("26.2");
        try (var bridge = mockStatic(Bridge1_9.class)) {
            for (boolean reversal : new boolean[] {true, false}) {
                Player p = mock(Player.class); World w = mock(World.class);
                MovingData d = mock(MovingData.class, CALLS_REAL_METHODS);
                MoveTrace trace = mock(MoveTrace.class); TestClientNoFallSequences.field(d, MovingData.class, "playerMoves", trace);
                d.clearWindChargeImpulse();
                MovingConfig cc = mock(MovingConfig.class); IPlayerData pd = mock(IPlayerData.class);
                when(pd.getGenericInstance(MovingConfig.class)).thenReturn(cc);
                NoFall nf = mock(NoFall.class, CALLS_REAL_METHODS);
                bridge.when(() -> Bridge1_9.isGlidingWithElytra(p)).thenReturn(true);
                float[] fd = {1f};
                when(p.getFallDistance()).thenAnswer(c -> fd[0]);
                doAnswer(c -> { fd[0] = c.getArgument(0); return null; }).when(p).setFallDistance(anyFloat());
                when(p.getLocation()).thenReturn(new Location(w, 16, 81.15363618312385, 16));
                d.noFallFallDistance = 11.846f; d.noFallMaxY = 93;
                PlayerMoveData lastMove = new PlayerMoveData(); lastMove.toIsValid = true; lastMove.yDistance = -1.195;
                lastMove.from.setLocation("world", 16, 82.34870589450762, 16, 0, 0);
                lastMove.to.setLocation("world", 16, 81.15363618312385, 16, 0, 0);
                PlayerMoveData rejected = new PlayerMoveData(); rejected.toIsValid = true;
                rejected.from.setLocation("world", 16, 81.15363618312385, 16, 0, 0);
                rejected.to.setLocation("world", 16, reversal ? 81.33319999363422 : 80.05, 16, 0, 0);
                rejected.yDistance = reversal ? 0.18 : -1.1;
                when(trace.getFirstPastMove()).thenReturn(lastMove); when(trace.getCurrentMove()).thenReturn(rejected);
                nf.checkDamage(p, 81.15363618312385, d, pd);
                if (reversal) {
                    assertEquals("impossible reversal keeps the dive as set-back debt", 11.846f, d.noFallSetBackFallDebt, 0.01);
                } else {
                    assertEquals("an ordinary gliding rejection keeps native glide accounting", 0f, d.noFallSetBackFallDebt, 0);
                    assertEquals(1f, fd[0], 0);
                }
            }
        } finally { ServerVersion.setMinecraftVersion(old); }
    }
}
