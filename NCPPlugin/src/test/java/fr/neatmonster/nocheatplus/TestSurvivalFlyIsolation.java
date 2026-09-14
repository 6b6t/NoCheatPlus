package fr.neatmonster.nocheatplus;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.*;
import org.bukkit.entity.Player;
import org.junit.Test;
import org.mockito.MockedConstruction;
import fr.neatmonster.nocheatplus.actions.ParameterName;
import fr.neatmonster.nocheatplus.checks.ViolationData;
import fr.neatmonster.nocheatplus.checks.moving.MovingConfig;
import fr.neatmonster.nocheatplus.checks.moving.MovingData;
import fr.neatmonster.nocheatplus.checks.moving.player.SurvivalFly;
import fr.neatmonster.nocheatplus.players.IPlayerData;

public class TestSurvivalFlyIsolation {
    private SurvivalFly check;

    @org.junit.Before
    public void setup() {
        NCPAPIProvider.setNoCheatPlusAPI(new PluginTests.UnitTestNoCheatPlusAPI() {
            @Override
            public fr.neatmonster.nocheatplus.compat.blocks.changetracker.BlockChangeTracker getBlockChangeTracker() {
                return null;
            }
        });
        try (org.mockito.MockedStatic<fr.neatmonster.nocheatplus.players.DataManager> ignored =
                mockStatic(fr.neatmonster.nocheatplus.players.DataManager.class)) {
            check = spy(new SurvivalFly());
        }
        doAnswer(invocation -> invocation.getArgument(0)).when(check).executeActions(any(ViolationData.class));
    }

    @Test
    public void overlappingChecksDoNotShareViolationTags() throws Exception {
        final CyclicBarrier overlap = new CyclicBarrier(2);
        final CountDownLatch firstAdded = new CountDownLatch(1);
        final ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            final Callable<Void> run = () -> {
                try (MockedConstruction<ViolationData> violations = mockConstruction(ViolationData.class, (vd, context) -> {
                    when(vd.needsParameters()).thenReturn(true);
                    // Both checks have added their own tag before either serializes it.
                    firstAdded.countDown();
                    overlap.await(5, TimeUnit.SECONDS);
                })) {
                    check.checkBed(mock(Player.class), mock(IPlayerData.class),
                            mock(MovingConfig.class), mock(MovingData.class));
                    verify(violations.constructed().get(0)).setParameter(ParameterName.TAGS, "bedfly");
                }
                return null;
            };
            final Future<Void> first = executor.submit(run);
            final Future<Void> second = executor.submit(() -> {
                assertTrue(firstAdded.await(5, TimeUnit.SECONDS));
                return run.call();
            });
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }
    @Test
    public void nestedChecksRestoreTheOuterBindingAndExceptionsRemoveIt() throws Exception {
        final var field = SurvivalFly.class.getDeclaredField("CONTEXT");
        field.setAccessible(true);
        final ScopedValue<?> context = (ScopedValue<?>) field.get(null);
        final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicReference<Object> outerBinding = new java.util.concurrent.atomic.AtomicReference<>();
        final RuntimeException failure = new RuntimeException("check failed");
        doAnswer(invocation -> {
            assertTrue(context.isBound());
            if (calls.getAndIncrement() == 0) {
                final Object outer = context.get();
                outerBinding.set(outer);
                check.checkBed(mock(Player.class), mock(IPlayerData.class),
                        mock(MovingConfig.class), mock(MovingData.class));
                assertSame(outer, context.get());
                throw failure;
            }
            assertNotSame(outerBinding.get(), context.get());
            return invocation.getArgument(0);
        }).when(check).executeActions(any(ViolationData.class));
        assertFalse(context.isBound());
        try (MockedConstruction<ViolationData> violations = mockConstruction(ViolationData.class)) {
            assertSame(failure, assertThrows(RuntimeException.class, () ->
                    check.checkBed(mock(Player.class), mock(IPlayerData.class),
                            mock(MovingConfig.class), mock(MovingData.class))));
            assertEquals(2, calls.get());
            assertFalse(context.isBound());
        }
    }

}
