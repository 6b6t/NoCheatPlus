package fr.neatmonster.nocheatplus.checks.moving.vehicle;

import static org.junit.Assert.*;

import java.util.UUID;
import org.junit.Test;

public class TestBoatPhasingLogLimiter {
    @Test
    public void oneHundredHitsForOnePlayerInASecondEmitOneLine() {
        final BoatPhasingLogLimiter limiter = new BoatPhasingLogLimiter();
        final UUID player = UUID.randomUUID();
        int admitted = 0;
        for (int i = 0; i < 100; i++) {
            if (limiter.admit(player, 1_000L + i * 10L).admitted) admitted++;
        }
        assertEquals(1, admitted);
        final var nextWindow = limiter.admit(player, 6_100L);
        assertTrue(nextWindow.admitted);
        assertEquals(99L, nextWindow.playerSuppressed);
    }

    @Test
    public void workerCapAggregatesSuppressedLinesInNextWindow() {
        final BoatPhasingLogLimiter limiter = new BoatPhasingLogLimiter();
        int admitted = 0;
        for (int i = 0; i < 61; i++) {
            if (limiter.admit(UUID.randomUUID(), 1_000L).admitted) admitted++;
        }
        assertEquals(60, admitted);
        final BoatPhasingLogLimiter.Decision nextWindow = limiter.admit(UUID.randomUUID(), 61_001L);
        assertTrue(nextWindow.admitted);
        assertEquals(1L, nextWindow.workerSuppressed);
    }
}
