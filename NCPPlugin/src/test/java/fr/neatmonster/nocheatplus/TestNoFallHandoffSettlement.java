package fr.neatmonster.nocheatplus;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.Plugin;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import fr.neatmonster.nocheatplus.checks.CheckListener;
import fr.neatmonster.nocheatplus.checks.CheckType;
import fr.neatmonster.nocheatplus.checks.moving.*;
import fr.neatmonster.nocheatplus.checks.moving.model.*;
import fr.neatmonster.nocheatplus.checks.moving.player.FallStateSnapshot;
import fr.neatmonster.nocheatplus.checks.moving.player.NoFall;
import fr.neatmonster.nocheatplus.checks.moving.util.*;
import fr.neatmonster.nocheatplus.compat.*;
import fr.neatmonster.nocheatplus.compat.versions.ServerVersion;
import fr.neatmonster.nocheatplus.components.registry.event.IGenericInstanceHandle;
import fr.neatmonster.nocheatplus.players.*;
import fr.neatmonster.nocheatplus.utilities.location.PlayerLocation;

/**
 * REVIEW-CODE-5 P1 #1 and #2: a restored landing (NoFall.adjustFallDistance
 * "handoff") is a request, not owed descent. Real NoFall check, real
 * MovingListener departure/probe/FALL handler/join/leave code and the real
 * worker checkpoint; native movement is a small explicit model:
 * descent adds to fall distance, a ground packet lands (FALL event unless
 * damage immunity skips it, LivingEntity.hurtServer), an upward packet resets
 * fall distance (ServerGamePacketListenerImpl movedUpwards).
 * Gliding descent from 92.5626 onto the 81.0 pad: vanilla 8.
 */
@RunWith(Parameterized.class)
public class TestNoFallHandoffSettlement {
    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> cases() {
        return Arrays.asList(new Object[][] {
            // P1 #1: landing processed by native, then an immediate jump, both task orders.
            {"immunity/probe-then-jump", 0}, {"immunity/jump-then-probe", 0},
            // No event and no reason for a silent landing: not processed, still owed.
            {"silent-without-cooldown/jump-then-probe", 8},
            // Reverted, then our own untracked-move correction puts the player back on the contact
            // before any settlement (lab f6C-lat-pj r4, 80+-40 ms relay): native zero there is no proof.
            {"reverted/setback-to-contact", 8},
            {"fall-event/jump-then-probe", 8}, {"cancelled-event/jump-then-probe", 0},
            {"reverted/jump-then-probe", 8}, {"reverted/probe-then-land", 8},
            {"moved-contact/jump-then-probe", 8}, {"teleported/jump-then-probe", 0},
            // P1 #2: the pending confirmation through glide transitions.
            {"glide-deploy/reverted", 8}, {"glide-limit/reverted", 8}, {"glide-stop/reverted", 8},
            {"glide-deploy/consumed", 0},
            // P1 #2: through worker departure (probe retired) and a fresh receiver.
            {"transfer/reverted", 8}, {"transfer/reverted-fd1", 8}, {"transfer/consumed", 0},
            {"transfer-unsettled/reverted", 8}, {"transfer-unsettled/consumed-drift", 0},
            {"transfer-unsettled/repeated-reverted", 8}, {"transfer-unsettled/repeated-consumed", 0},
            {"transfer-unsettled/glide-reverted", 8},
        });
    }

    static final double PAD = 81.0, ARC = 81.15363618412385, TOP = 92.5626270431092;
    final String name; final int vanilla;
    public TestNoFallHandoffSettlement(String n, int v) { name = n; vanilla = v; }

    Player p = mock(Player.class); IPlayerData pd = mock(IPlayerData.class); MovingConfig cc = mock(MovingConfig.class);
    MovingData d = mock(MovingData.class, CALLS_REAL_METHODS); MoveTrace trace = mock(MoveTrace.class);
    NoFall nf = mock(NoFall.class, CALLS_REAL_METHODS); MCAccess access = mock(MCAccess.class);
    MovingListener listener = mock(MovingListener.class, CALLS_REAL_METHODS);
    PlayerLocation support = mock(PlayerLocation.class); World world = mock(World.class);
    Map<NamespacedKey, Object> pdc = new HashMap<>();
    float[] fd = {0}; double[] x = {16.5}, y = {ARC}; boolean[] ground = {false}, owned = {true}, gliding = {false};
    int[] cooldown = {0}; double[] lastHurt = {0};
    int[] tick = {100}; double damage = 0; int dispatched = 0;
    AtomicReference<Consumer<Object>> task = new AtomicReference<>(); AtomicReference<Runnable> retired = new AtomicReference<>();
    Method queue, finish, join;

    void setUp() throws Exception {
        TestClientNoFallSequences.field(d, MovingData.class, "playerMoves", trace);
        TestClientNoFallSequences.field(d, MovingData.class, "vDistAcc", new fr.neatmonster.nocheatplus.utilities.ds.count.ActionAccumulator(3, 3));
        when(nf.isEnabled(p, pd)).thenReturn(true); when(pd.isCheckActive(CheckType.MOVING, p)).thenReturn(true);
        when(pd.getGenericInstance(MovingData.class)).thenAnswer(c -> d); when(pd.getGenericInstance(MovingConfig.class)).thenReturn(cc);
        when(p.isOnline()).thenReturn(true);
        when(p.getFallDistance()).thenAnswer(c -> fd[0]);
        doAnswer(c -> { fd[0] = c.getArgument(0); return null; }).when(p).setFallDistance(anyFloat());
        when(p.getLocation()).thenAnswer(c -> new Location(world, x[0], y[0], 16.5));
        when(p.getVelocity()).thenAnswer(c -> new org.bukkit.util.Vector(0, gliding[0] ? -0.155 : -0.0784, 0));
        when(p.getTicksLived()).thenAnswer(c -> tick[0]);
        when(p.getNoDamageTicks()).thenAnswer(c -> cooldown[0]);
        when(p.getMaximumNoDamageTicks()).thenReturn(20);
        when(p.getLastDamage()).thenAnswer(c -> lastHurt[0]);
        when(p.getBoundingBox()).thenAnswer(c -> new org.bukkit.util.BoundingBox(x[0] - 0.3, y[0], 16.2, x[0] + 0.3, y[0] + 1.8, 16.8));
        when(world.getKey()).thenReturn(NamespacedKey.minecraft("overworld"));
        PersistentDataContainer container = mock(PersistentDataContainer.class);
        when(p.getPersistentDataContainer()).thenReturn(container);
        doAnswer(c -> { Object v = c.getArgument(2); pdc.put(c.getArgument(0), v instanceof long[] ? ((long[]) v).clone() : v); return null; })
            .when(container).set(any(), any(), any());
        when(container.get(any(), any())).thenAnswer(c -> pdc.get(c.getArgument(0)));
        when(container.has(any(), any())).thenAnswer(c -> pdc.containsKey(c.getArgument(0)));
        doAnswer(c -> { pdc.remove(c.getArgument(0)); return null; }).when(container).remove(any());
        IGenericInstanceHandle h = mock(IGenericInstanceHandle.class); when(h.getHandle()).thenReturn(access);
        TestClientNoFallSequences.field(nf, fr.neatmonster.nocheatplus.checks.Check.class, "mcAccess", h);
        PlayerMoveInfo info = mock(PlayerMoveInfo.class); TestClientNoFallSequences.field(info, MoveInfo.class, "from", support);
        when(support.isOnGround(anyDouble())).thenAnswer(c -> ground[0]);
        AuxMoving aux = mock(AuxMoving.class); when(aux.usePlayerMoveInfo()).thenReturn(info);
        TestClientNoFallSequences.field(listener, MovingListener.class, "aux", aux);
        TestClientNoFallSequences.field(listener, MovingListener.class, "noFall", nf);
        TestClientNoFallSequences.field(listener, CheckListener.class, "mcAccess", h);
        when(access.dispatchNativeLanding(p)).thenAnswer(c -> {
            dispatched++; damage += Math.max(0, NoFall.getDamage(fd[0])); fd[0] = 0; return true; });
        queue = MovingListener.class.getDeclaredMethod("queueNativeLanding", Player.class, MovingData.class, MovingConfig.class, IPlayerData.class);
        queue.setAccessible(true);
        finish = MovingListener.class.getDeclaredMethod("finishNativeLandingBeforeDeparture", Player.class, PlayerLocation.class, MovingData.class, MovingConfig.class, IPlayerData.class);
        finish.setAccessible(true);
        join = MovingListener.class.getDeclaredMethod("restoreNoFallOnJoin", Player.class, MovingData.class, IPlayerData.class);
        join.setAccessible(true);
    }

    /** One accepted Bukkit move as MovingListener runs it (departure, check,
     * probe queue, MONITOR checkpoint), then native processing of that packet. */
    void move(double from, double to, boolean fromGround, boolean packetGround, String landing) throws Exception {
        PlayerMoveData m = new PlayerMoveData(); m.from.setLocation("world", x[0], from, 16.5, 0, 0);
        m.to.setLocation("world", x[0], to, 16.5, 0, 0); m.toIsValid = true; m.yDistance = to - from;
        m.from.onGround = fromGround; m.to.onGround = packetGround;
        when(trace.getCurrentMove()).thenReturn(m);
        PlayerLocation a = mock(PlayerLocation.class), b = mock(PlayerLocation.class);
        when(a.isOnGround(anyDouble())).thenReturn(fromGround); when(b.isOnGround(anyDouble())).thenReturn(packetGround);
        when(a.getY()).thenReturn(from); when(b.getY()).thenReturn(to);
        // During PlayerMoveEvent the server position is still the packet's start.
        y[0] = from; ground[0] = fromGround;
        finish.invoke(listener, p, a, d, cc, pd);
        nf.check(p, a, b, Double.NEGATIVE_INFINITY, d, cc, pd);
        queue.invoke(listener, p, d, cc, pd);
        FallStateSnapshot.save(p, d, new Location(world, x[0], to, 16.5));
        nativeMove(from, to, packetGround, landing);
    }

    void nativeMove(double from, double to, boolean packetGround, String landing) throws Exception {
        y[0] = to; ground[0] = packetGround;
        if (to < from) fd[0] += (float) (from - to);
        if (packetGround) {
            double raw = Math.max(0, NoFall.getDamage(fd[0]));
            if (raw > 0 && !"immune".equals(landing)) {
                if (!fallEvent(raw, "cancelled".equals(landing))) damage += raw;
            }
            fd[0] = 0;
        }
        if (to > from) fd[0] = 0;
    }

    /** The FALL handler as production calls it, at the current position. */
    boolean fallEvent(double raw, boolean cancelledBefore) throws Exception {
        try (var manager = mockStatic(DataManager.class)) {
            manager.when(() -> DataManager.getPlayerData(p)).thenReturn(pd);
            EntityDamageEvent event = new EntityDamageEvent(p, DamageCause.FALL, raw);
            if (cancelledBefore) event.setCancelled(true);
            Method check = MovingListener.class.getDeclaredMethod("checkFallDamageEvent", Player.class, EntityDamageEvent.class);
            check.setAccessible(true); check.invoke(listener, p, event);
            return event.isCancelled();
        }
    }

    void probe() { Consumer<Object> t = task.get(); if (t != null) t.accept(null); }

    /** Worker departure as MovingListener.playerLeaves runs it; the owner probe is retired. */
    void leave() throws Exception {
        if (retired.get() != null) retired.get().run();
        try {
            Method settle = MovingListener.class.getDeclaredMethod("settleNoFallHandoffOnLeave", Player.class, MovingData.class);
            settle.setAccessible(true); settle.invoke(listener, p, d);
        } catch (NoSuchMethodException before) { /* earlier code: no settlement */ }
        nf.onLeave(p, d, pd);
    }

    /** Fresh receiver: new MovingData, same native entity data (fall distance, PDC). */
    void arrive(double drift) throws Exception {
        d = mock(MovingData.class, CALLS_REAL_METHODS); trace = mock(MoveTrace.class);
        TestClientNoFallSequences.field(d, MovingData.class, "playerMoves", trace);
        TestClientNoFallSequences.field(d, MovingData.class, "vDistAcc", new fr.neatmonster.nocheatplus.utilities.ds.count.ActionAccumulator(3, 3));
        task.set(null); retired.set(null);
        x[0] += drift;
        join.invoke(listener, p, d, pd);
    }

    @Test public void restoredLandingIsSettledOnceAgainstItsOwnContact() throws Exception {
        String old = ServerVersion.getMinecraftVersion(); ServerVersion.setMinecraftVersion("26.2");
        try (var bukkit = mockStatic(Bukkit.class); var folia = mockStatic(Folia.class);
             var movement = mockStatic(MovingUtil.class); var bridge = mockStatic(Bridge1_9.class)) {
            setUp();
            Plugin plugin = mock(Plugin.class); var plugins = mock(org.bukkit.plugin.PluginManager.class);
            bukkit.when(Bukkit::getServer).thenReturn(mock(Server.class)); bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
            when(plugins.getPlugin("NoCheatPlus")).thenReturn(plugin);
            folia.when(() -> Folia.runSyncDelayedTaskForEntity(eq(p), eq(plugin), any(), any(), eq(1L)))
                .thenAnswer(c -> { task.set(c.getArgument(2)); retired.set(c.getArgument(3)); return new Object(); });
            folia.when(() -> Folia.isOwnedByCurrentRegion(p)).thenAnswer(c -> owned[0]);
            folia.when(Folia::isFoliaServer).thenReturn(name.startsWith("transfer"));
            movement.when(() -> MovingUtil.shouldCheckSurvivalFly(any(), any(), any(), any(), any(), any())).thenReturn(true);
            bridge.when(() -> Bridge1_9.isGlidingWithElytra(p)).thenAnswer(c -> gliding[0]);

            // Gliding descent; a spoofed ground state erased native distance.
            d.noFallMaxY = TOP; d.noFallFallDistance = 11.408991f; fd[0] = 0f; y[0] = ARC;
            if (name.startsWith("immunity") || name.contains("consumed")) {
                // Hit just before (e.g. by 9): LivingEntity.hurtServer skips the FALL event of an 8.
                cooldown[0] = 19; lastHurt[0] = 9;
            }
            final boolean reverted = name.contains("reverted") || name.startsWith("moved-contact");
            final String landing = name.startsWith("fall-event") ? "event" : name.startsWith("cancelled-event") ? "cancelled" : "immune";
            // silent-without-cooldown: modelled as 'immune' (no event, FD reset) although nothing can make it silent.
            // The landing packet: NCP restores the accepted distance for native processing.
            PlayerMoveData m = new PlayerMoveData(); m.from.setLocation("world", x[0], ARC, 16.5, 0, 0);
            m.to.setLocation("world", x[0], PAD, 16.5, 0, 0); m.toIsValid = true; m.yDistance = PAD - ARC; m.to.onGround = true;
            when(trace.getCurrentMove()).thenReturn(m);
            PlayerLocation a = mock(PlayerLocation.class), b = mock(PlayerLocation.class);
            when(b.isOnGround(anyDouble())).thenReturn(true); when(a.getY()).thenReturn(ARC);
            finish.invoke(listener, p, a, d, cc, pd);
            nf.check(p, a, b, Double.NEGATIVE_INFINITY, d, cc, pd);
            assertEquals("The landing packet gets the accepted distance", 11.4089f, fd[0], 1e-3);
            assertTrue("A restored landing awaits confirmation", d.noFallHandoffDistance > 11.5f);
            queue.invoke(listener, p, d, cc, pd);
            FallStateSnapshot.save(p, d, new Location(world, x[0], PAD, 16.5));
            if (reverted) {
                // Native reverted the landing packet (untracked position back on
                // the arc); the next tick's glide limit capped native distance.
                y[0] = ARC; ground[0] = false; fd[0] = name.endsWith("fd1") ? 1f : 0f;
                if (name.startsWith("moved-contact")) { y[0] = PAD; x[0] += 2.0; ground[0] = true; fd[0] = 0f; }
                if (name.endsWith("setback-to-contact")) { y[0] = PAD; ground[0] = true; fd[0] = 0f; }
            } else {
                y[0] = ARC; nativeMove(ARC, PAD, true, landing);
                if (name.startsWith("teleported")) {
                    // A plugin moved the player before the probe: an ordinary teleport ends the fall.
                    x[0] += 2.0; NoFall.afterTeleport(p, d, p.getLocation(), 0f, true);
                }
            }

            if (name.startsWith("glide-") || name.endsWith("glide-reverted")) {
                // A direct glide transition before anything settled the confirmation.
                gliding[0] = true; tick[0]++;
                if (name.contains("limit")) {
                    NoFall.onGlidingMove(p, d, -0.1); tick[0]++; d.noFallGlideLimitPending = true;
                    NoFall.onGlidingMove(p, d, -0.1);
                } else NoFall.onGlidingMove(p, d, -0.1);
                if (name.contains("stop")) { gliding[0] = false; NoFall.stopGlide(p, d); }
                assertTrue(name + ": a glide transition does not settle the landing", d.noFallHandoffDistance > 11.5f);
                FallStateSnapshot.save(p, d, p.getLocation()); // MONITOR checkpoint of the glide move.
                if (name.startsWith("glide-")) gliding[0] = false;
            }

            if (name.startsWith("transfer")) {
                boolean unsettled = name.startsWith("transfer-unsettled");
                if (unsettled) owned[0] = false; // Departure cannot settle; the checkpoint carries it.
                leave();
                owned[0] = true;
                if (!reverted) { /* native consumed the landing on the source: fd 0 on the pad */ }
                arrive(name.contains("drift") ? 0.001 : 0.0);
                if (name.contains("repeated")) {
                    owned[0] = false; leave(); owned[0] = true; arrive(0.001);
                }
                if (unsettled) assertTrue(name + ": the receiver gets the pending confirmation",
                        d.noFallHandoffDistance > 11.5f || d.noFallContactFallDebt > 11.5f);
                probe(); // The receiver's own owner probe.
                if (reverted) {
                    // The player falls back from the arc onto the pad on the receiver.
                    gliding[0] = false;
                    move(ARC, PAD, false, true, "event");
                } else {
                    move(PAD, 81.42, true, false, null); move(81.42, PAD, false, true, "event");
                }
                probe(); probe();
            } else if (name.endsWith("probe-then-jump")) {
                probe();
                move(PAD, 81.42, true, false, null); probe();
                move(81.42, PAD, false, true, "event"); probe(); probe();
            } else if (name.endsWith("jump-then-probe")) {
                // The next packet is processed before the owner probe (packet burst).
                if (reverted && !name.startsWith("moved-contact")) {
                    // Still on the arc: that packet descends.
                    move(ARC, 81.08, false, false, null); probe();
                    move(81.08, PAD, false, true, "event"); probe(); probe();
                } else {
                    move(y[0], y[0] + 0.42, true, false, null); probe();
                    move(y[0], y[0] - 0.42, false, true, "event"); probe(); probe();
                }
            } else if (name.endsWith("setback-to-contact")) {
                probe(); // On the contact, native zero, no event: still owed and charged here.
                move(PAD, 81.42, true, false, null); move(81.42, PAD, false, true, "event"); probe(); probe();
            } else if (name.endsWith("probe-then-land")) {
                probe();
                move(ARC, PAD, false, true, "event"); probe(); probe();
            } else {
                // Glide cases: the owner probe, then the real landing on the pad.
                probe();
                if (reverted) move(ARC, PAD, false, true, "event");
                else { move(PAD, 81.42, true, false, null); move(81.42, PAD, false, true, "event"); }
                probe(); probe();
            }
            assertEquals(name + ": damage (vanilla " + vanilla + ")", vanilla, damage, 0);
            assertEquals(name + ": nothing left owed", 0f, Math.max(d.noFallHandoffDistance, NoFall.retainedNativeResetDebt(d)), 0f);
            assertTrue(name + ": at most one NCP-dispatched landing", dispatched <= 1);
        } finally { ServerVersion.setMinecraftVersion(old); }
    }
}
