package fr.neatmonster.nocheatplus.checks.moving.util;

import java.util.function.BiPredicate;

/** Coalesces cross-thread requests without accessing player movement state. */
public final class SetBackRequest<T> {
    private T owner;
    private boolean pending;
    private long revision;
    private Object scheduled;

    /** Bind tasks to one connection, even when its player data survives a reconnect. */
    public synchronized void bind(final T owner) {
        if (this.owner == owner) return;
        this.owner = owner;
        pending = false;
        scheduled = null;
    }

    public synchronized void retire(final T owner) {
        if (this.owner == owner) bind(null);
    }

    public synchronized void request() {
        pending = true;
        ++revision;
    }

    public synchronized boolean isPending() {
        return pending || scheduled != null;
    }

    public void dispatch(final T owner, final BiPredicate<Runnable, Runnable> scheduler, final Runnable action) {
        final Object token;
        final long dispatchedRevision;
        synchronized (this) {
            if (owner == null || this.owner != owner || !pending || scheduled != null) return;
            scheduled = token = new Object();
            dispatchedRevision = revision;
        }
        try {
            if (!scheduler.test(() -> {
                synchronized (this) {
                    if (scheduled != token) return;
                    pending = false;
                }
                try {
                    action.run();
                } catch (RuntimeException | Error failure) {
                    retry(token);
                    throw failure;
                } finally {
                    release(token);
                }
            }, () -> retireTask(token, dispatchedRevision))) {
                release(token); // Retry rejection on the next global tick.
            }
        } catch (RuntimeException | Error failure) {
            release(token);
            throw failure;
        }
    }

    private synchronized void retry(final Object token) {
        if (scheduled == token) pending = true;
    }

    private synchronized void release(final Object token) {
        if (scheduled == token) scheduled = null;
    }

    private synchronized void retireTask(final Object token, final long dispatchedRevision) {
        if (scheduled != token) return;
        scheduled = null;
        if (revision == dispatchedRevision) pending = false;
    }
}
