package fr.neatmonster.nocheatplus.checks.moving.vehicle;

import java.time.LocalDate;

/** Small reset-on-date counter, scoped to this server process. */
final class BoatPhasingDailyCounter {
    private LocalDate date;
    private long count;

    synchronized long increment(LocalDate today) {
        if (!today.equals(date)) {
            date = today;
            count = 0;
        }
        return ++count;
    }
}
