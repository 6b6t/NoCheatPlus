package fr.neatmonster.nocheatplus.checks.moving.vehicle;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/** Bounds boat-phasing diagnostics to one record per player per five seconds and
 * sixty records per worker per minute. */
final class BoatPhasingLogLimiter {
    private static final long PLAYER_INTERVAL_MS = 5_000L;
    private static final long WORKER_WINDOW_MS = 60_000L;
    private static final int WORKER_LIMIT = 60;
    private static final long PLAYER_ENTRY_TTL_MS = 60 * 60_000L;

    private final Map<UUID, PlayerWindow> players = new HashMap<>();
    private long workerWindowStart;
    private int workerLines;
    private long workerSuppressed;

    synchronized Decision admit(final UUID player, final long now) {
        if (workerWindowStart == 0L || now - workerWindowStart >= WORKER_WINDOW_MS || now < workerWindowStart) {
            workerWindowStart = now;
            workerLines = 0;
        }
        if (now % 60_000L < 1_000L) {
            final Iterator<Map.Entry<UUID, PlayerWindow>> iterator = players.entrySet().iterator();
            while (iterator.hasNext()) {
                if (now - iterator.next().getValue().lastSeen > PLAYER_ENTRY_TTL_MS) iterator.remove();
            }
        }

        final PlayerWindow window = players.computeIfAbsent(player, ignored -> new PlayerWindow());
        window.lastSeen = now;
        if (window.lastLogged != Long.MIN_VALUE && now - window.lastLogged < PLAYER_INTERVAL_MS && now >= window.lastLogged) {
            window.suppressed++;
            return Decision.suppressed();
        }
        if (workerLines >= WORKER_LIMIT) {
            window.suppressed++;
            workerSuppressed++;
            return Decision.suppressed();
        }

        final long playerSuppressed = window.suppressed;
        window.suppressed = 0L;
        window.lastLogged = now;
        workerLines++;
        final long globalSuppressed = workerSuppressed;
        if (globalSuppressed > 0L && workerLines < WORKER_LIMIT) {
            // Reserve one of the minute's capped records for the aggregate.
            workerLines++;
            workerSuppressed = 0L;
        }
        else if (globalSuppressed > 0L) {
            workerSuppressed = 0L;
        }
        return new Decision(true, playerSuppressed, globalSuppressed);
    }

    static final class Decision {
        final boolean admitted;
        final long playerSuppressed;
        final long workerSuppressed;

        private Decision(final boolean admitted, final long playerSuppressed, final long workerSuppressed) {
            this.admitted = admitted;
            this.playerSuppressed = playerSuppressed;
            this.workerSuppressed = workerSuppressed;
        }

        static Decision suppressed() { return new Decision(false, 0L, 0L); }
    }

    private static final class PlayerWindow {
        long lastLogged = Long.MIN_VALUE;
        long lastSeen;
        long suppressed;
    }
}
