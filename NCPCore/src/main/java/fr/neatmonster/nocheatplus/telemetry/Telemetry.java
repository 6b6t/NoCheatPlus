package fr.neatmonster.nocheatplus.telemetry;

import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import fr.neatmonster.nocheatplus.checks.ViolationData;

/** Observational boundary. Disabled costs one volatile read; observers cannot affect enforcement. */
public final class Telemetry {
    public interface Sink {
        void begin(PlayerMoveEvent event);
        void end(PlayerMoveEvent event);
        void cancel(ViolationData data);
        void setback(Player player, String reason);
        default void setback(Player player, String reason, org.bukkit.Location destination) { setback(player, reason); }
        default void beginVehicle(Player player, org.bukkit.Location from, org.bukkit.Location to) {}
        default void endVehicle(Player player) {}
        default void activity(Player player, String kind) {}
        default void error() {}
    }
    private static volatile Sink sink;
    private Telemetry() {}
    public static void install(Sink observer) { sink = observer; }
    public static boolean enabled() { return sink != null; }
    public static void begin(PlayerMoveEvent event) {
        Sink s = sink; if (s != null) try { s.begin(event); } catch (Throwable ignored) { error(s); }
    }
    public static void end(PlayerMoveEvent event) {
        Sink s = sink; if (s != null) try { s.end(event); } catch (Throwable ignored) { error(s); }
    }
    public static void cancel(ViolationData data) {
        Sink s = sink; if (s != null && data.willCancel()) try { s.cancel(data); } catch (Throwable ignored) { error(s); }
    }
    public static void setback(Player player, String reason) {
        Sink s = sink; if (s != null) try { s.setback(player, reason); } catch (Throwable ignored) { error(s); }
    }
    public static void setback(Player player, String reason, org.bukkit.Location destination) {
        Sink s = sink; if (s != null) try { s.setback(player, reason, destination); } catch (Throwable ignored) { error(s); }
    }
    public static void beginVehicle(Player player, org.bukkit.Location from, org.bukkit.Location to) {
        Sink s = sink; if (s != null) try { s.beginVehicle(player, from, to); } catch (Throwable ignored) { error(s); }
    }
    public static void endVehicle(Player player) {
        Sink s = sink; if (s != null) try { s.endVehicle(player); } catch (Throwable ignored) { error(s); }
    }
    private static void error(Sink s) { try { s.error(); } catch (Throwable ignored) {} }
    public static void blocks(ViolationData data,
            fr.neatmonster.nocheatplus.utilities.location.RichBoundsLocation from,
            fr.neatmonster.nocheatplus.utilities.location.RichBoundsLocation to) {
        Sink s = sink; if (s == null || !data.willCancel()) return;
        try {
            if (!fr.neatmonster.nocheatplus.compat.Folia.isOwnedByCurrentRegion(data.player)) return;
            data.setTelemetryPositions(
                java.util.Collections.unmodifiableList(java.util.Arrays.asList(from.getX(), from.getY(), from.getZ(), from.getYaw(), from.getPitch())),
                java.util.Collections.unmodifiableList(java.util.Arrays.asList(to.getX(), to.getY(), to.getZ(), to.getYaw(), to.getPitch())));
            data.setTelemetryBlocks(
                CachedBlockSnapshot.capture(from.getBlockCache(), from.getBlockX(), from.getBlockY(), from.getBlockZ()),
                CachedBlockSnapshot.capture(to.getBlockCache(), to.getBlockX(), to.getBlockY(), to.getBlockZ()));
        } catch (Throwable ignored) { error(s); }
    }
    public static void creativeBlocks(ViolationData data,
            fr.neatmonster.nocheatplus.utilities.location.RichBoundsLocation from,
            fr.neatmonster.nocheatplus.utilities.location.RichBoundsLocation to) {
        Sink s = sink; if (s == null || !data.willCancel()) return;
        try {
            if (!fr.neatmonster.nocheatplus.compat.Folia.isOwnedByCurrentRegion(data.player)) return;
            data.setTelemetryPositions(
                java.util.Arrays.asList(from.getX(), from.getY(), from.getZ(), from.getYaw(), from.getPitch()),
                java.util.Arrays.asList(to.getX(), to.getY(), to.getZ(), to.getYaw(), to.getPitch()));
            data.setTelemetryBlocks(LoadedBlockSnapshot.capture(from), LoadedBlockSnapshot.capture(to));
        } catch (Throwable ignored) { error(s); }
    }
    public static void tags(ViolationData data, java.util.Collection<String> tags) {
        Sink s = sink; if (s == null) return;
        try { data.setTelemetryTags(fr.neatmonster.nocheatplus.utilities.StringUtil.join(tags, "+")); }
        catch (Throwable ignored) { error(s); }
    }
    public static void tags(ViolationData data, String tags) {
        Sink s = sink; if (s == null) return;
        try { data.setTelemetryTags(tags); } catch (Throwable ignored) { error(s); }
    }
    public static void activity(Player player, String kind) {
        Sink s = sink; if (s == null) return;
        try { s.activity(player, kind); } catch (Throwable ignored) { error(s); }
    }
}
