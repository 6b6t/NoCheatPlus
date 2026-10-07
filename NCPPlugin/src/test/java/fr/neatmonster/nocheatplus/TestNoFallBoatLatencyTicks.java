package fr.neatmonster.nocheatplus;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import org.bukkit.entity.Player;
import org.junit.Test;
import fr.neatmonster.nocheatplus.checks.moving.player.NoFall;

/** The boat-top window adds one round trip (the keep-alive average) and half again for jitter, at most one second. */
public class TestNoFallBoatLatencyTicks {
    @Test public void latencyTicksFollowTheKeepAliveAverage() {
        Player p = mock(Player.class);
        int[][] expected = {{0, 0}, {-1, 0}, {1, 1}, {40, 2}, {160, 5}, {200, 6}, {300, 9}, {666, 20}, {5000, 20}};
        for (int[] e : expected) {
            when(p.getPing()).thenReturn(e[0]);
            assertEquals("ping " + e[0], e[1], NoFall.boatContactLatencyTicks(p));
        }
        when(p.getPing()).thenThrow(new UnsupportedOperationException("no ping"));
        assertEquals(0, NoFall.boatContactLatencyTicks(p));
    }
}
