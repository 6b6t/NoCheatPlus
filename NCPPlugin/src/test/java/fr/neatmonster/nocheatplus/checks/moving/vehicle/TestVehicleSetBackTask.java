package fr.neatmonster.nocheatplus.checks.moving.vehicle;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.Test;
import fr.neatmonster.nocheatplus.NCPAPIProvider;
import fr.neatmonster.nocheatplus.checks.moving.MovingConfig;
import fr.neatmonster.nocheatplus.checks.moving.MovingData;
import fr.neatmonster.nocheatplus.checks.moving.util.AuxMoving;
import fr.neatmonster.nocheatplus.compat.Folia;
import fr.neatmonster.nocheatplus.components.NoCheatPlusAPI;
import fr.neatmonster.nocheatplus.logging.StaticLog;
import fr.neatmonster.nocheatplus.players.DataManager;
import fr.neatmonster.nocheatplus.players.IPlayerData;
import fr.neatmonster.nocheatplus.utilities.entity.PassengerUtil;
import fr.neatmonster.nocheatplus.utilities.map.MaterialUtil;

public class TestVehicleSetBackTask {
    private enum Completion { NORMAL, MOVED, UNSCHEDULED, REPLACED, RETIRED }

    @Test
    public void completionDoesNotAcceptMovementAfterTheCorrection() {
        correction(true, true, Completion.MOVED);
    }

    @Test
    public void unscheduledCompletionReleasesThePendingCorrection() {
        correction(true, true, Completion.UNSCHEDULED);
    }

    @Test
    public void unscheduledStaleCompletionCannotClearANewerCorrection() {
        correction(true, true, Completion.REPLACED);
    }

    @Test
    public void retiredBoatReleasesThePendingCorrection() {
        correction(true, true, Completion.RETIRED);
    }

    @Test
    public void foliaWaitsForTeleportCompletionWithoutEjectingPassengers() {
        correction(true, true);
    }

    @Test
    public void failedCorrectionReleasesPendingStateWithoutAcceptingTheDestination() {
        correction(false, true);
    }

    @Test
    public void dismountedTesterIsNotMovedBackIntoTheBoat() {
        correction(true, false);
    }

    @Test
    public void pendingFoliaCorrectionExpiresAfterFortyTicks() {
        final Entity boat = mock(Entity.class);
        final Player player = mock(Player.class);
        final Plugin plugin = mock(Plugin.class);
        final PluginManager plugins = mock(PluginManager.class);
        final IPlayerData pData = mock(IPlayerData.class);
        final MovingData data = mock(MovingData.class);
        final CompletableFuture<Boolean> teleport = new CompletableFuture<>();
        final AtomicReference<Consumer<Object>> timeout = new AtomicReference<>();
        final AtomicReference<Long> delay = new AtomicReference<>();
        when(boat.getType()).thenReturn(EntityType.BOAT);
        when(boat.getPassengers()).thenReturn(List.of(player));
        when(player.getLocation()).thenReturn(new Location(null, 0, 0, 0));
        when(plugins.getPlugin("NoCheatPlus")).thenReturn(plugin);
        when(pData.getGenericInstance(MovingData.class)).thenReturn(data);
        try (var bukkit = mockStatic(Bukkit.class); var players = mockStatic(DataManager.class);
                var folia = mockStatic(Folia.class); var logs = mockStatic(StaticLog.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
            players.when(() -> DataManager.getPlayerData(player)).thenReturn(pData);
            folia.when(Folia::isFoliaServer).thenReturn(true);
            folia.when(() -> Folia.teleportEntityWithPassengersAsync(eq(boat), any(Location.class), any())).thenReturn(teleport);
            folia.when(() -> Folia.runSyncDelayedTaskForEntity(eq(boat), eq(plugin), any(), any(), anyLong()))
                    .thenAnswer(call -> {
                        timeout.set(call.getArgument(2));
                        delay.set(call.getArgument(4));
                        return new Object();
                    });
            new VehicleSetBackTask(boat, player, new Location(null, 2, 0, 0), false).run();
            assertTrue(data.isVehicleSetBack);
            assertSame(teleport, data.vehicleSetBackTaskId);
            assertEquals(Long.valueOf(40), delay.get());
            timeout.get().accept(null);
            assertFalse(data.isVehicleSetBack);
            assertNull(data.vehicleSetBackTaskId);
        }
    }

    @Test
    public void nonBoatOnFoliaUsesTheExistingPassengerTeleportPath() {
        final Entity horse = mock(Entity.class);
        final Player player = mock(Player.class);
        final IPlayerData pData = mock(IPlayerData.class);
        final MovingData data = mock(MovingData.class);
        final NoCheatPlusAPI api = mock(NoCheatPlusAPI.class);
        final PassengerUtil passengerUtil = mock(PassengerUtil.class);
        when(horse.getType()).thenReturn(EntityType.HORSE);
        when(horse.getPassengers()).thenReturn(List.of(player));
        when(player.getLocation()).thenReturn(new Location(null, 0, 0, 0));
        when(pData.getGenericInstance(MovingData.class)).thenReturn(data);
        when(api.getGenericInstance(PassengerUtil.class)).thenReturn(passengerUtil);
        try (var players = mockStatic(DataManager.class); var provider = mockStatic(NCPAPIProvider.class);
                var folia = mockStatic(Folia.class); var material = mockStatic(MaterialUtil.class)) {
            players.when(() -> DataManager.getPlayerData(player)).thenReturn(pData);
            provider.when(NCPAPIProvider::getNoCheatPlusAPI).thenReturn(api);
            folia.when(Folia::isFoliaServer).thenReturn(true);
            material.when(() -> MaterialUtil.isBoat(EntityType.HORSE)).thenReturn(false);
            new VehicleSetBackTask(horse, player, new Location(null, 2, 0, 0), false).run();
            verify(passengerUtil).teleportWithPassengers(horse, player, new Location(null, 2, 0, 0), false,
                    new Entity[] { player }, true, pData);
            assertNull(data.vehicleSetBackTaskId);
            assertFalse(data.isVehicleSetBack);
        }
    }

    @Test
    public void timeoutRegistrationFailureClearsPendingCorrection() {
        final Entity boat = mock(Entity.class);
        final Player player = mock(Player.class);
        final Plugin plugin = mock(Plugin.class);
        final PluginManager plugins = mock(PluginManager.class);
        final IPlayerData pData = mock(IPlayerData.class);
        final MovingData data = mock(MovingData.class);
        when(boat.getType()).thenReturn(EntityType.BOAT);
        when(boat.getPassengers()).thenReturn(List.of(player));
        when(player.getLocation()).thenReturn(new Location(null, 0, 0, 0));
        when(plugins.getPlugin("NoCheatPlus")).thenReturn(plugin);
        when(pData.getGenericInstance(MovingData.class)).thenReturn(data);
        try (var bukkit = mockStatic(Bukkit.class); var players = mockStatic(DataManager.class);
                var folia = mockStatic(Folia.class); var logs = mockStatic(StaticLog.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
            players.when(() -> DataManager.getPlayerData(player)).thenReturn(pData);
            folia.when(Folia::isFoliaServer).thenReturn(true);
            folia.when(() -> Folia.runSyncDelayedTaskForEntity(eq(boat), eq(plugin), any(), any(), anyLong()))
                    .thenThrow(new IllegalStateException("scheduler unavailable"));
            new VehicleSetBackTask(boat, player, new Location(null, 2, 0, 0), false).run();
            assertFalse(data.isVehicleSetBack);
            assertNull(data.vehicleSetBackTaskId);
            assertNull(data.vehicleSetBackTimeoutTaskId);
            logs.verify(() -> StaticLog.logSevere(any(Throwable.class)));
        }
    }

    @Test
    public void teleportStartupFailureCancelsTimeoutAndClearsPendingCorrection() {
        final Entity boat = mock(Entity.class);
        final Player player = mock(Player.class);
        final Plugin plugin = mock(Plugin.class);
        final PluginManager plugins = mock(PluginManager.class);
        final IPlayerData pData = mock(IPlayerData.class);
        final MovingData data = mock(MovingData.class);
        final Object timeout = new Object();
        when(boat.getType()).thenReturn(EntityType.BOAT);
        when(boat.getPassengers()).thenReturn(List.of(player));
        when(player.getLocation()).thenReturn(new Location(null, 0, 0, 0));
        when(plugins.getPlugin("NoCheatPlus")).thenReturn(plugin);
        when(pData.getGenericInstance(MovingData.class)).thenReturn(data);
        try (var bukkit = mockStatic(Bukkit.class); var players = mockStatic(DataManager.class);
                var folia = mockStatic(Folia.class); var logs = mockStatic(StaticLog.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
            players.when(() -> DataManager.getPlayerData(player)).thenReturn(pData);
            folia.when(Folia::isFoliaServer).thenReturn(true);
            folia.when(() -> Folia.runSyncDelayedTaskForEntity(eq(boat), eq(plugin), any(), any(), anyLong()))
                    .thenReturn(timeout);
            folia.when(() -> Folia.teleportEntityWithPassengersAsync(eq(boat), any(Location.class), any()))
                    .thenThrow(new IllegalStateException("teleport startup failed"));
            new VehicleSetBackTask(boat, player, new Location(null, 2, 0, 0), false).run();
            assertFalse(data.isVehicleSetBack);
            assertNull(data.vehicleSetBackTaskId);
            assertNull(data.vehicleSetBackTimeoutTaskId);
            folia.verify(() -> Folia.cancelTask(timeout));
            logs.verify(() -> StaticLog.logSevere(any(Throwable.class)));
        }
    }

    private void correction(final boolean success, final boolean stillMounted) {
        correction(success, stillMounted, Completion.NORMAL);
    }

    private void correction(final boolean success, final boolean stillMounted, final Completion completion) {
        final Entity boat = mock(Entity.class);
        final Player player = mock(Player.class);
        final Plugin plugin = mock(Plugin.class);
        final PluginManager plugins = mock(PluginManager.class);
        final IPlayerData pData = mock(IPlayerData.class);
        final MovingData data = mock(MovingData.class);
        final MovingConfig config = mock(MovingConfig.class);
        final AuxMoving aux = mock(AuxMoving.class);
        final NoCheatPlusAPI api = mock(NoCheatPlusAPI.class);
        final Location destination = new Location(null, 1, 2, 3);
        final CompletableFuture<Boolean> teleport = new CompletableFuture<>();
        final AtomicReference<Consumer<Object>> callback = new AtomicReference<>();
        final Object replacement = new Object();
        final Object timeoutTask = new Object();
        when(boat.getType()).thenReturn(EntityType.BOAT);
        when(boat.getPassengers()).thenReturn(List.of(player));
        when(boat.getLocation()).thenReturn(destination);
        when(player.getLocation()).thenReturn(destination);
        when(plugins.getPlugin("NoCheatPlus")).thenReturn(plugin);
        when(pData.getGenericInstance(MovingData.class)).thenReturn(data);
        when(pData.getGenericInstance(MovingConfig.class)).thenReturn(config);
        when(api.getGenericInstance(AuxMoving.class)).thenReturn(aux);
        try (var bukkit = mockStatic(Bukkit.class); var players = mockStatic(DataManager.class);
                var provider = mockStatic(NCPAPIProvider.class); var folia = mockStatic(Folia.class);
                var logs = mockStatic(StaticLog.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
            players.when(() -> DataManager.getPlayerData(player)).thenReturn(pData);
            provider.when(NCPAPIProvider::getNoCheatPlusAPI).thenReturn(api);
            folia.when(Folia::isFoliaServer).thenReturn(true);
            folia.when(() -> Folia.teleportEntityWithPassengersAsync(eq(boat), eq(destination), any()))
                    .thenReturn(teleport);
            folia.when(() -> Folia.runSyncDelayedTaskForEntity(eq(boat), eq(plugin), any(), any(), anyLong()))
                    .thenReturn(timeoutTask);
            folia.when(() -> Folia.runSyncTaskForEntity(eq(boat), eq(plugin), any(), any()))
                    .thenAnswer(call -> {
                        if (completion == Completion.UNSCHEDULED) return null;
                        if (completion == Completion.REPLACED) {
                            data.vehicleSetBackTaskId = replacement;
                            return null;
                        }
                        if (completion == Completion.RETIRED) {
                            final Runnable retired = call.getArgument(3);
                            retired.run();
                            return null;
                        }
                        callback.set(call.getArgument(2));
                        return new Object();
                    });
            new VehicleSetBackTask(boat, player, destination, false).run();
            assertTrue(data.isVehicleSetBack);
            assertSame(teleport, data.vehicleSetBackTaskId);
            verifyNoInteractions(aux);
            if (!stillMounted) when(boat.getPassengers()).thenReturn(List.of());
            teleport.complete(success);
            if (callback.get() != null) {
                assertTrue(data.isVehicleSetBack);
                assertSame(teleport, data.vehicleSetBackTaskId);
                if (completion == Completion.MOVED) {
                    when(boat.getLocation()).thenReturn(new Location(null, 1.005, 2, 3));
                }
                callback.get().accept(null);
            }
            if (completion == Completion.REPLACED) {
                assertTrue(data.isVehicleSetBack);
                assertSame(replacement, data.vehicleSetBackTaskId);
                verifyNoInteractions(aux);
                return;
            }
            assertFalse(data.isVehicleSetBack);
            assertNull(data.vehicleSetBackTaskId);
            if (success && stillMounted && callback.get() != null) {
                verify(aux).resetVehiclePositions(boat, destination, data, config);
                verify(aux).resetPositionsAndMediumProperties(player, destination, data, config);
                assertEquals(0, data.timeSinceSetBack);
                verify(player).setRotation(0f, 0f);
            }
            else verifyNoInteractions(aux);
            verify(boat, never()).eject();
            verify(player, never()).teleport(any(Location.class));
        }
    }
}
