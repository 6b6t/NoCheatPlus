package fr.neatmonster.nocheatplus;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
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
import fr.neatmonster.nocheatplus.checks.moving.util.SetBackRequest;
import fr.neatmonster.nocheatplus.checks.workaround.WRPT;
import fr.neatmonster.nocheatplus.compat.Folia;
import fr.neatmonster.nocheatplus.logging.LogManager;
import fr.neatmonster.nocheatplus.players.DataManager;
import fr.neatmonster.nocheatplus.players.IPlayerData;
import fr.neatmonster.nocheatplus.players.PlayerData;
import fr.neatmonster.nocheatplus.permissions.PermissionRegistry;
import fr.neatmonster.nocheatplus.utilities.location.PlayerLocation;

public class TestTeleportCompletion {
    private Player player;
    private World world;
    private MovingData data;
    private MovingConfig config;
    private IPlayerData pData;
    private AuxMoving aux;

    @Before
    public void setup() throws Exception {
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
        final Field method = MovingConfig.class.getField("playerSetBackMethod");
        method.setAccessible(true);
        method.set(config, fr.neatmonster.nocheatplus.checks.moving.player.PlayerSetBackMethod.CAUTIOUS);
        pData = mock(IPlayerData.class);
        when(pData.getGenericInstance(MovingConfig.class)).thenReturn(config);
        when(pData.getGenericInstance(CombinedData.class)).thenReturn(new CombinedData());
        data = new MovingData(config, pData);
        when(pData.getGenericInstance(MovingData.class)).thenReturn(data);
        player = mock(Player.class);
        when(player.isOnline()).thenReturn(true);
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

    @Test
    public void clearedSetbackDoesNotReadPlayerLocationOrStartTeleport() {
        data.prepareSetBack(new Location(world, 10, 90, 0));
        data.resetTeleported();
        try (MockedStatic<Folia> folia = mockStatic(Folia.class)) {
            folia.when(() -> Folia.isOwnedByCurrentRegion(player)).thenReturn(true);
            assertFalse(MovingUtil.processStoredSetBack(player, "", pData));
            verify(player, never()).getLocation();
            folia.verify(() -> Folia.teleportEntityAsync(any(), any(), any()), never());
        }
    }

    @Test
    public void globalSchedulerCannotReadSetbackState() {
        try (MockedStatic<Folia> folia = mockStatic(Folia.class)) {
            clearInvocations(pData);
            assertThrows(IllegalStateException.class, () -> MovingUtil.processStoredSetBack(player, "", pData));
            verifyNoInteractions(pData);
        }
    }

    @Test
    public void olderFailedCompletionCannotClearNewCorrectionAtSameDestination() throws Exception {
        final Location target = new Location(world, 10, 90, 0);
        when(player.getLocation()).thenReturn(new Location(world, 0, 70, 0));
        data.prepareSetBack(target);
        final var first = new CompletableFuture<Boolean>();
        final var second = new CompletableFuture<Boolean>();
        try (MockedStatic<Folia> folia = mockStatic(Folia.class)) {
            folia.when(() -> Folia.isOwnedByCurrentRegion(player)).thenReturn(true);
            folia.when(() -> Folia.teleportEntityAsync(any(), any(), any())).thenReturn(first, second);
            assertTrue(MovingUtil.processStoredSetBack(player, "", pData));
            data.prepareSetBack(target);
            final long newer = data.getSetBackSequence();
            final Thread completion = Thread.ofVirtual().start(() -> first.complete(false));
            completion.join();
            assertEquals(newer, data.getSetBackSequence()); // Completion cannot touch movement state off-region.
            assertTrue(MovingUtil.processStoredSetBack(player, "", pData));
            assertEquals(newer, data.getSetBackSequence());
            assertEquals(target, data.getTeleported());
            folia.verify(() -> Folia.teleportEntityAsync(any(), any(), any()), times(2));
        }
    }

    @Test
    public void slowTeleportDoesNotAllowAnotherAttemptAfterOneSecond() throws Exception {
        final Location target = new Location(world, 10, 90, 0);
        when(player.getLocation()).thenReturn(new Location(world, 0, 70, 0));
        data.prepareSetBack(target);
        try (MockedStatic<Folia> folia = mockStatic(Folia.class)) {
            folia.when(() -> Folia.isOwnedByCurrentRegion(player)).thenReturn(true);
            folia.when(() -> Folia.teleportEntityAsync(any(), any(), any())).thenReturn(new CompletableFuture<>());
            assertTrue(MovingUtil.processStoredSetBack(player, "", pData));
            Thread.sleep(1100L); // The old one-second timeout allowed a second attempt here.
            for (int tick = 0; tick < 20; ++tick) {
                assertFalse(MovingUtil.processStoredSetBack(player, "", pData));
            }
            folia.verify(() -> Folia.teleportEntityAsync(any(), any(), any()), times(1));
        }
    }

    @Test
    public void reentrantDirectSetbackWaitsForTheActualTeleportToFinish() {
        final Location firstTarget = new Location(world, 10, 90, 0);
        final Location latestTarget = new Location(world, 20, 90, 0);
        when(player.getLocation()).thenReturn(new Location(world, 0, 70, 0));
        final var first = new CompletableFuture<Boolean>();
        final var second = new CompletableFuture<Boolean>();
        final AtomicInteger attempts = new AtomicInteger();
        try (MockedStatic<Folia> folia = mockStatic(Folia.class);
             MockedStatic<DataManager> manager = mockStatic(DataManager.class)) {
            manager.when(() -> DataManager.getPlayerData(player)).thenReturn(pData);
            folia.when(() -> Folia.isOwnedByCurrentRegion(player)).thenReturn(true);
            folia.when(() -> Folia.teleportEntityAsync(any(), any(), any())).thenAnswer(call -> {
                if (attempts.incrementAndGet() == 1) {
                    MovingUtil.teleportSetBack(player, latestTarget, data);
                    return first;
                }
                assertEquals(latestTarget, call.getArgument(1));
                return second;
            });
            MovingUtil.teleportSetBack(player, firstTarget, data);
            assertEquals(1, attempts.get());
            assertEquals(latestTarget, data.getTeleported());
            first.complete(false);
            assertTrue(MovingUtil.processStoredSetBack(player, "", pData));
            assertEquals(2, attempts.get());
        }
    }

    @Test
    public void oldConnectionCompletionCannotChangeReconnectedPlayersAttempt() {
        final Location target = new Location(world, 10, 90, 0);
        when(player.getLocation()).thenReturn(new Location(world, 0, 70, 0));
        final Player reconnect = mock(Player.class);
        when(reconnect.getLocation()).thenReturn(new Location(world, 0, 70, 0));
        final var first = new CompletableFuture<Boolean>();
        final var second = new CompletableFuture<Boolean>();
        try (MockedStatic<Folia> folia = mockStatic(Folia.class)) {
            folia.when(() -> Folia.isOwnedByCurrentRegion(any(Player.class))).thenReturn(true);
            folia.when(() -> Folia.teleportEntityAsync(any(), any(), any())).thenReturn(first, second);
            data.prepareSetBack(target);
            assertTrue(MovingUtil.processStoredSetBack(player, "", pData));
            data.resetSetBackTeleport(); // Join invalidates the old connection's attempt and destination.
            data.prepareSetBack(target);
            assertTrue(MovingUtil.processStoredSetBack(reconnect, "", pData));
            final var current = data.getSetBackTeleport();
            first.complete(false);
            assertFalse(MovingUtil.processStoredSetBack(reconnect, "", pData));
            assertSame(current, data.getSetBackTeleport());
            assertEquals(target, data.getTeleported());
            assertNull(current.getResult());
        }
    }

    @Test
    public void schedulerAndProcessingFailuresKeepCompletedResultWithoutRepeatingTeleport() throws Exception {
        final Location target = new Location(world, 10, 90, 0);
        when(player.getLocation()).thenReturn(new Location(world, 0, 70, 0));
        final var completion = new CompletableFuture<Boolean>();
        final SetBackRequest<Player> request = new SetBackRequest<>();
        request.bind(player);
        doAnswer(call -> { request.request(); return null; }).when(pData).requestPlayerSetBack();
        final Runnable process = () -> MovingUtil.processStoredSetBack(player, "", pData);
        final PlayerMoveInfo info = mockMoveInfo(target);
        data.prepareSetBack(target);
        data.noFallFallDistance = 15F;
        data.survivalFlyVL = 100;
        try (MockedStatic<Folia> folia = mockStatic(Folia.class)) {
            folia.when(() -> Folia.isOwnedByCurrentRegion(player)).thenReturn(true);
            folia.when(() -> Folia.teleportEntityAsync(any(), any(), any())).thenReturn(completion);
            request.request();
            request.dispatch(player, (run, retired) -> { run.run(); return true; }, process);
            completion.complete(true);
            when(player.getLocation()).thenReturn(target.clone());
            request.dispatch(player, (run, retired) -> false, process);
            request.dispatch(player, (run, retired) -> false, process);
            assertTrue(request.isPending());
            assertTrue(data.getSetBackTeleport().getResult().success());
            when(aux.usePlayerMoveInfo()).thenThrow(new IllegalStateException("processing failed")).thenReturn(info);
            assertThrows(IllegalStateException.class, () -> request.dispatch(player,
                    (run, retired) -> { run.run(); return true; }, process));
            assertTrue(request.isPending());
            assertTrue(data.getSetBackTeleport().getResult().success());
            assertEquals(target, data.getTeleported());
            request.dispatch(player, (run, retired) -> { run.run(); return true; }, process);
            assertFalse(request.isPending());
            assertFalse(data.hasTeleported());
            assertNull(data.getSetBackTeleport());
            assertEquals(15F, data.noFallFallDistance, 0F);
            assertEquals(100, data.survivalFlyVL, 0.0);
            folia.verify(() -> Folia.teleportEntityAsync(any(), any(), any()), times(1));
        }
    }

    @Test
    public void successAtAnObsoletePositionDoesNotReadThatDestinationsChunks() {
        final Location target = new Location(world, 10, 90, 0);
        when(player.getLocation()).thenReturn(new Location(world, 0, 70, 0));
        data.prepareSetBack(target);
        final var completion = new CompletableFuture<Boolean>();
        try (MockedStatic<Folia> folia = mockStatic(Folia.class)) {
            folia.when(() -> Folia.isOwnedByCurrentRegion(player)).thenReturn(true);
            folia.when(() -> Folia.teleportEntityAsync(any(), any(), any())).thenReturn(completion);
            assertTrue(MovingUtil.processStoredSetBack(player, "", pData));
            completion.complete(true);
            final World otherWorld = world();
            when(player.getLocation()).thenReturn(new Location(otherWorld, 10, 90, 0));
            assertFalse(MovingUtil.processStoredSetBack(player, "", pData));
            assertNull(data.getSetBackTeleport());
            assertFalse(data.hasTeleported());
            verify(aux, never()).usePlayerMoveInfo();
            folia.verify(() -> Folia.teleportEntityAsync(any(), any(), any()), times(1));
        }
    }

    @Test
    public void oldFoxArrivalAndBukkitEventDoNotDiscardNewerCorrection() {
        final Location firstTarget = new Location(world, 10, 90, 0);
        final Location latestTarget = new Location(world, 20, 90, 0);
        when(player.getLocation()).thenReturn(new Location(world, 0, 70, 0));
        data.prepareSetBack(firstTarget);
        final var first = new CompletableFuture<Boolean>();
        final var second = new CompletableFuture<Boolean>();
        final MovingListener listener = mock(MovingListener.class, CALLS_REAL_METHODS);
        try (MockedStatic<Folia> folia = mockStatic(Folia.class);
             MockedStatic<DataManager> manager = mockStatic(DataManager.class)) {
            manager.when(() -> DataManager.getPlayerData(player)).thenReturn(pData);
            folia.when(() -> Folia.isOwnedByCurrentRegion(player)).thenReturn(true);
            folia.when(() -> Folia.teleportEntityAsync(any(), any(), any())).thenReturn(first, second);
            assertTrue(MovingUtil.processStoredSetBack(player, "", pData));
            data.prepareSetBack(latestTarget);
            final long latest = data.getSetBackSequence();
            data.noFallFallDistance = 15F;
            data.survivalFlyVL = 100;
            final var event = new PlayerTeleportEvent(player, player.getLocation(), firstTarget, TeleportCause.PLUGIN);
            event.setCancelled(true);
            listener.onPlayerTeleportMonitor(event);
            event.setCancelled(false);
            listener.onPlayerTeleportMonitor(event);
            assertEquals(latestTarget, data.getTeleported());
            when(player.getLocation()).thenReturn(firstTarget.clone());
            MovingUtil.onTeleportComplete(player, firstTarget, 1L, data, pData);
            assertEquals(latest, data.getSetBackSequence());
            assertEquals(latestTarget, data.getTeleported());
            assertEquals(15F, data.noFallFallDistance, 0F);
            assertEquals(100, data.survivalFlyVL, 0.0);
            first.complete(true);
            assertTrue(MovingUtil.processStoredSetBack(player, "", pData));
            folia.verify(() -> Folia.teleportEntityAsync(same(player), eq(latestTarget), any()), times(1));
        }
    }

    @Test
    public void actualPlayerDataSchedulesMovementAccessOnEntityThread() throws Exception {
        final PlayerData actual = spy(new PlayerData(UUID.randomUUID(), "setback-test", new PermissionRegistry(1)));
        doReturn(data).when(actual).getGenericInstance(MovingData.class);
        doReturn(config).when(actual).getGenericInstance(MovingConfig.class);
        final Method online = PlayerData.class.getDeclaredMethod("onPlayerOnline", Player.class);
        online.setAccessible(true);
        online.invoke(actual, player);
        final Method frequent = PlayerData.class.getDeclaredMethod("frequentTasks", int.class, long.class, Player.class);
        frequent.setAccessible(true);
        final var task = new java.util.concurrent.atomic.AtomicReference<java.util.function.Consumer<Object>>();
        final var completion = new CompletableFuture<Boolean>();
        final var plugins = mock(org.bukkit.plugin.PluginManager.class);
        data.prepareSetBack(new Location(world, 10, 90, 0));
        when(player.getLocation()).thenReturn(new Location(world, 0, 70, 0));
        try (MockedStatic<Folia> folia = mockStatic(Folia.class);
             MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<DataManager> manager = mockStatic(DataManager.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
            folia.when(() -> Folia.runSyncTaskForEntity(any(), any(), any(), any())).thenAnswer(call -> {
                task.set(call.getArgument(2));
                return 1;
            });
            folia.when(() -> Folia.isTaskScheduled(any())).thenCallRealMethod();
            folia.when(() -> Folia.teleportEntityAsync(any(), any(), any())).thenReturn(completion);
            actual.requestPlayerSetBack();
            frequent.invoke(actual, 1, 0L, player);
            verify(player, never()).getLocation();
            assertNotNull(task.get());
            folia.when(() -> Folia.isOwnedByCurrentRegion(player)).thenReturn(true);
            task.get().accept(null);
            folia.verify(() -> Folia.teleportEntityAsync(any(), any(), any()), times(1));
            assertTrue(actual.isPlayerSetBackScheduled());
            completion.complete(false);
            folia.when(() -> Folia.isOwnedByCurrentRegion(player)).thenReturn(false);
            frequent.invoke(actual, 2, 0L, player);
            folia.when(() -> Folia.isOwnedByCurrentRegion(player)).thenReturn(true);
            task.get().accept(null);
            assertFalse(actual.isPlayerSetBackScheduled());
            assertFalse(data.hasTeleported());
        }
    }

    @Test
    public void cancelledExceptionalAndThrowingTeleportsDoNotLeavePendingState() {
        final Location target = new Location(world, 10, 90, 0);
        when(player.getLocation()).thenReturn(new Location(world, 0, 70, 0));
        try (MockedStatic<Folia> folia = mockStatic(Folia.class)) {
            folia.when(() -> Folia.isOwnedByCurrentRegion(player)).thenReturn(true);
            for (int failure = 0; failure < 4; ++failure) {
                final var completion = new CompletableFuture<Boolean>();
                if (failure == 3) {
                    folia.when(() -> Folia.teleportEntityAsync(any(), any(), any()))
                            .thenThrow(new IllegalStateException("teleport invocation failed"));
                } else {
                    folia.when(() -> Folia.teleportEntityAsync(any(), any(), any())).thenReturn(completion);
                }
                data.prepareSetBack(target);
                assertTrue(MovingUtil.processStoredSetBack(player, "", pData));
                switch (failure) {
                    case 0 -> completion.complete(false);
                    case 1 -> completion.completeExceptionally(new IllegalStateException("teleport failed"));
                    case 2 -> completion.cancel(false);
                    default -> {}
                }
                assertFalse(MovingUtil.processStoredSetBack(player, "", pData));
                assertFalse(data.hasTeleported());
                assertNull(data.getSetBackTeleport());
            }
        }
    }

    @Test
    public void sameCoordinatesInAnotherWorldStillRequireTeleport() {
        final Location target = new Location(world, 10, 90, 0);
        final World otherWorld = world();
        when(player.getLocation()).thenReturn(new Location(otherWorld, 10, 90, 0));
        data.prepareSetBack(target);
        try (MockedStatic<Folia> folia = mockStatic(Folia.class)) {
            folia.when(() -> Folia.isOwnedByCurrentRegion(player)).thenReturn(true);
            folia.when(() -> Folia.teleportEntityAsync(any(), any(), any())).thenReturn(new CompletableFuture<>());
            assertTrue(MovingUtil.processStoredSetBack(player, "", pData));
            folia.verify(() -> Folia.teleportEntityAsync(same(player), eq(target), any()), times(1));
        }
    }

    private PlayerMoveInfo mockMoveInfo(final Location target) throws Exception {
        final PlayerMoveInfo info = mock(PlayerMoveInfo.class);
        final PlayerLocation from = mock(PlayerLocation.class);
        final String worldName = target.getWorld().getName();
        when(from.getWorldName()).thenReturn(worldName);
        when(from.getX()).thenReturn(target.getX());
        when(from.getY()).thenReturn(target.getY());
        when(from.getZ()).thenReturn(target.getZ());
        final Field field = MoveInfo.class.getField("from");
        field.setAccessible(true);
        field.set(info, from);
        when(aux.usePlayerMoveInfo()).thenReturn(info);
        return info;
    }

    private static World world() {
        final World world = mock(World.class);
        final UUID id = UUID.randomUUID();
        when(world.getUID()).thenReturn(id);
        when(world.getName()).thenReturn(id.toString());
        return world;
    }
}
