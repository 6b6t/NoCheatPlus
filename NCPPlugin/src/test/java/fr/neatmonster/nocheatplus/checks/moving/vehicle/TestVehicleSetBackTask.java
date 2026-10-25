package fr.neatmonster.nocheatplus.checks.moving.vehicle;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
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

public class TestVehicleSetBackTask {
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

    private void correction(final boolean success, final boolean stillMounted) {
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
            folia.when(() -> Folia.runSyncTaskForEntity(eq(boat), eq(plugin), any(), any()))
                    .thenAnswer(call -> {
                        final Consumer<Object> task = call.getArgument(2);
                        task.accept(null);
                        return new Object();
                    });
            new VehicleSetBackTask(boat, player, destination, false).run();
            assertTrue(data.isVehicleSetBack);
            assertSame(teleport, data.vehicleSetBackTaskId);
            verifyNoInteractions(aux);
            if (!stillMounted) when(boat.getPassengers()).thenReturn(List.of());
            teleport.complete(success);
            assertFalse(data.isVehicleSetBack);
            assertNull(data.vehicleSetBackTaskId);
            if (success && stillMounted) {
                verify(aux).resetVehiclePositions(boat, destination, data, config);
                verify(aux).resetPositionsAndMediumProperties(player, destination, data, config);
                assertEquals(0, data.timeSinceSetBack);
            }
            else verifyNoInteractions(aux);
            verify(boat, never()).eject();
            verify(player, never()).teleport(any(Location.class));
        }
    }
}
