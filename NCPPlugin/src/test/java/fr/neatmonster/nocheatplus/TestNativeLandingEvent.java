package fr.neatmonster.nocheatplus;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.Material;
import org.bukkit.block.Block;
import fr.neatmonster.nocheatplus.compat.MCAccess;
import fr.neatmonster.nocheatplus.compat.AlmostBoolean;
import fr.neatmonster.nocheatplus.components.registry.event.IGenericInstanceHandle;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.junit.Test;
import fr.neatmonster.nocheatplus.checks.CheckType;
import fr.neatmonster.nocheatplus.checks.moving.*;
import fr.neatmonster.nocheatplus.checks.moving.model.*;
import fr.neatmonster.nocheatplus.checks.moving.player.NoFall;
import fr.neatmonster.nocheatplus.checks.moving.util.*;
import fr.neatmonster.nocheatplus.compat.Folia;
import fr.neatmonster.nocheatplus.compat.versions.ServerVersion;
import fr.neatmonster.nocheatplus.players.*;
import fr.neatmonster.nocheatplus.utilities.location.PlayerLocation;
import fr.neatmonster.nocheatplus.utilities.ds.count.ActionAccumulator;

public class TestNativeLandingEvent {
    private static void field(Object target, Class<?> owner, String name, Object value) throws Exception {
        Field f = owner.getDeclaredField(name); f.setAccessible(true); f.set(target, value);
    }
    private void event(boolean ground, boolean reset, boolean skipAir, double nativeDamage) throws Exception {
        String previous = ServerVersion.getMinecraftVersion();
        try (var manager = mockStatic(DataManager.class);
             var movement = mockStatic(MovingUtil.class);
             var folia = mockStatic(Folia.class)) {
            ServerVersion.setMinecraftVersion("26.2");
            Player player = mock(Player.class);
            World world = mock(World.class); Block block = mock(Block.class);
            when(block.getType()).thenReturn(Material.STONE);
            when(world.getBlockAt(any(Location.class))).thenReturn(block);
            when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(block);
            when(player.getLocation()).thenReturn(new Location(world, 16, 81, 16));
            when(player.getFallDistance()).thenReturn(26.56f);
            IPlayerData pData = mock(IPlayerData.class);
            MovingData data = mock(MovingData.class, CALLS_REAL_METHODS);
            field(data, MovingData.class, "playerMoves", mock(MoveTrace.class));
            field(data, MovingData.class, "vDistAcc", new ActionAccumulator(3, 3));
            data.noFallMaxY = 107.56; data.noFallFallDistance = 26.56f;
            data.noFallSkipAirCheck = skipAir;
            MovingConfig config = mock(MovingConfig.class);
            manager.when(() -> DataManager.getPlayerData(player)).thenReturn(pData);
            when(pData.getGenericInstance(MovingData.class)).thenReturn(data);
            when(pData.getGenericInstance(MovingConfig.class)).thenReturn(config);
            when(pData.isCheckActive(CheckType.MOVING, player)).thenReturn(true);
            PlayerMoveInfo info = mock(PlayerMoveInfo.class);
            PlayerLocation support = mock(PlayerLocation.class);
            field(info, MoveInfo.class, "from", support);
            when(support.isOnGround(anyDouble())).thenReturn(ground);
            when(support.isResetCond()).thenReturn(reset);
            when(support.isOnGround(anyDouble(), anyDouble(), anyDouble())).thenReturn(ground);
            movement.when(() -> MovingUtil.shouldCheckSurvivalFly(player, support, null, data, config, pData)).thenReturn(true);
            AuxMoving aux = mock(AuxMoving.class);
            when(aux.usePlayerMoveInfo()).thenReturn(info);
            NoFall noFall = mock(NoFall.class);
            when(noFall.isEnabled(player, pData)).thenReturn(true);
            MovingListener listener = mock(MovingListener.class, CALLS_REAL_METHODS);
            field(listener, MovingListener.class, "aux", aux);
            field(listener, MovingListener.class, "noFall", noFall);
            MCAccess access = mock(MCAccess.class);
            when(access.dealFallDamageFiresAnEvent()).thenReturn(fr.neatmonster.nocheatplus.compat.AlmostBoolean.YES);
            IGenericInstanceHandle handle = mock(IGenericInstanceHandle.class);
            when(handle.getHandle()).thenReturn(access);
            field(listener, fr.neatmonster.nocheatplus.checks.CheckListener.class, "mcAccess", handle);
            EntityDamageEvent damage = new EntityDamageEvent(player, DamageCause.FALL, nativeDamage);
            Method check = MovingListener.class.getDeclaredMethod("checkFallDamageEvent", Player.class, EntityDamageEvent.class);
            check.setAccessible(true); check.invoke(listener, player, damage);
            assertEquals(nativeDamage, damage.getDamage(), 0);
            assertEquals(!ground && !reset, damage.isCancelled());
            assertEquals(ground || reset ? 0 : 26.56f, data.noFallFallDistance, 0);
            verify(player, never()).setFallDistance(anyFloat());
            verify(aux).returnPlayerMoveInfo(info);
        } finally { ServerVersion.setMinecraftVersion(previous); }
    }
    @Test public void windLimitedDamageSurvivesContactWithSkipAirCheck() throws Exception { event(true, false, true, 8); }
    @Test public void windLimitedDamageSurvivesCorrectionRecovery() throws Exception { event(true, false, false, 8); }
    @Test public void nativeShortFallAndImmunityBoundaryKeepOnePoint() throws Exception { event(true, false, false, 1); }
    @Test public void acceptedResetMediumPreservesNativeMitigation() throws Exception { event(false, true, false, 1); }
    @Test public void phantomSupportCannotConsumeDistanceEvenAfterCorrection() throws Exception { event(false, false, true, 8); }
}
