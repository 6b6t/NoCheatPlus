package fr.neatmonster.nocheatplus;

import static org.mockito.Mockito.*;
import java.lang.reflect.Method;
import java.util.concurrent.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.junit.Test;
import fr.neatmonster.nocheatplus.checks.moving.player.NoFall;

public class TestNoFallLocations {
    @Test
    public void concurrentLandingsReadTheirOwnWorld() throws Exception {
        final NoFall check = mock(NoFall.class, CALLS_REAL_METHODS);
        // Initialize the legacy scratch field when running this regression against the baseline.
        try {
            final java.lang.reflect.Field scratch = NoFall.class.getDeclaredField("useLoc2");
            scratch.setAccessible(true); scratch.set(check, new Location(null, 0, 0, 0));
        } catch (NoSuchFieldException removed) { /* Candidate uses per-call locations. */ }
        final Method land = NoFall.class.getDeclaredMethod("fallOn", Player.class, double.class);
        land.setAccessible(true);
        final CyclicBarrier barrier = new CyclicBarrier(2);
        final ExecutorService executor = Executors.newFixedThreadPool(2);
        final World[] worlds = {mock(World.class), mock(World.class)};
        final Player[] players = {mock(Player.class), mock(Player.class)};
        for (int i = 0; i < 2; i++) {
            final Location location = new Location(worlds[i], 10 + i * 100, 61, 10);
            final Block block = mock(Block.class);
            when(block.getType()).thenReturn(Material.AIR);
            when(worlds[i].getBlockAt(any(Location.class))).thenReturn(block);
            when(players[i].getLocation()).thenAnswer(call -> {
                barrier.await(5, TimeUnit.SECONDS);
                return location.clone();
            });
            when(players[i].getLocation(any(Location.class))).thenAnswer(call -> {
                final Location shared = call.getArgument(0);
                shared.setWorld(location.getWorld()); shared.setX(location.getX());
                shared.setY(location.getY()); shared.setZ(location.getZ());
                barrier.await(5, TimeUnit.SECONDS);
                return shared;
            });
        }
        try {
            final Future<?> first = executor.submit(() -> { land.invoke(check, players[0], 20.0); return null; });
            final Future<?> second = executor.submit(() -> { land.invoke(check, players[1], 20.0); return null; });
            first.get(10, TimeUnit.SECONDS); second.get(10, TimeUnit.SECONDS);
            verify(worlds[0]).getBlockAt(org.mockito.ArgumentMatchers.argThat(loc -> loc.getBlockX() == 10 && loc.getBlockY() == 60 && loc.getBlockZ() == 10));
            verify(worlds[1]).getBlockAt(org.mockito.ArgumentMatchers.argThat(loc -> loc.getBlockX() == 110 && loc.getBlockY() == 60 && loc.getBlockZ() == 10));
        } finally { executor.shutdownNow(); }
    }
}
