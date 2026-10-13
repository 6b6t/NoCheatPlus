package fr.neatmonster.nocheatplus;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

import fr.neatmonster.nocheatplus.checks.moving.MovingData;
import fr.neatmonster.nocheatplus.checks.moving.MovingListener;
import fr.neatmonster.nocheatplus.players.DataManager;
import fr.neatmonster.nocheatplus.players.IPlayerData;

/**
 * On Fox, a plugin teleport from inside a PlayerMoveEvent completes synchronously (PlayerPostTeleportEvent, no
 * Bukkit teleport event). NCP's MONITOR handling of that move must then stop, as it does after Bukkit's teleport
 * event, instead of recording the move's end point over the completed placement (which made the untracked poll
 * set the player back to where the plugin had just moved them from, again and again).
 */
public class TestTeleportDuringMoveEvent {
    private MovingListener listener;
    private Map<String, PlayerMoveEvent> processing;
    private Map<PlayerMoveEvent, Location> requested;
    private Player player;
    private World world;

    @SuppressWarnings("unchecked")
    @Before
    public void setup() throws Exception {
        listener = mock(MovingListener.class, CALLS_REAL_METHODS);
        processing = new ConcurrentHashMap<>();
        requested = new ConcurrentHashMap<>();
        final Field p = MovingListener.class.getDeclaredField("processingEvents");
        p.setAccessible(true);
        p.set(listener, processing);
        final Field r = MovingListener.class.getDeclaredField("requestedMovePositions");
        r.setAccessible(true);
        r.set(listener, requested);
        player = mock(Player.class);
        when(player.getName()).thenReturn("Mover");
        world = mock(World.class);
        when(world.getName()).thenReturn("world");
        when(world.getUID()).thenReturn(UUID.randomUUID());
    }

    private void interrupt(final Player who) {
        try {
            final java.lang.reflect.Method m = MovingListener.class.getDeclaredMethod("interruptMoveProcessing", Player.class);
            m.setAccessible(true);
            m.invoke(listener, who);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private PlayerMoveEvent moveInProcessing() {
        final PlayerMoveEvent event = new PlayerMoveEvent(player, new Location(world, 1, 64, 1),
                new Location(world, 1.2, 64, 1));
        processing.put("Mover", event);
        requested.put(event, event.getTo().clone());
        return event;
    }

    @Test
    public void completedPlacementEndsProcessingOfTheMoveInProgress() {
        final PlayerMoveEvent event = moveInProcessing();
        interrupt(player);
        assertFalse(processing.containsKey("Mover"));
        assertFalse(requested.containsKey(event));
    }

    @Test
    public void monitorThenLeavesMovementDataAlone() {
        final PlayerMoveEvent event = moveInProcessing();
        final IPlayerData pData = mock(IPlayerData.class);
        try (MockedStatic<DataManager> manager = mockStatic(DataManager.class)) {
            manager.when(() -> DataManager.getPlayerData(player)).thenReturn(pData);
            interrupt(player);
            listener.onPlayerMoveMonitor(event);
        }
        // Returned before reading any movement data or the player's position.
        verify(pData, never()).getGenericInstance(MovingData.class);
        verify(player, never()).getLocation();
    }

    @Test
    public void withoutAPlacementMonitorProcessesTheMove() {
        final PlayerMoveEvent event = moveInProcessing();
        final IPlayerData pData = mock(IPlayerData.class);
        try (MockedStatic<DataManager> manager = mockStatic(DataManager.class)) {
            manager.when(() -> DataManager.getPlayerData(player)).thenReturn(pData);
            try {
                listener.onPlayerMoveMonitor(event);
            } catch (RuntimeException expected) {
                // The bare mock environment cannot complete MONITOR processing; reaching it is the point.
            }
        }
        verify(pData, atLeastOnce()).getGenericInstance(MovingData.class);
        assertFalse(processing.containsKey("Mover"));
    }

    @Test
    public void anotherPlayersMoveIsUntouched() {
        final PlayerMoveEvent event = moveInProcessing();
        final Player other = mock(Player.class);
        when(other.getName()).thenReturn("Other");
        interrupt(other);
        assertSame(event, processing.get("Mover"));
        assertTrue(requested.containsKey(event));
    }
}
