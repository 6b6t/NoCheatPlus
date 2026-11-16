package fr.neatmonster.nocheatplus.checks.moving.vehicle;

/** Enforcement policy for the independent boat phasing check. */
public enum BoatPhasingMode {
    LOG_ONLY,
    ENFORCE;

    public static BoatPhasingMode parse(String value) {
        return "ENFORCE".equalsIgnoreCase(value) ? ENFORCE : LOG_ONLY;
    }

    public boolean isLogOnly() {
        return this != ENFORCE;
    }
}
