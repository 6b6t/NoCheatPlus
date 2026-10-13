package fr.neatmonster.nocheatplus;

import static org.junit.Assert.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.junit.Test;
import fr.neatmonster.nocheatplus.telemetry.*;

/** Real begin/end observer path; lightweight API stubs avoid Mockito instrumentation in timings. */
public class TestTelemetryHotPath {
    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
    private static void move(PlayerMoveEvent[] events, int i) {
        PlayerMoveEvent event = events[i % events.length];
        event.getFrom().setX(i % 128); event.getTo().setX(i % 128 + 1);
        Telemetry.begin(event); Telemetry.end(event);
    }
    @Test public void benchmarkProcessedMovesIncludingHistoryAndOwnership() throws Exception {
        Field serverField = Bukkit.class.getDeclaredField("server"); serverField.setAccessible(true);
        Object previous = serverField.get(null);
        UUID worldId = new UUID(3, 4);
        World world = proxy(World.class, (p,m,a) -> m.getName().equals("getUID") ? worldId : null);
        Player[] players = new Player[200];
        PlayerMoveEvent[] events = new PlayerMoveEvent[200];
        int selected = 0, realSelected = 0;
        for (int n=0;n<players.length;n++) {
            UUID id = new UUID(1,n);
            if (TelemetryRecord.sampled(id,System.currentTimeMillis(),1)) selected++;
            if (TelemetryRecord.sampled(id,System.currentTimeMillis(),10)) realSelected++;
            players[n] = proxy(Player.class, (p,m,a) -> {
                switch (m.getName()) {
                    case "getUniqueId": return id;
                    case "getWorld": return world;
                    case "getName": return "BenchmarkPlayer";
                    case "isOnGround": case "isGliding": case "isInsideVehicle": return false;
                    case "getFallDistance": return 0f;
                    default: throw new AssertionError("Unexpected hot-path API: " + m.getName());
                }
            });
            events[n] = new PlayerMoveEvent(players[n],new Location(world,1,64,1),new Location(world,2,64,1));
        }
        serverField.set(null, proxy(Server.class, (p,m,a) -> {
            if (m.getName().equals("isPrimaryThread")) return true;
            throw new AssertionError(m.getName());
        }));
        try {
            for (double percent : new double[]{0, 1, 10}) {
                TelemetryWriter writer = new TelemetryWriter(Files.createTempDirectory("ncp-hotpath").toRealPath(), "worker", 32, 0, Long.MAX_VALUE, () -> Long.MAX_VALUE, s -> {});
                TelemetryService service = new TelemetryService(writer, "worker", percent == 10 ? 1 : percent, percent == 10 ? 10 : 0, 100, new TelemetryService.BotCache(){
                    @Override Boolean lookup(UUID id){return false;}
                    @Override Boolean lookupTempbot(UUID id){return false;}
                });
                Telemetry.install(service);
                int warmup = 100000, count = 100000;
                for (int i = 0; i < warmup; i++) move(events,i);
                long best = Long.MAX_VALUE, total = 0;
                var allocations = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
                long allocatedStart = allocations.getThreadAllocatedBytes(Thread.currentThread().threadId());
                for (int round = 0; round < 5; round++) {
                    long start = System.nanoTime();
                    for (int i = 0; i < count; i++) move(events,i);
                    long elapsed = System.nanoTime() - start; total += elapsed; best = Math.min(best, elapsed);
                }
                assertEquals(warmup + 5L * count, writer.moves.get());
                assertEquals(0, writer.captureErrors.get());
                assertTrue("Warmed begin/end exceeded 3 us/move: " + best/count, best/count <= 3000);
                System.out.println("BENCH processed-move sample_percent=" + percent + " best_ns=" + best/count + " mean_ns=" + total/(5L*count) + " allocated_bytes_per_move=" + (allocations.getThreadAllocatedBytes(Thread.currentThread().threadId())-allocatedStart)/(5L*count) + " sticky_sampled_players=" + (percent == 10 ? realSelected : selected) + "/200 (real Telemetry.begin/end; varying endpoints; API stubs; no writer thread)");
            }
        } finally { Telemetry.install(null); serverField.set(null, previous); }
    }
}
