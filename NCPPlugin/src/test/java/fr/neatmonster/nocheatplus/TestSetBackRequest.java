package fr.neatmonster.nocheatplus;

import static org.junit.Assert.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import fr.neatmonster.nocheatplus.checks.moving.util.SetBackRequest;

public class TestSetBackRequest {
    private final Object player = new Object();

    private SetBackRequest<Object> newRequest() {
        final SetBackRequest<Object> request = new SetBackRequest<>();
        request.bind(player);
        return request;
    }

    @Test
    public void dispatchCoalescesAndPreservesRequestDuringExecution() {
        final SetBackRequest<Object> request = newRequest();
        final AtomicReference<Runnable> queued = new AtomicReference<>();
        final AtomicInteger runs = new AtomicInteger();
        request.request();
        request.request();
        request.dispatch(player, (run, retired) -> { queued.set(run); return true; }, () -> {
            runs.incrementAndGet();
            request.request();
        });
        request.dispatch(player, (run, retired) -> { fail("duplicate task"); return false; }, () -> {});
        assertEquals(0, runs.get());
        queued.get().run();
        assertTrue(request.isPending());
        request.dispatch(player, (run, retired) -> { queued.set(run); return true; }, runs::incrementAndGet);
        queued.get().run();
        assertEquals(2, runs.get());
        assertFalse(request.isPending());
    }

    @Test
    public void rejectedAndThrowingSchedulersPreserveTheRequest() {
        final SetBackRequest<Object> request = newRequest();
        request.request();
        request.dispatch(player, (run, retired) -> false, () -> fail("rejected task ran"));
        assertTrue(request.isPending());
        assertThrows(IllegalStateException.class, () -> request.dispatch(player, (run, retired) -> {
            throw new IllegalStateException();
        }, () -> fail("rejected task ran")));
        final AtomicInteger runs = new AtomicInteger();
        request.dispatch(player, (run, retired) -> { run.run(); return true; }, runs::incrementAndGet);
        assertEquals(1, runs.get());
        assertFalse(request.isPending());
    }

    @Test
    public void retiredTasksCannotRunOrClearNewerRequests() {
        final SetBackRequest<Object> request = newRequest();
        final AtomicReference<Runnable> queued = new AtomicReference<>();
        final AtomicReference<Runnable> retired = new AtomicReference<>();
        request.request();
        request.dispatch(player, (run, cancel) -> { queued.set(run); retired.set(cancel); return true; }, () -> fail());
        retired.get().run();
        assertFalse(request.isPending());
        request.request();
        retired.get().run();
        queued.get().run();
        assertTrue(request.isPending());
    }
    @Test
    public void actionFailureLeavesARequestToRetry() {
        final SetBackRequest<Object> request = newRequest();
        request.request();
        final AtomicReference<Runnable> queued = new AtomicReference<>();
        request.dispatch(player, (run, retired) -> { queued.set(run); return true; }, () -> {
            throw new IllegalStateException("processing failed");
        });
        assertThrows(IllegalStateException.class, () -> queued.get().run());
        assertTrue(request.isPending());
    }

    @Test
    public void retirementDoesNotConsumeRequestMadeAfterDispatch() {
        final SetBackRequest<Object> request = newRequest();
        request.request();
        final AtomicReference<Runnable> retired = new AtomicReference<>();
        request.dispatch(player, (run, cancel) -> { retired.set(cancel); return true; }, () -> fail());
        request.request();
        retired.get().run();
        assertTrue(request.isPending());
    }

    @Test
    public void reconnectInvalidatesOldActionsAndRetirementCallbacks() {
        final SetBackRequest<Object> request = newRequest();
        final AtomicReference<Runnable> queued = new AtomicReference<>();
        final AtomicReference<Runnable> retired = new AtomicReference<>();
        request.request();
        request.dispatch(player, (run, cancel) -> { queued.set(run); retired.set(cancel); return true; }, () -> fail());
        final Object reconnect = new Object();
        request.retire(player);
        request.bind(reconnect);
        request.request();
        queued.get().run();
        retired.get().run();
        request.retire(player);
        request.dispatch(player, (run, cancel) -> { fail("old connection scheduled"); return false; }, () -> fail());
        assertTrue(request.isPending());
        final AtomicInteger runs = new AtomicInteger();
        request.dispatch(reconnect, (run, cancel) -> { run.run(); return true; }, runs::incrementAndGet);
        assertEquals(1, runs.get());
        assertFalse(request.isPending());
    }

    @Test
    public void concurrentRequestWhileActionRunsIsRetained() throws Exception {
        final SetBackRequest<Object> request = newRequest();
        final var started = new java.util.concurrent.CountDownLatch(1);
        final var finish = new java.util.concurrent.CountDownLatch(1);
        final AtomicReference<Runnable> queued = new AtomicReference<>();
        request.request();
        request.dispatch(player, (run, cancel) -> { queued.set(run); return true; }, () -> {
            started.countDown();
            try {
                assertTrue(finish.await(5, java.util.concurrent.TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        });
        try (final var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            final var running = executor.submit(queued.get());
            try {
                assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
                request.request();
                request.bind(player); // A consistency refresh of the same connection keeps its work.
                request.dispatch(player, (run, cancel) -> { fail("duplicate task"); return false; }, () -> fail());
            } finally {
                finish.countDown();
            }
            running.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertTrue(request.isPending());
        request.dispatch(player, (run, cancel) -> { run.run(); return true; }, () -> {});
        assertFalse(request.isPending());
    }
}
