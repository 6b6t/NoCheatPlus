package fr.neatmonster.nocheatplus;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.concurrent.*;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import fr.neatmonster.nocheatplus.checks.CheckType;
import fr.neatmonster.nocheatplus.checks.moving.MovingData;
import fr.neatmonster.nocheatplus.checks.moving.MovingListener;
import fr.neatmonster.nocheatplus.checks.moving.player.NoFall;
import fr.neatmonster.nocheatplus.checks.moving.player.SurvivalFly;
import fr.neatmonster.nocheatplus.compat.Bridge1_13;
import fr.neatmonster.nocheatplus.compat.Folia;
import fr.neatmonster.nocheatplus.players.DataManager;
import fr.neatmonster.nocheatplus.players.IPlayerData;
import fr.neatmonster.nocheatplus.utilities.map.BlockProperties;

public class TestPlayerLeave {
    @Before
    public void setup() {
        NCPAPIProvider.setNoCheatPlusAPI(new PluginTests.UnitTestNoCheatPlusAPI());
    }

    @Test
    public void modernLogoutSkipsBlockReadsAndCompletesCleanup() throws Exception {
        final MovingListener listener = mock(MovingListener.class, CALLS_REAL_METHODS);
        doReturn(null).when(listener).removeData(any());
        final SurvivalFly survivalFly = mock(SurvivalFly.class);
        final NoFall noFall = mock(NoFall.class);
        for (String name : new String[] {"survivalFly", "noFall"}) {
            final Field field = MovingListener.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(listener, name.equals("survivalFly") ? survivalFly : noFall);
        }
        final Player player = mock(Player.class);
        final Location location = new Location(null, 100, 80, 200);
        when(player.getLocation()).thenReturn(location);
        when(player.getLocation(nullable(Location.class))).thenReturn(location);
        final IPlayerData playerData = mock(IPlayerData.class);
        final MovingData data = mock(MovingData.class);
        data.vehicleSetPassengerTaskId = new Object();
        when(playerData.isCheckActive(CheckType.MOVING, player)).thenReturn(true);
        when(playerData.getGenericInstance(MovingData.class)).thenReturn(data);
        try (MockedStatic<DataManager> manager = mockStatic(DataManager.class);
             MockedStatic<Bridge1_13> bridge = mockStatic(Bridge1_13.class);
             MockedStatic<BlockProperties> blocks = mockStatic(BlockProperties.class);
             MockedStatic<Folia> folia = mockStatic(Folia.class)) {
            manager.when(() -> DataManager.getPlayerData(player)).thenReturn(playerData);
            bridge.when(Bridge1_13::hasIsSwimming).thenReturn(true);
            blocks.when(() -> BlockProperties.isPassable(any(Location.class)))
                    .thenThrow(new IllegalStateException("Cross-region block read"));
            listener.playerLeaves(player);
            blocks.verifyNoInteractions();
            verify(survivalFly).setReallySneaking(player, false);
            verify(noFall).onLeave(player, data, playerData);
            verify(data).onPlayerLeave();
            assertNull(data.vehicleSetPassengerTaskId);
        }
    }

    @Test
    public void overlappingNoFallLogoutsKeepTheirOwnHeight() throws Exception {
        final NoFall noFall;
        try (MockedStatic<DataManager> manager = mockStatic(DataManager.class)) {
            noFall = new NoFall();
        }
        final CyclicBarrier overlap = new CyclicBarrier(2);
        final ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            final Future<?> first = executor.submit(() -> leave(noFall, overlap, 80, 100, 20));
            final Future<?> second = executor.submit(() -> leave(noFall, overlap, 170, 200, 30));
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    private void leave(NoFall noFall, CyclicBarrier overlap, double y, double maxY, float expected) {
        final Player player = mock(Player.class);
        when(player.getLocation()).thenAnswer(call -> {
            final Location snapshot = new Location(null, 0, y, 0);
            overlap.await(5, TimeUnit.SECONDS);
            return snapshot;
        });
        when(player.getLocation(any(Location.class))).thenAnswer(call -> {
            final Location destination = call.getArgument(0);
            destination.setY(y);
            overlap.await(5, TimeUnit.SECONDS);
            return destination;
        });
        final MovingData data = mock(MovingData.class);
        data.noFallFallDistance = 10;
        data.noFallMaxY = maxY;
        noFall.onLeave(player, data, mock(IPlayerData.class));
        verify(player).setFallDistance(expected);
    }
}
