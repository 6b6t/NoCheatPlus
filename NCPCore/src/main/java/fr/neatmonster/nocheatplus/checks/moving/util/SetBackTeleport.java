package fr.neatmonster.nocheatplus.checks.moving.util;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import fr.neatmonster.nocheatplus.utilities.location.TrigUtil;

/** One teleport attempt. Its completion publishes a result without accessing movement state. */
public final class SetBackTeleport {
    public record Result(boolean success, Throwable error) {}

    private final Player player;
    private final long sequence;
    private final Location destination;
    private volatile Result result;

    public SetBackTeleport(final Player player, final long sequence, final Location destination) {
        this.player = player;
        this.sequence = sequence;
        this.destination = destination.clone();
    }

    public boolean isFor(final Player player) {
        return this.player == player;
    }

    public long getSequence() {
        return sequence;
    }

    public boolean isDestination(final Location location) {
        return destination.getWorld() == location.getWorld() && TrigUtil.isSamePos(destination, location);
    }

    public Result getResult() {
        return result;
    }

    public void complete(final Boolean success, final Throwable error) {
        result = new Result(error == null && Boolean.TRUE.equals(success), error);
    }
}
