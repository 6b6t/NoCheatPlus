package fr.neatmonster.nocheatplus.checks.moving.vehicle;

import static org.junit.Assert.*;

import java.time.LocalDate;
import org.junit.Test;

import fr.neatmonster.nocheatplus.config.ConfPaths;
import fr.neatmonster.nocheatplus.config.DefaultConfig;

public class TestBoatPhasingConfig {
    @Test
    public void independentBoatCheckDefaultsEnabledAndLogOnly() {
        final DefaultConfig defaults = new DefaultConfig();
        assertTrue(defaults.getBoolean(ConfPaths.MOVING_VEHICLE_BOATPHASING_ACTIVE));
        assertEquals("LOG_ONLY", defaults.getString(ConfPaths.MOVING_VEHICLE_BOATPHASING_MODE));
    }

    @Test
    public void unrecognizedModeFailsSafeToLogOnly() {
        assertEquals(BoatPhasingMode.LOG_ONLY, BoatPhasingMode.parse("invalid"));
        assertEquals(BoatPhasingMode.LOG_ONLY, BoatPhasingMode.parse(null));
        assertTrue(BoatPhasingMode.LOG_ONLY.isLogOnly());
        assertFalse(BoatPhasingMode.ENFORCE.isLogOnly());
    }

    @Test
    public void dailyCounterIncrementsAndResetsAtDateBoundary() {
        final BoatPhasingDailyCounter counter = new BoatPhasingDailyCounter();
        final LocalDate today = LocalDate.of(2026, 11, 16);
        assertEquals(1L, counter.increment(today));
        assertEquals(2L, counter.increment(today));
        assertEquals(1L, counter.increment(today.plusDays(1)));
    }
}
