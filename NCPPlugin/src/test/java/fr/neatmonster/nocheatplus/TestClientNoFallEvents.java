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

public class TestClientNoFallEvents {
    private static void field(Object target, Class<?> owner, String name, Object value) throws Exception {
        Field f = owner.getDeclaredField(name); f.setAccessible(true); f.set(target, value);
    }
    private void event(boolean ground, boolean reset, String entityKind, boolean shouldCancel) throws Exception {
        boolean skipAir = false; double nativeDamage = "boat-history".equals(entityKind) ? 3 : 9;
        String previous = ServerVersion.getMinecraftVersion();
        try (var manager = mockStatic(DataManager.class);
             var movement = mockStatic(MovingUtil.class);
             var folia = mockStatic(Folia.class);
             var bukkit = mockStatic(org.bukkit.Bukkit.class)) {
            ServerVersion.setMinecraftVersion("26.2");
            Player player = mock(Player.class);
            World world = mock(World.class); Block block = mock(Block.class);
            when(block.getType()).thenReturn(Material.STONE);
            when(world.getBlockAt(any(Location.class))).thenReturn(block);
            when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(block);
            when(player.getLocation()).thenReturn(new Location(world, 16, 81, 16));
            when(player.getFallDistance()).thenReturn(26.56f);
            when(player.getBoundingBox()).thenReturn(new org.bukkit.util.BoundingBox(15.7,81,15.7,16.3,82.8,16.3));
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
            if (entityKind != null) {
                org.bukkit.entity.Entity entity = entityKind.startsWith("boat") ? mock(org.bukkit.entity.Boat.class) : mock(org.bukkit.entity.AreaEffectCloud.class);
                when(entity.isValid()).thenReturn(true);
                folia.when(() -> Folia.isOwnedByCurrentRegion(entity)).thenReturn(true);
                when(access.canCollideWith(player, entity)).thenReturn(entityKind.startsWith("boat"));
                when(entity.getUniqueId()).thenReturn(java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"));
                when(player.getUniqueId()).thenReturn(java.util.UUID.fromString("00000000-0000-0000-0000-000000000002"));
                when(entity.getBoundingBox()).thenReturn(new org.bukkit.util.BoundingBox(15.5,80,15.5,16.5,81,16.5));
                when(player.getNearbyEntities(1.5,8,1.5)).thenReturn(java.util.List.of(entity));
                if ("boat-history".equals(entityKind)) {
                    when(player.getLocation()).thenReturn(new Location(world,16,81.84805,16));
                    when(entity.getBoundingBox()).thenReturn(new org.bukkit.util.BoundingBox(15.5,81,15.5,16.5,81.5625,16.5));
                    when(access.hasSweptBoatSupport(eq(entity),anyDouble(),anyDouble(),anyDouble(),anyDouble(),anyDouble())).thenReturn(true);
                }
            }
            if ("boat-swept".equals(entityKind)) when(access.hasVerticalCollisionBelow(player)).thenReturn(true);
            if ("boat-swept".equals(entityKind)) when(player.getLocation()).thenReturn(new Location(world,16,81.65,16));
            if ("boat-above".equals(entityKind)) when(player.getLocation()).thenReturn(new Location(world,16,81.84,16));
            if ("boat-beside".equals(entityKind)) when(player.getLocation()).thenReturn(new Location(world,16.84,81,16));
            EntityDamageEvent damage = new EntityDamageEvent(player, DamageCause.FALL, nativeDamage);
            Method check = MovingListener.class.getDeclaredMethod("checkFallDamageEvent", Player.class, EntityDamageEvent.class);
            check.setAccessible(true); check.invoke(listener, player, damage);
            assertEquals(nativeDamage, damage.getDamage(), 0);
            assertEquals("Native FALL cancellation for " + entityKind, shouldCancel, damage.isCancelled());
            assertEquals(shouldCancel ? 26.56f : 0, data.noFallFallDistance, 0);
            verify(player, never()).setFallDistance(anyFloat());
            verify(aux).returnPlayerMoveInfo(info);
        } finally { ServerVersion.setMinecraftVersion(previous); }
    }
    @Test public void realStoneLandingPreservesNativeNineDamage() throws Exception { event(true,false,null,false); }
    @Test public void spoofedAirGroundCancelsPrematureDamageAndRetainsDebt() throws Exception { event(false,false,null,true); }
    @Test public void boatCollisionPreservesNativeDamage() throws Exception { event(false,false,"boat",false); }
    @Test public void nativeSweptBoatCollisionPreservesDamageAfterTheBoatMoves() throws Exception { event(false,false,"boat-swept",false); }
    @Test public void boatBelowWithoutContactCannotConsumeFall() throws Exception { event(false,false,"boat-above",true); }
    @Test public void boatBesideWithoutOverlapCannotConsumeFall() throws Exception { event(false,false,"boat-beside",true); }
    @Test public void nonCollidableAreaEffectCloudIsNotLandingSupport() throws Exception { event(false,false,"cloud",true); }
    @Test public void nativePreviousBoatPositionPreservesTheThreePointDismountLanding() throws Exception { event(false,false,"boat-history",false); }

}
