package fr.neatmonster.nocheatplus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import fr.neatmonster.nocheatplus.checks.ViolationData;
import fr.neatmonster.nocheatplus.checks.fight.FightConfig;
import fr.neatmonster.nocheatplus.checks.fight.FightData;
import fr.neatmonster.nocheatplus.checks.fight.Visible;
import fr.neatmonster.nocheatplus.compat.MCAccess;
import fr.neatmonster.nocheatplus.logging.StaticLog;
import fr.neatmonster.nocheatplus.players.DataManager;
import fr.neatmonster.nocheatplus.test.BlockTests;
import fr.neatmonster.nocheatplus.utilities.map.FakeBlockCache;

/**
 * Part 3 of the set-back candidate: the live fight/blockinteract Visible checks keep no per-call state in fields. One
 * instance serves every region thread; each call gets its own block cache and ray tracing.
 */
public class TestVisibleThreadSafety {

    /** The geometry of the world the current thread's player is in (per-thread "world"). */
    private static final ThreadLocal<Boolean> WALL = ThreadLocal.withInitial(() -> false);

    private Visible check;

    @Before
    public void setup() {
        StaticLog.setUseLogManager(false);
        BlockTests.initBlockProperties();
        StaticLog.setUseLogManager(true);
        final MCAccess mcAccess = mock(MCAccess.class);
        when(mcAccess.getBlockCache()).thenAnswer(invocation -> {
            final FakeBlockCache bc = new FakeBlockCache();
            if (WALL.get()) {
                for (int y = 63; y <= 68; y++) for (int z = -3; z <= 3; z++) bc.set(2, y, z, Material.STONE);
            }
            return bc;
        });
        NCPAPIProvider.getNoCheatPlusAPI().registerGenericInstance(MCAccess.class, mcAccess);
        try (MockedStatic<DataManager> ignored = mockStatic(DataManager.class)) {
            check = spy(new Visible());
        }
        doAnswer(invocation -> invocation.getArgument(0)).when(check).executeActions(any(ViolationData.class));
    }

    @Test
    public void noInstanceFields() {
        for (final Class<?> clazz : new Class<?>[] {Visible.class, fr.neatmonster.nocheatplus.checks.blockinteract.Visible.class}) {
            for (final Field field : clazz.getDeclaredFields()) {
                assertTrue(clazz.getName() + "." + field.getName() + " is per-instance state shared by all threads",
                        Modifier.isStatic(field.getModifiers()) || field.isSynthetic());
            }
        }
    }

    /** True if the hit is cancelled: eye at (0.5, 65.62, 0.5), target at (4.5, 64, 0.5). */
    private boolean hidden(final World world, final FightData data) {
        // (Location only keeps a weak reference to the world: the caller holds it.)
        final Player player = mock(Player.class);
        when(player.getEyeHeight()).thenReturn(1.62);
        final Entity target = mock(Entity.class);
        return check.check(player, new Location(world, 0.5, 64.0, 0.5), target, true, new Location(world, 4.5, 64.0, 0.5),
                data, mock(FightConfig.class));
    }

    @Test
    public void eachThreadSeesItsOwnWorld() throws Exception {
        final int n = 3000;
        final CyclicBarrier start = new CyclicBarrier(2);
        final ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            final Callable<int[]> open = () -> run(false, n, start);
            final Callable<int[]> walled = () -> run(true, n, start);
            final Future<int[]> a = executor.submit(open);
            final Future<int[]> b = executor.submit(walled);
            final int[] ra = a.get(60, TimeUnit.SECONDS);
            final int[] rb = b.get(60, TimeUnit.SECONDS);
            assertEquals("Open line of sight: never cancelled.", 0, ra[0]);
            assertEquals("Behind a wall: always cancelled.", n, rb[0]);
        }
        finally {
            executor.shutdownNow();
        }
    }

    private int[] run(final boolean wall, final int n, final CyclicBarrier start) throws Exception {
        WALL.set(wall);
        final FightData data = mock(FightData.class);
        final World world = mock(World.class);
        int cancelled = 0;
        try (MockedConstruction<ViolationData> violations = mockConstruction(ViolationData.class,
                (vd, context) -> when(vd.willCancel()).thenReturn(true))) {
            start.await(10, TimeUnit.SECONDS);
            for (int i = 0; i < n; i++) {
                if (hidden(world, data)) cancelled++;
            }
        }
        return new int[] {cancelled};
    }
}
