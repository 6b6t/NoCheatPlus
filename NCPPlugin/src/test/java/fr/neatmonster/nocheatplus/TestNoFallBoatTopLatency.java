package fr.neatmonster.nocheatplus;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockito.Answers;
import fr.neatmonster.nocheatplus.checks.CheckType;
import fr.neatmonster.nocheatplus.checks.moving.*;
import fr.neatmonster.nocheatplus.checks.moving.model.*;
import fr.neatmonster.nocheatplus.checks.moving.player.NoFall;
import fr.neatmonster.nocheatplus.checks.moving.util.*;
import fr.neatmonster.nocheatplus.compat.Folia;
import fr.neatmonster.nocheatplus.compat.MCAccess;
import fr.neatmonster.nocheatplus.compat.versions.ServerVersion;
import fr.neatmonster.nocheatplus.components.registry.event.IGenericInstanceHandle;
import fr.neatmonster.nocheatplus.players.*;
import fr.neatmonster.nocheatplus.utilities.ds.count.ActionAccumulator;
import fr.neatmonster.nocheatplus.utilities.location.PlayerLocation;

/**
 * FIX-6 still open #1: an airborne boat dismount under an 80 +- 40 ms relay.
 * The client lands on the falling boat it displays; vanilla accepts the ground
 * packet there (f6D-lat-mount8-en mount-air-0: FALL at 83.339, FD 6.12, raw 3).
 * The server boat already settled at 81; its top passed 83.339 eight boat ticks
 * before the FALL (ticks 20-21 of the measured tops), beyond the vanilla display
 * window of updateInterval + 3 = 6 ticks. The handler cancelled the FALL as
 * "still airborne" and the next contact charged 6 instead of 3. The support test
 * must add the client's round trip; without latency, far older tops, a top
 * beside the feet, or a claim above every recent top are still airborne.
 */
@RunWith(Parameterized.class)
public class TestNoFallBoatTopLatency {
    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> cases() {
        return Arrays.asList(new Object[][] {
            // name, ping ms, FALL boat tick, feet x, feet y, native FALL stands
            {"ping160-top-8-ticks-old", 160, 28, 16.5, 83.33915494615299, true},
            {"ping160-exact-top-of-tick-21", 160, 29, 16.5, 82.65915494615299, true},
            {"ping40-top-8-ticks-old", 40, 28, 16.5, 83.33915494615299, true},
            {"no-ping-same-landing", 0, 28, 16.5, 83.33915494615299, false},
            {"ping160-top-14-ticks-old", 160, 34, 16.5, 83.33915494615299, false},
            {"ping160-beside-the-boat", 160, 28, 17.6, 83.33915494615299, false},
            {"ping160-above-every-recent-top", 160, 28, 16.5, 88.5, false},
        });
    }

    // Measured native boat tops (f6D-lat-mount8-en mount-air-0), boat ticks 16-23; settled from 23.
    static final double[] TOPS = {85.85915495, 85.29915495, 84.69915495, 84.05915495, 83.37915495, 82.65915495,
        81.89915495, 81.5625};
    static double top(int tick) { return tick < 16 ? 86.37915495 : tick >= 23 ? 81.5625 : TOPS[tick - 16]; }

    /** NativeLandingAccess semantics: consecutive native boxes within updateInterval + 3 (+ latency) ticks. */
    static boolean swept(int now, int latencyTicks, double minX, double feetY, double maxX) {
        final int search = 3 + 3 + Math.max(0, Math.min(20, latencyTicks));
        for (int t = now - search + 1; t <= now; t++) {
            double old = top(t - 1), cur = top(t);
            if (old - cur <= 0.00001 || feetY < cur - 0.00001 || feetY > old + 0.00001) continue;
            if (17.1875 > minX && 15.8125 < maxX) return true;
        }
        return false;
    }

    private final String name; private final int ping, tick; private final double x, y; private final boolean stands;
    public TestNoFallBoatTopLatency(String name, int ping, int tick, double x, double y, boolean stands) {
        this.name = name; this.ping = ping; this.tick = tick; this.x = x; this.y = y; this.stands = stands;
    }

    private static void field(Object target, Class<?> owner, String name, Object value) throws Exception {
        Field f = owner.getDeclaredField(name); f.setAccessible(true); f.set(target, value);
    }

    @Test public void nativeLandingOnTheDisplayedFallingBoatStandsUnderPing() throws Exception {
        String previous = ServerVersion.getMinecraftVersion();
        try (var manager = mockStatic(DataManager.class); var movement = mockStatic(MovingUtil.class);
             var folia = mockStatic(Folia.class); var bukkit = mockStatic(org.bukkit.Bukkit.class)) {
            ServerVersion.setMinecraftVersion("26.2");
            Player player = mock(Player.class); World world = mock(World.class);
            when(player.getLocation()).thenReturn(new Location(world, x, y, 16.5));
            when(player.getFallDistance()).thenReturn(6.12f);
            when(player.getPing()).thenReturn(ping);
            when(player.getBoundingBox()).thenReturn(new org.bukkit.util.BoundingBox(x - 0.3, y, 16.2, x + 0.3, y + 1.8, 16.8));
            when(player.getUniqueId()).thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000002"));
            org.bukkit.entity.Boat boat = mock(org.bukkit.entity.Boat.class);
            when(boat.isValid()).thenReturn(true);
            when(boat.getUniqueId()).thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000001"));
            // The server boat has settled on the pad.
            when(boat.getBoundingBox()).thenReturn(new org.bukkit.util.BoundingBox(15.8125, 81, 15.8125, 17.1875, 81.5625, 17.1875));
            folia.when(() -> Folia.isOwnedByCurrentRegion(boat)).thenReturn(true);
            when(player.getNearbyEntities(1.5, 8, 1.5)).thenReturn(List.of(boat));

            IPlayerData pData = mock(IPlayerData.class);
            MovingData data = mock(MovingData.class, CALLS_REAL_METHODS);
            field(data, MovingData.class, "playerMoves", mock(MoveTrace.class));
            field(data, MovingData.class, "vDistAcc", new ActionAccumulator(3, 3));
            data.noFallMaxY = 89.45915494615299; data.noFallFallDistance = 6.12f;
            MovingConfig config = mock(MovingConfig.class);
            manager.when(() -> DataManager.getPlayerData(player)).thenReturn(pData);
            when(pData.getGenericInstance(MovingData.class)).thenReturn(data);
            when(pData.getGenericInstance(MovingConfig.class)).thenReturn(config);
            when(pData.isCheckActive(CheckType.MOVING, player)).thenReturn(true);
            PlayerMoveInfo info = mock(PlayerMoveInfo.class);
            PlayerLocation support = mock(PlayerLocation.class);
            field(info, MoveInfo.class, "from", support);
            movement.when(() -> MovingUtil.shouldCheckSurvivalFly(player, support, null, data, config, pData)).thenReturn(true);
            AuxMoving aux = mock(AuxMoving.class); when(aux.usePlayerMoveInfo()).thenReturn(info);
            NoFall noFall = mock(NoFall.class); when(noFall.isEnabled(player, pData)).thenReturn(true);
            MovingListener listener = mock(MovingListener.class, CALLS_REAL_METHODS);
            field(listener, MovingListener.class, "aux", aux);
            field(listener, MovingListener.class, "noFall", noFall);
            // Either overload: the plain one (no latency) or the one with the client's latency.
            int[] latencies = {-1};
            MCAccess access = mock(MCAccess.class, invocation -> {
                String m = invocation.getMethod().getName();
                if (m.equals("canCollideWith")) return invocation.getArgument(1) == boat;
                if (m.equals("hasSweptBoatSupport")) {
                    Object[] a = invocation.getArguments();
                    int latency = a.length == 7 ? (Integer) a[6] : 0;
                    latencies[0] = Math.max(latencies[0], latency);
                    return a[0] == boat && swept(tick, latency, (Double) a[1], (Double) a[2], (Double) a[4]);
                }
                return Answers.RETURNS_DEFAULTS.answer(invocation);
            });
            IGenericInstanceHandle handle = mock(IGenericInstanceHandle.class); when(handle.getHandle()).thenReturn(access);
            field(listener, fr.neatmonster.nocheatplus.checks.CheckListener.class, "mcAccess", handle);

            EntityDamageEvent damage = new EntityDamageEvent(player, DamageCause.FALL, 3.0);
            Method check = MovingListener.class.getDeclaredMethod("checkFallDamageEvent", Player.class, EntityDamageEvent.class);
            check.setAccessible(true); check.invoke(listener, player, damage);

            assertEquals(name + ": native FALL on the displayed boat top " + (stands ? "stands" : "is still airborne"),
                    !stands, damage.isCancelled());
            assertEquals("native damage itself is never rewritten", 3.0, damage.getDamage(), 0);
            if (stands) {
                assertEquals("the landing consumed the descent", 0f, data.noFallFallDistance, 0);
                assertEquals(0f, data.noFallCancelledAirFallDebt, 0);
            } else {
                assertEquals("an unproven contact keeps the descent", 6.12f, data.noFallCancelledAirFallDebt, 1e-5);
            }
            verify(player, never()).setFallDistance(anyFloat());
            verify(aux).returnPlayerMoveInfo(info);
        } finally { ServerVersion.setMinecraftVersion(previous); }
    }
}
