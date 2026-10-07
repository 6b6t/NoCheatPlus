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
import fr.neatmonster.nocheatplus.checks.net.NetConfig;
import fr.neatmonster.nocheatplus.checks.net.NetData;
import fr.neatmonster.nocheatplus.checks.net.model.DataPacketFlying;
import fr.neatmonster.nocheatplus.compat.Bridge1_13;
import fr.neatmonster.nocheatplus.compat.Bridge1_9;
import fr.neatmonster.nocheatplus.compat.versions.ServerVersion;
import fr.neatmonster.nocheatplus.players.IPlayerData;
import fr.neatmonster.nocheatplus.checks.moving.velocity.SimpleAxisVelocity;
import fr.neatmonster.nocheatplus.checks.moving.velocity.SimpleEntry;

/**
 * Native glide accounting (vanilla 26.2, LivingEntity#updateFallFlying): the
 * server's own velocity is its glide simulation of the player from the last
 * received position. When that simulation meets the ground (velocity 0), the
 * next server tick caps fall distance at exactly one before any later packet.
 * <p>
 * Legit (REVIEW-CODE-7 P1 #1): a landing packet that arrives after that tick
 * takes vanilla's capped distance, whether the cap was visible at the last move
 * (velocity above -0.5) or happened at a tick without a packet (native distance
 * exactly one). A landing in the same tick as the last dive move is charged in
 * full, as vanilla does.
 * <p>
 * Cheats (FIX-6 P2, round 7): LiquidBounce PacketJump's +1e-9 split move and
 * SpoofLanding's jump after an absorbed landing packet would let that limit
 * forgive the whole 11.85-block dive. A move higher than vanilla glide physics
 * can reach from the last move over the client ticks it spans (received
 * packets), with no impulse, keeps the forgiven descent owed at the next
 * contact. The bound reads the horizontal speed (REVIEW-CODE-7 P2 #3): legit
 * high-speed pull-ups after a boost, and grouped movement, are not reversals.
 */
@RunWith(Parameterized.class)
public class TestNoFallGlideReversal {
    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> cases() {
        return Arrays.asList(new Object[][] {
            // name, landing damage range (-1: the native model's own damage for this trajectory).
            // A straight landing after this dive is FD 12.0 = 9 here (the LB lab fixture's vanilla is 8).
            {"vanilla-landing", 9, 9}, {"bunched-landing", 9, 9},
            // Vanilla forgives these (the server's glide simulation met the pad a tick before the landing packet).
            {"native-race-landing", 0, 0}, {"native-race-unobserved", 0, 0},
            {"native-race-unobserved-gliding", 0, 0}, {"native-race-unobserved-airborne", 0, 0},
            // The cheats owe the dive at the reversal point (11.85 = 8) plus nothing of the last 0.15 block: 8 or 9, never 0.
            {"LB/PacketJump-jitter", 8, 9}, {"LB/SpoofLanding-jitter", 8, 9}, {"LB/SpoofLanding-glide-stop", 8, 9},
            {"LB/SpoofLanding-shadow", 8, 9}, {"LB/PacketJump-shadow", 8, 9}, {"crafted-refill", 9, 10},
            {"legit-pull-up", -1, -1}, {"high-speed-pull-up", -1, -1}, {"grouped-pull-up", -1, -1},
            {"rocket-reversal", 0, 0}, {"knockback-reversal", 0, 0}, {"knockback-queued", 0, 0},
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
    Player p; MovingData d; NoFall nf; MovingConfig cc; IPlayerData pd; MoveTrace trace; NetData net;
    float[] fd = {0}; double[] pos = {93}; double[] posX = {16}; int[] tick = {0}; boolean[] gliding = {true};
    long time = 1;
    PlayerMoveData last;

    static NetData netData() throws Exception {
        NetConfig nc = mock(NetConfig.class);
        for (String f : new String[] {"flyingFrequencySeconds", "flyingFrequencyRedundantSeconds", "packetFrequencySeconds"}) {
            TestClientNoFallSequences.field(nc, NetConfig.class, f, 1);
        }
        return new NetData(nc);
    }

    /** A received position packet (ProtocolLib MONITOR), with or without a Bukkit event. */
    void packet(double x, double y, boolean ground) {
        net.addNoFallPacket(new DataPacketFlying(ground, x, y, 16, time++));
    }

    PlayerMoveData moveData(double fromX, double from, double toX, double to, boolean ground) {
        PlayerMoveData m = new PlayerMoveData();
        m.from.setLocation("world", fromX, from, 16, 0, 0);
        m.to.setLocation("world", toX, to, 16, 0, 0);
        m.toIsValid = true; m.yDistance = to - from; m.hDistance = Math.abs(toX - fromX); m.to.onGround = ground;
        return m;
    }

    /** One Bukkit move through NoFall.check, the way the movement listener runs it (glide stop first). */
    void event(double fromX, double from, double toX, double to, boolean ground) throws Exception {
        PlayerMoveData m = moveData(fromX, from, toX, to, ground);
        if (name.equals("knockback-reversal") && to > from && last != null && last.yDistance < -1) {
            m.verVelUsed = new SimpleEntry(0.4, 3);
        }
        when(trace.getCurrentMove()).thenReturn(m);
        when(trace.getFirstPastMove()).thenReturn(last);
        if (!gliding[0]) stopGlide(p, d, pd, m);
        fr.neatmonster.nocheatplus.utilities.location.PlayerLocation start = mock(fr.neatmonster.nocheatplus.utilities.location.PlayerLocation.class);
        fr.neatmonster.nocheatplus.utilities.location.PlayerLocation end = mock(fr.neatmonster.nocheatplus.utilities.location.PlayerLocation.class);
        when(end.isOnGround(anyDouble())).thenReturn(ground);
        nf.check(p, start, end, Double.NEGATIVE_INFINITY, d, cc, pd);
        last = m;
        pos[0] = to; posX[0] = toX;
    }

    void move(double from, double to, boolean ground) throws Exception {
        packet(16, to, ground);
        event(16, from, 16, to, ground);
    }

    /** Invoke the glide stop the way the movement listener does (its move, and whether it lands). */
    static void stopGlide(Player p, MovingData d, IPlayerData pd, PlayerMoveData m) throws Exception {
        try {
            Method begin = NoFall.class.getMethod("beginMove", PlayerMoveData.class, MovingData.class, IPlayerData.class);
            begin.invoke(null, m, d, pd);
            Method stop = NoFall.class.getMethod("stopGlide", Player.class, MovingData.class, double.class, boolean.class);
            stop.invoke(null, p, d, m.yDistance, m.to.onGround);
        } catch (NoSuchMethodException older) {
            try {
                Method stop = NoFall.class.getMethod("stopGlide", Player.class, MovingData.class, double.class);
                stop.invoke(null, p, d, m.yDistance);
            } catch (NoSuchMethodException oldest) {
                NoFall.stopGlide(p, d);
            }
        }
    }

    void setUp(org.mockito.MockedStatic<Bridge1_9> bridge) throws Exception {
        p = mock(Player.class); World w = mock(World.class);
        d = mock(MovingData.class, CALLS_REAL_METHODS);
        nf = mock(NoFall.class, CALLS_REAL_METHODS); cc = mock(MovingConfig.class); pd = mock(IPlayerData.class);
        net = netData();
        when(pd.getGenericInstance(NetData.class)).thenReturn(net);
        when(pd.getGenericInstance(MovingConfig.class)).thenReturn(cc);
        trace = mock(MoveTrace.class); TestClientNoFallSequences.field(d, MovingData.class, "playerMoves", trace);
        d.clearWindChargeImpulse(); // field initialisers do not run on this mock
        d.clearGlideDebt();
        // As on the server: the previous landing's fly-check transition
        // (MovingListener.workaroundFlyCheckTransition) queued its own entries,
        // still valid during this dive (20 moves / 140 ticks; f7M-dbg-pj-1 r1).
        SimpleAxisVelocity axis = new SimpleAxisVelocity();
        axis.add(new SimpleEntry(0, -1.25, 20)); axis.add(new SimpleEntry(0, 0.0, 20));
        if (name.equals("knockback-queued")) axis.add(new SimpleEntry(0, 0.4, 20));
        TestClientNoFallSequences.field(d, MovingData.class, "verVel", axis);
        bridge.when(() -> Bridge1_9.isGlidingWithElytra(p)).thenAnswer(c -> gliding[0]);
        bridge.when(() -> Bridge1_9.getLevitationAmplifier(p)).thenReturn(Double.NEGATIVE_INFINITY);
        when(p.getLocation()).thenAnswer(c -> new Location(w, posX[0], pos[0], 16));
        when(p.getFallDistance()).thenAnswer(c -> fd[0]);
        when(p.getTicksLived()).thenAnswer(c -> tick[0]);
        when(p.getVelocity()).thenReturn(new Vector(0, -1, 0));
        doAnswer(c -> { fd[0] = c.getArgument(0); return null; }).when(p).setFallDistance(anyFloat());
    }

    /** No slow falling (the predicate defers to native accounting with it). */
    static org.mockito.MockedStatic<Bridge1_13> slowFalling() {
        var b13 = mockStatic(Bridge1_13.class);
        b13.when(() -> Bridge1_13.getSlowfallingAmplifier(any())).thenReturn(Double.NEGATIVE_INFINITY);
        return b13;
    }

    void velocity(double y) { when(p.getVelocity()).thenReturn(new Vector(0, y, 0)); }

    /**
     * The measured dive, up to (not including) DIVE[endIndex] when endIndex is
     * below its length. The server's own velocity runs about one tick ahead of
     * the client's move (f6D-dbg-pj-1: 92.92 move with velocity -0.4475);
     * native limits FD at a tick after a velocity above -0.5. collidedAtLast:
     * at the last move the server simulation already met the pad (velocity 0).
     */
    void dive(int endIndex, boolean collidedAtLast) throws Exception {
        double velocity = 0;
        for (int i = 1; i < endIndex; i++) {
            tick[0] = i; double dy = DIVE[i] - DIVE[i - 1];
            if (i > 1 && velocity > -0.5) fd[0] = Math.min(1, fd[0]);
            velocity = i == DIVE.length - 1 && collidedAtLast ? 0 : dy - 0.07;
            velocity(velocity);
            move(DIVE[i - 1], DIVE[i], false);
            fd[0] += (float) -dy;
        }
    }

    @Test public void forgivenDiveNeedsAPlausiblePullUp() throws Exception {
        String old = ServerVersion.getMinecraftVersion(); ServerVersion.setMinecraftVersion("26.2");
        try (var bridge = mockStatic(Bridge1_9.class); var b13 = slowFalling()) {
            setUp(bridge);
            if (name.endsWith("pull-up")) { pullUp(); return; }
            final boolean collided = !name.equals("vanilla-landing") && !name.startsWith("native-race-unobserved")
                    && !name.endsWith("-shadow") && !name.equals("crafted-refill");
            dive(name.equals("native-race-unobserved-airborne") ? DIVE.length - 1 : DIVE.length, collided);
            if (!name.equals("native-race-unobserved-airborne")) {
                assertEquals("measured dive (lab nf 11.846363)", 11.846, d.noFallFallDistance, 0.01);
            }
            final double a = DIVE[DIVE.length - 1];
            if (!name.equals("bunched-landing")) tick[0]++;
            switch (name) {
                case "vanilla-landing":
                case "bunched-landing":
                case "native-race-landing": {
                    // Vanilla: the next tick lands (no cap: velocity -1.2 at the last move, or
                    // the landing came in the same tick). In the race native's tick limit
                    // forgave its own FD first (f7F-lat-dive enabled-0/3, native-2/3/5).
                    if (name.equals("native-race-landing")) fd[0] = 1f;
                    move(a, 81.0, true); fd[0] += (float) (a - 81.0);
                    break;
                }
                case "native-race-unobserved":
                case "native-race-unobserved-gliding": {
                    // f7F-lat-dive native-1/4: velocity -1.30 at the last dive move; the
                    // simulation met the pad at the next tick (no packet), the tick after
                    // capped FD at 1 and (native-1/4) ended the glide. Then the landing packet.
                    tick[0]++; fd[0] = 1f; velocity(-0.0784);
                    if (name.equals("native-race-unobserved")) gliding[0] = false;
                    move(a, 81.0, true); fd[0] += (float) (a - 81.0);
                    break;
                }
                case "native-race-unobserved-airborne": {
                    // f7F-lat-dive enabled-4: the cap (and glide end) came before the
                    // 82.35 -> 81.15 packet, which arrives with native FD exactly 1.
                    final double b = DIVE[DIVE.length - 2];
                    tick[0]++; fd[0] = 1f; velocity(-0.0784); gliding[0] = false;
                    move(b, a, false); fd[0] += (float) (b - a);
                    tick[0]++; velocity(-0.1552);
                    move(a, 81.0, true); fd[0] += (float) (a - 81.0);
                    break;
                }
                case "LB/PacketJump-jitter":
                case "LB/PacketJump-shadow": {
                    // +1e-9 split move (native resets FD on any upward move), then the
                    // landing split move in the same tick. Shadow: velocity -1.2 at the last
                    // dive move, the owner probe saw the simulation meet the pad first.
                    if (name.endsWith("shadow")) NoFall.onGlideShadowContact(d);
                    move(a, a + 1e-9, false); fd[0] = 0f;
                    move(a + 1e-9, 81.0, true); fd[0] += (float) (a + 1e-9 - 81.0);
                    break;
                }
                case "LB/SpoofLanding-jitter":
                case "LB/SpoofLanding-glide-stop":
                case "LB/SpoofLanding-shadow": {
                    // The 81.0 landing packet (ground false) got no event; native added its
                    // descent and (jitter) its tick limit cut FD to 1. Next event: the jump off the pad.
                    // Shadow: no cap yet, but the owner probe saw the simulation meet the pad.
                    packet(16, 81.0, false);
                    if (name.endsWith("shadow")) { NoFall.onGlideShadowContact(d); fd[0] += (float) (a - 81.0); }
                    else fd[0] = 1f;
                    double from = a;
                    for (int i = 0; i < SPOOF_LANDING_AFTER.length; i++) {
                        double to = SPOOF_LANDING_AFTER[i]; boolean ground = i == SPOOF_LANDING_AFTER.length - 1;
                        velocity(Math.max(-0.45, to - from));
                        if (i > 0) tick[0]++;
                        if (name.endsWith("glide-stop") && i == 0) gliding[0] = false; // the listener ends the glide on that move
                        move(from, to, ground);
                        if (to > from) fd[0] = 0f;
                        if (to < from) fd[0] += (float) (from - to);
                        from = to;
                    }
                    break;
                }
                case "crafted-refill": {
                    // A client that resets native FD with an upward move, then refills it to
                    // exactly one, must not pass for the native glide limit (no cap is visible:
                    // velocity -1.2 everywhere, no shadow contact).
                    velocity(-1.2);
                    move(a, a + 1e-9, false); fd[0] = 0f;
                    tick[0]++;
                    move(a + 1e-9, a + 1e-9 - 1.0, false); fd[0] += 1.0f;
                    tick[0]++;
                    move(a + 1e-9 - 1.0, 80.0, true); fd[0] += (float) (a + 1e-9 - 1.0 - 80.0);
                    break;
                }
                case "rocket-reversal":
                case "knockback-reversal":
                case "knockback-queued":
                case "checkpoint-restore": {
                    double[] path;
                    if (name.equals("checkpoint-restore")) {
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
                        velocity(to - from);
                        move(from, to, ground);
                        if (to > from) fd[0] = 0f;
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

    /**
     * Vanilla glide tick (LivingEntity#updateFallFlyingMovement), flying along +x:
     * {vx, vy} after one tick at this pitch (degrees, negative looks up).
     */
    static double[] glideTick(double vx, double vy, double pitchDegrees) {
        final double pitch = Math.toRadians(pitchDegrees);
        final double lx = Math.cos(pitch), lookHor = Math.abs(lx), moveHor = Math.abs(vx);
        final double liftForce = Math.cos(pitch) * Math.cos(pitch);
        vy += 0.08 * (-1.0 + liftForce * 0.75);
        if (vy < 0.0 && lookHor > 0.0) { final double c = vy * -0.1 * liftForce; vx += lx * c / lookHor; vy += c; }
        if (pitch < 0.0 && lookHor > 0.0) {
            final double c = moveHor * -Math.sin(pitch) * 0.04; vx -= lx * c / lookHor; vy += c * 3.2;
        }
        if (lookHor > 0.0) vx += (lx / lookHor * moveHor - vx) * 0.1;
        return new double[] {vx * (double) 0.99f, vy * (double) 0.98f};
    }

    /**
     * Legit pull-ups made of vanilla glide ticks, against the native model
     * (server velocity one tick ahead, limit at the tick after a velocity above
     * -0.5, upward packets reset, landing charges the rest). Expected damage is
     * the native model's own, and no reversal debt may appear on the way.
     * legit: dive at pitch 45 from h 0.8, pull at -35, glide down at 25.
     * high-speed: a launch just ended at h 6.0 / vy -1.2 (riptide over, fireworks
     * duration 0), dive 4 ticks at 30, pull at -85: the move the native limit
     * first forgives gains 0.698 with 4.07 of descent, above the old fixed 0.6
     * bound (REVIEW-CODE-7 P2 #3); vanilla stays 0.0275 under the new bound.
     * grouped: the legit one, with two client ticks in one Bukkit move through the pull.
     */
    void pullUp() throws Exception {
        final boolean fast = name.equals("high-speed-pull-up"), grouped = name.equals("grouped-pull-up");
        final List<double[]> ticks = new ArrayList<>(); // {pitch} per client tick
        double vx = fast ? 6.0 : 0.8, vy = fast ? -1.2 : -0.4;
        double x = 16, y = 120;
        // Phase plan: dive, pull, glide down until the pad (Y 81).
        final List<Double> pitches = new ArrayList<>();
        for (int i = 0; i < (fast ? 4 : 18); i++) pitches.add(fast ? 30.0 : 45.0);
        for (int i = 0; i < (fast ? 6 : 14); i++) pitches.add(fast ? -85.0 : -35.0);
        for (int i = 0; i < 1200; i++) pitches.add(25.0);
        // Client ticks.
        final List<double[]> path = new ArrayList<>(); // {x, y, vx, vy}
        double padY = 81.0;
        for (double pitch : pitches) {
            final double[] v = glideTick(vx, vy, pitch); vx = v[0]; vy = v[1];
            double nx = x + vx, ny = y + vy;
            if (ny <= padY) { path.add(new double[] {nx, padY, vx, 0}); break; }
            path.add(new double[] {nx, ny, vx, vy}); x = nx; y = ny;
        }
        assertTrue("trajectory lands", path.get(path.size() - 1)[1] == padY);
        // Native model and NCP, one event per client tick (grouped: pairs during the pull).
        pos[0] = 120; posX[0] = 16; fd[0] = 0f;
        double prevServerVelocity = vy = (fast ? -1.2 : -0.4);
        double fromX = 16, from = 120;
        final double[] nativeFrom = {Double.NaN};
        int i = 0; float minDebt = 0f;
        while (i < path.size()) {
            final boolean land = i == path.size() - 1;
            final boolean pair = grouped && !land && i >= 16 && i < 26 && i % 2 == 0;
            tick[0] += 1;
            // Native: the tick before this packet applies the limit with the velocity seen so far.
            if (prevServerVelocity > -0.5) fd[0] = Math.min(1f, fd[0]);
            double[] step = path.get(i);
            if (pair) {
                // First of two client ticks: received, no Bukkit event; native processes it,
                // then its tick limits with the server velocity after that packet.
                packet(step[0], step[1], false);
                if (step[1] > from) fd[0] = 0f; else fd[0] += (float) (from - step[1]);
                final double midServer = path.get(i + 1)[3];
                tick[0] += 1;
                if (midServer > -0.5) fd[0] = Math.min(1f, fd[0]);
                nativeFrom[0] = step[1];
                i++; step = path.get(i);
            }
            // Server velocity: its simulation is one tick ahead of the client (next tick's velocity).
            final double serverVelocity = i + 1 < path.size() ? path.get(i + 1)[3] : 0;
            velocity(serverVelocity);
            final double to = step[1];
            final float before = fd[0];
            packet(step[0], to, land);
            event(fromX, from, step[0], to, land);
            final double packetFrom = Double.isNaN(nativeFrom[0]) ? from : nativeFrom[0];
            nativeFrom[0] = Double.NaN;
            if (!land) {
                if (fd[0] != before) fail(name + ": NCP wrote native distance on a glide move");
                if (to > packetFrom) fd[0] = 0f; else fd[0] += (float) (packetFrom - to);
            } else fd[0] += (float) (packetFrom - to);
            minDebt = Math.max(minDebt, d.noFallContactFallDebt);
            prevServerVelocity = serverVelocity;
            fromX = step[0]; from = to; i++;
        }
        assertEquals(name + ": no reversal debt on a legit pull-up", 0f, minDebt, 0f);
        // The native model without NoCheatPlus would have charged exactly this.
        final double damage = Math.max(0, NoFall.getDamage(fd[0]));
        assertEquals(name + ": landing damage = native (" + fd[0] + ")", nativeDamage(path, fast, grouped), damage, 0.0);
    }

    /** The same trajectory through the native model alone. */
    static double nativeDamage(List<double[]> path, boolean fast, boolean grouped) {
        float fd = 0f; double prevV = fast ? -1.2 : -0.4, from = 120;
        for (int i = 0; i < path.size(); i++) {
            if (prevV > -0.5) fd = Math.min(1f, fd);
            final double to = path.get(i)[1];
            if (to > from) fd = 0f; else fd += (float) (from - to);
            prevV = i + 1 < path.size() ? path.get(i + 1)[3] : 0;
            from = to;
        }
        return Math.max(0, NoFall.getDamage(fd));
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

    /**
     * REVIEW-CODE-7 P2 #3 driver: horizontal speed 6, vertical -1.2, pitch -80:
     * the vanilla next move is h 5.732843, vy -0.507644 (gain 0.69). With a
     * pending native limit, 12 tracked and native FD 1, NoFall.onGlidingMove
     * must forgive it (no contact debt), as native does.
     */
    @Test public void highSpeedPullUpIsNotAReversal() throws Exception {
        if (!name.equals("vanilla-landing")) return; // once per run
        String old = ServerVersion.getMinecraftVersion(); ServerVersion.setMinecraftVersion("26.2");
        try (var bridge = mockStatic(Bridge1_9.class); var b13 = slowFalling()) {
            setUp(bridge);
            final double[] next = glideTick(6.0, -1.2, -80.0);
            assertEquals(5.732843, next[0], 1e-6); assertEquals(-0.507644, next[1], 1e-6);
            // The previous move: one received packet, matched.
            pos[0] = 101.2; posX[0] = -6; tick[0] = 1; velocity(-1.2);
            packet(0, 100, false); move0(-6, 101.2, 0, 100);
            // Next tick: the limit is pending (as in the review: modeled native limit), FD 1, tracked 12.
            d.noFallGlideLimitPending = true; d.noFallFallDistance = 12f; d.noFallMaxY = 112; fd[0] = 1f; tick[0] = 2;
            packet(next[0], 100 + next[1], false);
            event(0, 100, next[0], 100 + next[1], false);
            assertEquals("forgiven like native", 0f, d.noFallContactFallDebt, 0f);
            assertTrue("limit applied", d.noFallFallDistance < 2f);
            // Same state, physically impossible: a gain beyond the bound for h 6 (vy +0.2).
            setUp(bridge);
            pos[0] = 101.2; posX[0] = -6; tick[0] = 1; velocity(-1.2);
            packet(0, 100, false); move0(-6, 101.2, 0, 100);
            d.noFallGlideLimitPending = true; d.noFallFallDistance = 12f; d.noFallMaxY = 112; fd[0] = 1f; tick[0] = 2;
            packet(5.7, 100.2, false);
            event(0, 100, 5.7, 100.2, false);
            assertEquals("impossible reversal keeps the dive", 12f, d.noFallContactFallDebt, 0.01f);
        } finally { ServerVersion.setMinecraftVersion(old); }
    }

    /**
     * Timing: a Bukkit move that spans two received packets (one had no event)
     * is not one tick of velocity. The next move cannot be judged against it:
     * read as one tick, -2.4 would make a legit -1.15 look like a 1.25 gain.
     */
    @Test public void aGroupedLastMoveIsNotAVelocity() throws Exception {
        if (!name.equals("vanilla-landing")) return; // once per run
        String old = ServerVersion.getMinecraftVersion(); ServerVersion.setMinecraftVersion("26.2");
        try (var bridge = mockStatic(Bridge1_9.class); var b13 = slowFalling()) {
            setUp(bridge);
            pos[0] = 103.6; posX[0] = 0; tick[0] = 1; velocity(-1.2);
            packet(0, 101.2, false); move0(0, 103.6, 0, 101.2);
            // Two client ticks, one Bukkit move (the first packet had no event).
            tick[0] = 3; velocity(-1.2);
            packet(0, 99.99, false); packet(0, 98.8, false);
            event(0, 101.2, 0, 98.8, false);
            assertEquals("two received packets", 2, d.noFallLastMovePackets);
            d.noFallGlideLimitPending = true; d.noFallFallDistance = 12f; d.noFallMaxY = 110.8; fd[0] = 1f; tick[0] = 4;
            packet(0, 97.65, false);
            event(0, 98.8, 0, 97.65, false);
            assertEquals("deferred to native accounting", 0f, d.noFallContactFallDebt, 0f);
        } finally { ServerVersion.setMinecraftVersion(old); }
    }

    /** A matched previous move ending at (toX, to); its own span is unknown (first move). */
    void move0(double fromX, double from, double toX, double to) throws Exception {
        event(fromX, from, toX, to, false);
        // Make that move a known one-packet move (as after any earlier matched move).
        d.noFallLastMovePackets = 1;
    }

    /** A gliding player's rejected impossible reversal keeps its descent (SurvivalFly set-back path);
     * an ordinary rejection and a rejected descent onto the pad (a collision) do not. */
    @Test public void rejectedReversalWhileGlidingKeepsSetBackDebtAndOrdinaryRejectionDoesNot() throws Exception {
        if (!name.equals("vanilla-landing")) return; // once per run
        String old = ServerVersion.getMinecraftVersion(); ServerVersion.setMinecraftVersion("26.2");
        try (var bridge = mockStatic(Bridge1_9.class); var b13 = slowFalling()) {
            for (String kind : new String[] {"reversal", "ordinary", "landing"}) {
                Player p = mock(Player.class); World w = mock(World.class);
                MovingData d = mock(MovingData.class, CALLS_REAL_METHODS);
                MoveTrace trace = mock(MoveTrace.class); TestClientNoFallSequences.field(d, MovingData.class, "playerMoves", trace);
                d.clearWindChargeImpulse();
                MovingConfig cc = mock(MovingConfig.class); IPlayerData pd = mock(IPlayerData.class);
                NetData net = netData();
                when(pd.getGenericInstance(MovingConfig.class)).thenReturn(cc);
                when(pd.getGenericInstance(NetData.class)).thenReturn(net);
                NoFall nf = mock(NoFall.class, CALLS_REAL_METHODS);
                bridge.when(() -> Bridge1_9.isGlidingWithElytra(p)).thenReturn(true);
                bridge.when(() -> Bridge1_9.getLevitationAmplifier(p)).thenReturn(Double.NEGATIVE_INFINITY);
                float[] fd = {1f};
                when(p.getFallDistance()).thenAnswer(c -> fd[0]);
                doAnswer(c -> { fd[0] = c.getArgument(0); return null; }).when(p).setFallDistance(anyFloat());
                when(p.getLocation()).thenReturn(new Location(w, 16, 81.15363618312385, 16));
                d.noFallFallDistance = 11.846f; d.noFallMaxY = 93;
                PlayerMoveData lastMove = new PlayerMoveData(); lastMove.toIsValid = true; lastMove.yDistance = -1.195;
                lastMove.from.setLocation("world", 16, 82.34870589450762, 16, 0, 0);
                lastMove.to.setLocation("world", 16, 81.15363618312385, 16, 0, 0);
                // The last accepted move: one received packet, matched by NoFall.check.
                net.addNoFallPacket(new DataPacketFlying(false, 16, 81.15363618312385, 16, 1));
                d.noFallMatchedPacketSequence = 1; d.noFallLastMovePackets = 1;
                d.noFallLastMoveX = 16; d.noFallLastMoveY = 81.15363618312385; d.noFallLastMoveZ = 16;
                final double toY = kind.equals("reversal") ? 81.33319999363422 : kind.equals("landing") ? 81.0 : 80.05;
                net.addNoFallPacket(new DataPacketFlying(kind.equals("landing"), 16, toY, 16, 2));
                PlayerMoveData rejected = new PlayerMoveData(); rejected.toIsValid = true;
                rejected.from.setLocation("world", 16, 81.15363618312385, 16, 0, 0);
                rejected.to.setLocation("world", 16, toY, 16, 0, 0);
                rejected.yDistance = toY - 81.15363618312385; rejected.to.onGround = kind.equals("landing");
                when(trace.getFirstPastMove()).thenReturn(lastMove); when(trace.getCurrentMove()).thenReturn(rejected);
                nf.checkDamage(p, 81.15363618312385, d, pd);
                if (kind.equals("reversal")) {
                    assertEquals("impossible reversal keeps the dive as set-back debt", 11.846f, d.noFallSetBackFallDebt, 0.01);
                } else {
                    assertEquals(kind + ": a gliding rejection keeps native glide accounting", 0f, d.noFallSetBackFallDebt, 0);
                    assertEquals(1f, fd[0], 0);
                }
            }
        } finally { ServerVersion.setMinecraftVersion(old); }
    }
}
