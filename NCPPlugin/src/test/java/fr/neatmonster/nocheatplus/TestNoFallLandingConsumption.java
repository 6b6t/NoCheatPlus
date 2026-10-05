package fr.neatmonster.nocheatplus;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.junit.Test;
import fr.neatmonster.nocheatplus.checks.moving.*;
import fr.neatmonster.nocheatplus.checks.moving.player.NoFall;
import fr.neatmonster.nocheatplus.compat.*;
import fr.neatmonster.nocheatplus.players.IPlayerData;

public class TestNoFallLandingConsumption {
    private static class Fixture {
        final MCAccess access = mock(MCAccess.class);
        final Player player = mock(Player.class);
        final IPlayerData pData = mock(IPlayerData.class);
        final MovingData data;
        final MovingConfig config;
        final NoFall check;
        final Method landing;
        Fixture() throws Exception {
            when(access.dealFallDamageFiresAnEvent()).thenReturn(AlmostBoolean.YES);
            config = mock(MovingConfig.class);
            data = mock(MovingData.class, CALLS_REAL_METHODS);
            var moves = mock(fr.neatmonster.nocheatplus.checks.moving.model.MoveTrace.class);
            var traceField = MovingData.class.getField("playerMoves");
            traceField.setAccessible(true); traceField.set(data, moves);
            World world = mock(World.class); Block block = mock(Block.class);
            when(block.getType()).thenReturn(Material.AIR);
            when(world.getBlockAt(any(Location.class))).thenReturn(block);
            Location location = new Location(world, 16, 81, 16);
            when(player.getLocation()).thenReturn(location);
            when(player.getLocation(any(Location.class))).thenReturn(location);
            when(player.getNoDamageTicks()).thenReturn(100);
            check = mock(NoFall.class, CALLS_REAL_METHODS);
            try {
                var scratch = NoFall.class.getDeclaredField("useLoc2");
                scratch.setAccessible(true); scratch.set(check, location);
            } catch (NoSuchFieldException removed) { /* Baseline-first regression run only. */ }
            var handle = mock(fr.neatmonster.nocheatplus.components.registry.event.IGenericInstanceHandle.class);
            when(handle.getHandle()).thenReturn(access);
            var accessField = fr.neatmonster.nocheatplus.checks.Check.class.getDeclaredField("mcAccess");
            accessField.setAccessible(true); accessField.set(check, handle);
            landing = NoFall.class.getDeclaredMethod("handleOnGround", Player.class, double.class,
                    double.class, boolean.class, MovingData.class, MovingConfig.class, IPlayerData.class);
            landing.setAccessible(true);
        }
        void fall(float distance) throws Exception {
            data.noFallFallDistance = distance; data.noFallMaxY = 81 + distance;
            landing.invoke(check, player, 81.0, Double.NEGATIVE_INFINITY, true, data, config, pData);
        }
    }
    @Test public void nativeLandingAddsTheCurrentPacketDescentExactlyOnce() throws Exception {
        String previous = fr.neatmonster.nocheatplus.compat.versions.ServerVersion.getMinecraftVersion();
        try {
            fr.neatmonster.nocheatplus.compat.versions.ServerVersion.setMinecraftVersion("26.2");
            Fixture f = new Fixture();
            var move = new fr.neatmonster.nocheatplus.checks.moving.model.PlayerMoveData();
            move.toIsValid = true; move.yDistance = -1.19347388999101;
            when(f.data.playerMoves.getCurrentMove()).thenReturn(move);
            f.data.noFallFallDistance = 12; f.data.noFallMaxY = 93;
            Method adjust = NoFall.class.getDeclaredMethod("adjustFallDistance", Player.class,
                    double.class, boolean.class, MovingData.class, MovingConfig.class);
            adjust.setAccessible(true);
            adjust.invoke(f.check, f.player, 81.0, true, f.data, f.config);
            var distance = org.mockito.ArgumentCaptor.forClass(Float.class);
            verify(f.player).setFallDistance(distance.capture());
            assertEquals(12, distance.getValue() - move.yDistance, 1.0e-6);
            assertEquals(0, f.data.noFallFallDistance, 0);
        } finally { fr.neatmonster.nocheatplus.compat.versions.ServerVersion.setMinecraftVersion(previous); }
    }
    @Test public void shortFallsRestoreEvenWhenThePrePacketDistanceIsBelowThree() throws Exception {
        String previous = fr.neatmonster.nocheatplus.compat.versions.ServerVersion.getMinecraftVersion();
        try {
            fr.neatmonster.nocheatplus.compat.versions.ServerVersion.setMinecraftVersion("26.2");
            Fixture f = new Fixture();
            var move = new fr.neatmonster.nocheatplus.checks.moving.model.PlayerMoveData();
            move.toIsValid = true; move.yDistance = -1.047;
            when(f.data.playerMoves.getCurrentMove()).thenReturn(move);
            f.data.noFallFallDistance = 4; f.data.noFallMaxY = 85;
            Method adjust = NoFall.class.getDeclaredMethod("adjustFallDistance", Player.class,
                    double.class, boolean.class, MovingData.class, MovingConfig.class);
            adjust.setAccessible(true);
            adjust.invoke(f.check, f.player, 81.0, true, f.data, f.config);
            var distance = org.mockito.ArgumentCaptor.forClass(Float.class);
            verify(f.player).setFallDistance(distance.capture());
            assertEquals(4, distance.getValue() - move.yDistance, 1.0e-6);
        } finally { fr.neatmonster.nocheatplus.compat.versions.ServerVersion.setMinecraftVersion(previous); }
    }
    @Test public void immunityWithoutDamageEventStillConsumesEachLanding() throws Exception {
        Fixture f = new Fixture();
        f.fall(12); assertEquals(0, f.data.noFallFallDistance, 0);
        f.fall(8); assertEquals(0, f.data.noFallFallDistance, 0);
        var order = inOrder(f.access);
        order.verify(f.access).dealFallDamage(f.player, 9);
        order.verify(f.access).dealFallDamage(f.player, 5);
        verify(f.player, never()).getNoDamageTicks();
    }
    @Test public void failedNativeDamageCannotLeaveAReplayableLanding() throws Exception {
        Fixture f = new Fixture();
        doThrow(new IllegalStateException("native failure")).when(f.access).dealFallDamage(f.player, 9);
        try { f.fall(12); fail("expected exception"); }
        catch (InvocationTargetException expected) { assertTrue(expected.getCause() instanceof IllegalStateException); }
        assertEquals(0, f.data.noFallFallDistance, 0);
    }
    @Test public void reentrantCallbackStateSurvivesTheConsumedLanding() throws Exception {
        Fixture f = new Fixture();
        doAnswer(call -> { f.data.noFallFallDistance = 2; return null; })
                .when(f.access).dealFallDamage(f.player, 9);
        f.fall(12); assertEquals(2, f.data.noFallFallDistance, 0);
    }
}
