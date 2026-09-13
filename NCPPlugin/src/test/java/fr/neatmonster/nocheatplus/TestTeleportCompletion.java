package fr.neatmonster.nocheatplus;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

import fr.neatmonster.nocheatplus.checks.CheckType;
import fr.neatmonster.nocheatplus.checks.combined.CombinedData;
import fr.neatmonster.nocheatplus.checks.moving.MovingConfig;
import fr.neatmonster.nocheatplus.checks.moving.MovingData;
import fr.neatmonster.nocheatplus.checks.moving.MovingListener;
import fr.neatmonster.nocheatplus.checks.moving.location.tracking.LocationTrace.TraceEntryPool;
import fr.neatmonster.nocheatplus.checks.moving.model.LocationData;
import fr.neatmonster.nocheatplus.checks.moving.model.MoveInfo;
import fr.neatmonster.nocheatplus.checks.moving.model.PlayerMoveInfo;
import fr.neatmonster.nocheatplus.checks.moving.util.AuxMoving;
import fr.neatmonster.nocheatplus.checks.moving.util.MovingUtil;
import fr.neatmonster.nocheatplus.checks.workaround.WRPT;
import fr.neatmonster.nocheatplus.compat.Folia;
import fr.neatmonster.nocheatplus.logging.LogManager;
import fr.neatmonster.nocheatplus.players.DataManager;
import fr.neatmonster.nocheatplus.players.IPlayerData;
import fr.neatmonster.nocheatplus.utilities.location.PlayerLocation;

public class TestTeleportCompletion {
    private Player player;
    private World world;
    private MovingData data;
    private MovingConfig config;
    private IPlayerData pData;
    private AuxMoving aux;

    @Before
    public void setup() {
        final LogManager logs = mock(LogManager.class);
        final PluginTests.UnitTestNoCheatPlusAPI api = new PluginTests.UnitTestNoCheatPlusAPI() {
            @Override public LogManager getLogManager() { return logs; }
        };
        NCPAPIProvider.setNoCheatPlusAPI(api);
        api.registerGenericInstance(WRPT.class, new WRPT());
        api.registerGenericInstance(TraceEntryPool.class, new TraceEntryPool(100));
        aux = mock(AuxMoving.class);
        api.registerGenericInstance(AuxMoving.class, aux);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getVersion).thenReturn("Folia (MC: 26.2)");
            config = new MovingConfig(api.getWorldDataManager().getWorldData(UUID.randomUUID().toString()));
        }
        pData = mock(IPlayerData.class);
        when(pData.getGenericInstance(MovingConfig.class)).thenReturn(config);
        when(pData.getGenericInstance(CombinedData.class)).thenReturn(new CombinedData());
        data = new MovingData(config, pData);
        when(pData.getGenericInstance(MovingData.class)).thenReturn(data);
        player = mock(Player.class);
        world = world();
        when(pData.isCheckActive(CheckType.MOVING, player)).thenReturn(true);
        doAnswer(call -> {
            final Location to = call.getArgument(1);
            data.resetPlayerPositions(null);
            final LocationData ref = new LocationData();
            ref.setLocation(to.getWorld().getName(), to.getX(), to.getY(), to.getZ(), to.getYaw(), to.getPitch());
            data.playerMoves.getFirstPastMove().set(ref);
            return null;
        }).when(aux).resetPositionsAndMediumProperties(any(), any(), same(data), same(config));
    }

    @Test
    public void distantArrivalThenSmallMoveDoesNotRollBackButUncheckedDriftDoes() throws Exception {
        final Location to = new Location(world, 8000000, 90, -8000000);
        when(player.getLocation()).thenReturn(to.clone());
        MovingUtil.onTeleportComplete(player, to, 1L, data, pData);
        assertEquals(to, data.getSetBack(to));
        assertEquals(to.getX(), data.untrackedTrustedX, 0.0);
        when(player.getLocation()).thenReturn(to.clone().add(0.02, 0, 0));

        final MovingListener listener = mock(MovingListener.class, CALLS_REAL_METHODS);
        final Field supported = MovingListener.class.getDeclaredField("teleportCompletionSupported");
        supported.setAccessible(true);
        supported.setBoolean(listener, true);
        final Method poll = MovingListener.class.getDeclaredMethod("checkUntrackedMove", Player.class);
        poll.setAccessible(true);
        try (MockedStatic<DataManager> manager = mockStatic(DataManager.class);
             MockedStatic<MovingUtil> moving = mockStatic(MovingUtil.class, CALLS_REAL_METHODS)) {
            manager.when(() -> DataManager.getPlayerData(player)).thenReturn(pData);
            moving.when(() -> MovingUtil.teleportSetBack(any(), any(), any())).thenAnswer(call -> null);
            poll.invoke(listener, player);
            moving.verify(() -> MovingUtil.teleportSetBack(any(), any(), any()), never());
            when(player.getLocation()).thenReturn(to.clone().add(0.2, 0, 0));
            poll.invoke(listener, player);
            moving.verify(() -> MovingUtil.teleportSetBack(player, to, data));
        }
    }

    @Test
    public void staleOrUncompletedTargetCannotOverwriteNewerPosition() {
        final Location latest = new Location(world, 40, 90, 0);
        final Location turned = latest.clone();
        turned.setYaw(45F);
        when(player.getLocation()).thenReturn(turned);
        MovingUtil.onTeleportComplete(player, latest, 2L, data, pData);
        assertEquals(45F, data.getSetBack(turned).getYaw(), 0F);
        final Location old = new Location(world, 10, 90, 0);
        MovingUtil.onTeleportComplete(player, old, 1L, data, pData);
        MovingUtil.onTeleportComplete(player, old, 3L, data, pData);
        assertEquals(40, data.untrackedTrustedX, 0.0);
        assertEquals(2L, data.lastTeleportCompletionSequence);
        verify(aux, times(1)).resetPositionsAndMediumProperties(any(), any(), any(), any());
    }

    @Test
    public void matchingCoordinatesInAnotherWorldAreNotOurPendingSetBack() {
        final Location source = new Location(world, 10, 90, 0);
        data.prepareSetBack(source);
        final Location to = new Location(world(), 10, 90, 0);
        when(player.getLocation()).thenReturn(to.clone());
        MovingUtil.onTeleportComplete(player, to, 1L, data, pData);
        assertEquals(to.getWorld(), data.getSetBack(to).getWorld());
        assertFalse(data.hasTeleported());
        verify(aux).resetPositionsAndMediumProperties(player, to, data, config);
    }

    @Test
    public void ownCorrectionConfirmsWithoutClearingFallDistanceOrViolations() throws Exception {
        final Location to = new Location(world, 10, 90, 0);
        when(player.getLocation()).thenReturn(to.clone());
        data.prepareSetBack(to);
        data.noFallFallDistance = 15F;
        data.survivalFlyVL = 100;
        final PlayerMoveInfo info = mock(PlayerMoveInfo.class);
        final PlayerLocation from = mock(PlayerLocation.class);
        final String worldName = world.getName();
        when(from.getWorldName()).thenReturn(worldName);
        when(from.getX()).thenReturn(to.getX());
        when(from.getY()).thenReturn(to.getY());
        when(from.getZ()).thenReturn(to.getZ());
        final Field field = MoveInfo.class.getField("from");
        field.setAccessible(true);
        field.set(info, from);
        when(aux.usePlayerMoveInfo()).thenReturn(info);
        MovingUtil.onTeleportComplete(player, to, 1L, data, pData);
        assertFalse(data.hasTeleported());
        assertEquals(15F, data.noFallFallDistance, 0F);
        assertEquals(100, data.survivalFlyVL, 0.0);
        assertEquals(10, data.untrackedTrustedX, 0.0);
        verify(aux, never()).resetPositionsAndMediumProperties(any(), any(), any(), any());
    }

    @Test
    public void remoteDestinationIsNotReadBeforeArrival() {
        final AuxMoving actualAux = spy(new AuxMoving());
        final Location remote = new Location(world, 20, 90, 0);
        try (MockedStatic<Folia> folia = mockStatic(Folia.class)) {
            folia.when(Folia::isFoliaServer).thenReturn(true);
            folia.when(() -> Folia.isOwnedByCurrentRegion(player)).thenReturn(true);
            actualAux.resetPositionsAndMediumProperties(player, remote, data, config);
            verify(actualAux, never()).usePlayerMoveInfo();
        }
    }

    @Test
    public void ownedDestinationBeyondThirtyThousandRefreshesEnvironment() throws Exception {
        final AuxMoving actualAux = spy(new AuxMoving());
        final Location to = new Location(world, 8000000, 90, -8000000);
        final PlayerMoveInfo info = mock(PlayerMoveInfo.class);
        final PlayerLocation from = mock(PlayerLocation.class);
        final Field field = MoveInfo.class.getField("from");
        field.setAccessible(true);
        field.set(info, from);
        doReturn(info).when(actualAux).usePlayerMoveInfo();
        try (MockedStatic<Folia> folia = mockStatic(Folia.class)) {
            folia.when(Folia::isFoliaServer).thenReturn(true);
            folia.when(() -> Folia.isOwnedByCurrentRegion(player)).thenReturn(true);
            folia.when(() -> Folia.isOwnedByCurrentRegion(to)).thenReturn(true);
            actualAux.resetPositionsAndMediumProperties(player, to, data, config);
            verify(info).set(player, to, null, config.yOnGround);
            assertTrue(data.playerMoves.getFirstPastMove().valid);
        }
    }

    private static World world() {
        final World world = mock(World.class);
        final UUID id = UUID.randomUUID();
        when(world.getUID()).thenReturn(id);
        when(world.getName()).thenReturn(id.toString());
        return world;
    }
}
