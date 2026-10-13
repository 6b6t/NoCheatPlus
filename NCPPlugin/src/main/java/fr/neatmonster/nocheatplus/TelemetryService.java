package fr.neatmonster.nocheatplus;

import java.io.IOException;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import fr.neatmonster.nocheatplus.actions.ParameterName;
import fr.neatmonster.nocheatplus.checks.ViolationData;
import fr.neatmonster.nocheatplus.checks.moving.MovingData;
import fr.neatmonster.nocheatplus.checks.moving.model.LocationData;
import fr.neatmonster.nocheatplus.compat.Folia;
import fr.neatmonster.nocheatplus.config.ConfigFile;
import fr.neatmonster.nocheatplus.players.DataManager;
import fr.neatmonster.nocheatplus.telemetry.*;

/** All snapshots are captured on the player's owning region; writer never receives Bukkit objects. */
final class TelemetryService implements Telemetry.Sink, Listener, AutoCloseable {
    private final TelemetryWriter writer;
    private final String worker;
    private final double percent, realPercent;
    private final int recentTicks;
    private final ConcurrentHashMap<UUID, State> players = new ConcurrentHashMap<>();
    private final ThreadLocal<Deque<Context>> contexts = ThreadLocal.withInitial(ArrayDeque::new);
    private final BotCache bots;
    private static final class State {
        long lastMove, lastAccepted;
        final String playerToken = UUID.randomUUID().toString();
        UUID world;
        int mace = Integer.MIN_VALUE, charge = Integer.MIN_VALUE, burst = Integer.MIN_VALUE, impulse = Integer.MIN_VALUE;
        final ArrayDeque<AcceptedMove> history = new ArrayDeque<>(20);
    }
    /** Primitive endpoints; materialize JSON lists only for sampled moves or set-backs. */
    private static final class Position {
        final double x, y, z;
        final float yaw, pitch;
        Position(Location loc) { x = loc.getX(); y = loc.getY(); z = loc.getZ(); yaw = loc.getYaw(); pitch = loc.getPitch(); }
        boolean matches(Location loc) {
            return Double.doubleToLongBits(x) == Double.doubleToLongBits(loc.getX())
                && Double.doubleToLongBits(y) == Double.doubleToLongBits(loc.getY())
                && Double.doubleToLongBits(z) == Double.doubleToLongBits(loc.getZ())
                && Float.floatToIntBits(yaw) == Float.floatToIntBits(loc.getYaw())
                && Float.floatToIntBits(pitch) == Float.floatToIntBits(loc.getPitch());
        }
        List<Object> list() { return Arrays.asList(x, y, z, yaw, pitch); }
    }
    private static final class AcceptedMove {
        final long dt;
        final Position position;
        final boolean onGround;
        AcceptedMove(long dt, Position position, boolean onGround) { this.dt = dt; this.position = position; this.onGround = onGround; }
        List<Object> list() { return Arrays.asList(dt, position.x, position.y, position.z, position.yaw, position.pitch, onGround); }
    }
    private static final class Context {
        final PlayerMoveEvent event;
        final Player player;
        final boolean vehicle;
        final long id;
        final Position from, to;
        boolean incident;
        Context(PlayerMoveEvent e, long id) { this.id = id; event = e; player = e.getPlayer(); vehicle = false; from = new Position(e.getFrom()); to = new Position(e.getTo()); }
        Context(Player p, Location f, Location t, long id) { this.id = id; event = null; player = p; vehicle = true; from = new Position(f); to = new Position(t); }
    }
    static TelemetryService start(JavaPlugin plugin, ConfigFile config) {
        if (!config.getBoolean("telemetry.enabled", false)) return null;
        TelemetryService service = null;
        try {
            String worker = workerIdentity();
            double percent = config.getDouble("telemetry.sample-percent", 1.0);
            double realPercent = config.getDouble("telemetry.move-sample-real-percent", 0.0);
            int capacity = config.getInt("telemetry.queue", 50000);
            long free = config.getLong("telemetry.min-free-bytes", 20000000000L);
            long cap = config.getLong("telemetry.max-folder-bytes", 5000000000L);
            int recent = config.getInt("telemetry.recent-ticks", 100);
            if (!validPercent(percent) || !validPercent(realPercent) || capacity > 1000000 || recent < 1) throw new IllegalArgumentException("Invalid telemetry config");
            Path folder = resolveFolder(Path.of(""), config.getString("telemetry.folder", "ncp-telemetry"));
            TelemetryWriter writer = new TelemetryWriter(folder, worker, capacity, free, cap, null, message -> plugin.getLogger().warning(message));
            service = new TelemetryService(writer, worker, percent, realPercent, recent, new BotCache());
            writer.start(); plugin.getServer().getPluginManager().registerEvents(service, plugin); Telemetry.install(service);
            plugin.getLogger().info("Telemetry enabled for Fox worker " + worker + " at " + folder);
            return service;
        } catch (Exception | LinkageError e) {
            if (service != null) service.close();
            plugin.getLogger().warning("Telemetry remains disabled: " + e.getClass().getSimpleName() + ": " + e.getMessage()); return null;
        }
    }
    /** Resolve against the worker server working directory (its data-volume root). */
    static Path resolveFolder(Path serverRoot, String configured) {
        if (configured == null || configured.trim().isEmpty()) throw new IllegalArgumentException("Telemetry folder is empty");
        Path root = serverRoot.toAbsolutePath().normalize();
        Path folder = root.resolve(configured).normalize();
        if (folder.equals(root) || !folder.startsWith(root) || folder.startsWith(root.resolve("plugins"))) {
            throw new IllegalArgumentException("Telemetry folder must be below server data root and outside plugins/");
        }
        return folder;
    }
    TelemetryService(TelemetryWriter writer, String worker, double percent, int recentTicks, BotCache bots) {
        this(writer, worker, percent, 0, recentTicks, bots);
    }
    static boolean validPercent(double value) { return Double.isFinite(value) && value >= 0 && value <= 100; }
    TelemetryService(TelemetryWriter writer, String worker, double percent, double realPercent, int recentTicks, BotCache bots) {
        if (!validPercent(percent) || !validPercent(realPercent)) throw new IllegalArgumentException("Invalid telemetry sample percent");
        this.writer = writer; this.worker = worker; this.percent = percent; this.realPercent = realPercent; this.recentTicks = recentTicks; this.bots = bots;
    }
    static String workerIdentity() throws ReflectiveOperationException {
        // Runtime name is --worker-name, NOT Kubernetes hostname, node name, or geographic region.
        String value = (String) Class.forName("net.blockhost.fox.worker.Worker").getMethod("getWorkerName").invoke(null);
        String env = System.getenv("FOX_WORKER_NAME");
        return validateWorkerIdentity(value, env);
    }
    static String validateWorkerIdentity(String runtime, String env) {
        if (runtime == null || !runtime.matches("[A-Za-z0-9_-]{1,100}")) throw new IllegalArgumentException("Fox runtime worker identity unavailable");
        if (env != null && !env.equals(runtime)) throw new IllegalArgumentException("Fox runtime/env worker identity mismatch");
        return runtime;
    }
    private boolean owned(Player p) {
        if (Folia.isOwnedByCurrentRegion(p)) return true;
        writer.captureErrors.incrementAndGet(); return false;
    }
    private State state(Player p) {
        State state = players.computeIfAbsent(p.getUniqueId(), id -> new State());
        UUID world = p.getWorld().getUID();
        if (state.world != null && !state.world.equals(world)) { state.history.clear(); state.lastAccepted = 0; state.lastMove = 0; }
        state.world = world; return state;
    }
    private Context context(Player p) {
        for (Context c : contexts.get()) if (c.player == p) return c;
        return null;
    }
    @Override public void activity(Player p, String kind) {
        if (owned(p) && kind.equals("wind-charge-impulse")) state(p).impulse = p.getTicksLived();
    }
    @Override public void error() { writer.captureErrors.incrementAndGet(); }
    @Override public void beginVehicle(Player p, Location from, Location to) {
        long id = writer.moves.incrementAndGet();
        if (owned(p)) contexts.get().push(new Context(p, from, to, id));
    }
    @Override public void endVehicle(Player p) {
        Deque<Context> stack = contexts.get(); Context c = stack.peek();
        if (c == null || !c.vehicle || c.player != p) return;
        stack.pop();
        if (!owned(p)) return;
        // Propagate an incident to an enclosing PlayerMoveEvent so it cannot be counted twice.
        Context outer = context(p); if (c.incident && outer != null) outer.incident = true;
        finish(p, c, c.incident);
    }
    @Override public void begin(PlayerMoveEvent event) {
        long id = writer.moves.incrementAndGet();
        if (!owned(event.getPlayer())) return;
        contexts.get().push(new Context(event, id));
    }
    @Override public void end(PlayerMoveEvent event) {
        Deque<Context> stack = contexts.get(); Context c = null;
        for (Iterator<Context> it = stack.iterator(); it.hasNext();) {
            Context next = it.next(); if (next.event == event) { c = next; it.remove(); break; }
        }
        if (c == null || !owned(event.getPlayer())) return;
        boolean corrected = event.isCancelled() || event.getTo() == null || !c.to.matches(event.getTo());
        finish(event.getPlayer(), c, corrected);
    }
    private void finish(Player p, Context c, boolean corrected) {
        State s = state(p); long now = System.currentTimeMillis();
        // Nested UUID/hour cohorts preserve the general sample; only known non-bots get the higher rate.
        double samplePercent = realPercent > percent && Boolean.FALSE.equals(bots.bot(p.getUniqueId(), now)) ? realPercent : percent;
        if (TelemetryRecord.sampled(p.getUniqueId(), now, samplePercent)) {
            Map<String, Object> r = base("move", now, p);
            r.put("move_id", c.id);
            r.put("movement_kind", c.vehicle ? "vehicle" : "player");
            r.put("dt", s.lastMove == 0 ? 0 : now - s.lastMove); r.put("pos", relativePosition(c.to.list(), c.from.list()));
            r.put("accepted", !corrected && !c.incident); r.put("flags", compactFlags(p)); r.put("sample_percent", samplePercent);
            writer.offer(new TelemetryRecord(now, r));
        }
        s.lastMove = now;
        if (!corrected && !c.incident) {
            AcceptedMove h = new AcceptedMove(s.lastAccepted == 0 ? 0 : now - s.lastAccepted, c.to, p.isOnGround());
            if (s.history.size() == 20) s.history.removeFirst(); s.history.addLast(h);
            s.lastAccepted = now;
        }
    }
    @Override public void cancel(ViolationData vd) {
        String check = vd.check.getType().name().toLowerCase(Locale.ROOT);
        if (!(check.startsWith("moving") || check.startsWith("fight") || check.startsWith("blockinteract"))) return;
        writer.countSetback(check);
        if (!owned(vd.player)) return;
        Context c = context(vd.player); if (c != null) c.incident = true;
        try { captureSetback(vd.player, check, vd.getTelemetryTags() == null ? vd.getParameter(ParameterName.TAGS) : vd.getTelemetryTags(), vd.vL, c, System.currentTimeMillis(), vd.getTelemetryBlocksFrom(), vd.getTelemetryBlocksTo(), vd.getTelemetryFrom(), vd.getTelemetryTo()); }
        catch (RuntimeException | LinkageError e) { writer.captureErrors.incrementAndGet(); }
    }
    @Override public void setback(Player player, String reason) { setback(player, reason, null); }
    @Override public void setback(Player player, String reason, Location destination) {
        if (!owned(player)) return;
        Context c = context(player);
        if (c != null && c.incident) return;
        if (c != null) c.incident = true;
        String check = reason.startsWith("nofall") ? "moving.nofall" : reason.startsWith("vehicle") ? "moving.vehicle" : reason.startsWith("fight") ? "fight.listener"
            : reason.startsWith("blockinteract") ? "blockinteract.listener" : "moving.listener";
        writer.countSetback(check);
        try {
            captureSetback(player, check, reason, null, c, System.currentTimeMillis(), null, null,
                destination == null ? null : position(player.getLocation()), destination == null ? null : position(destination));
        } catch (RuntimeException | LinkageError e) { writer.captureErrors.incrementAndGet(); }
    }
    private void captureSetback(Player p, String check, String tags, Double vl, Context c, long now, Map<String, Object> blocksFrom, Map<String, Object> blocksTo, List<Object> fromOverride, List<Object> toOverride) {
        State state = state(p);
        Map<String, Object> r = base("setback", now, p);
        List<Object> to = toOverride != null ? toOverride : c == null ? position(p.getLocation()) : c.to.list();
        List<Object> from = fromOverride != null ? fromOverride : c == null && !state.history.isEmpty() ? state.history.getLast().position.list() : c == null ? to : c.from.list();
        // A fresh integer block frame retains the fractional position needed to interpret cells.
        List<Object> origin = Arrays.asList(Math.floor(number(from, 0)), Math.floor(number(from, 1)), Math.floor(number(from, 2)), 0d, 0d);
        boolean policyBlocks = "nofall-native-air-landing".equals(tags) || "owner-correction".equals(tags) || "poll-correction".equals(tags);
        if (policyBlocks) {
            World world = p.getWorld();
            blocksFrom = policySnapshot(world, from);
            blocksTo = policySnapshot(world, to);
        }
        r.put("move_id", c == null ? null : c.id);
        r.put("movement_kind", c == null ? "outside-move" : c.vehicle ? "vehicle" : "player");
        r.put("check", check); r.put("tags", tags); r.put("vl", vl); r.put("from", relativePosition(from, origin)); r.put("to", relativePosition(to, origin));
        double dx = number(to, 0) - number(from, 0), dy = number(to, 1) - number(from, 1), dz = number(to, 2) - number(from, 2);
        long dt = state.lastMove == 0 ? 0 : now - state.lastMove;
        r.put("dt", dt); r.put("h_distance", Math.hypot(dx, dz)); r.put("v_distance", dy);
        r.put("h_speed", dt > 0 ? Math.hypot(dx, dz) * 1000 / dt : null); r.put("v_speed", dt > 0 ? dy * 1000 / dt : null);
        r.put("ping", p.getPing()); r.put("game_mode", p.getGameMode().name()); r.put("flags", fullFlags(p, state));
        r.put("region", RegionHealth.capture());
        List<Object> history = new ArrayList<>(state.history.size());
        for (AcceptedMove move : state.history) {
            List<Object> pos = relativePosition(move.position.list(), origin);
            history.add(Arrays.asList(move.dt, pos.get(0), pos.get(1), pos.get(2), pos.get(3), pos.get(4), move.onGround));
        }
        r.put("history", history);
        // Every neighbourhood is expressed in the same anonymous block frame as the endpoints/history.
        r.put("blocks_from", relativeBlocks(blocksFrom, from, origin, policyBlocks)); r.put("blocks_to", relativeBlocks(blocksTo, to, origin, policyBlocks)); r.put("blocks_skipped", blocksFrom == null || blocksTo == null ? ((policyBlocks || check.equals("moving_creativefly")) ? "neighbourhood-unowned-or-unloaded" : "neighbourhood-not-in-existing-check-snapshot") : null);
        writer.offer(new TelemetryRecord(now, r));
    }
    private Map<String, Object> base(String kind, long now, Player p) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("v", 2); r.put("kind", kind); r.put("time", now); r.put("worker", worker); r.put("session", writer.session);
        r.put("player", state(p).playerToken); r.put("bot", bots.bot(p.getUniqueId(), now));
        r.put("bot_meta", bots.metaBot(p.getUniqueId(), now)); r.put("tempbot", bots.tempbot(p.getUniqueId(), now));
        return r;
    }
    /** A failed endpoint must not discard the incident or the other endpoint. */
    private Map<String, Object> policySnapshot(World world, List<Object> endpoint) {
        try { return LoadedBlockSnapshot.captureRelative(new Location(world, number(endpoint, 0), number(endpoint, 1), number(endpoint, 2))); }
        catch (RuntimeException | LinkageError e) { writer.captureErrors.incrementAndGet(); return null; }
    }
    private static double rounded(double value) { return Math.rint(value * 1000) / 1000; }
    private static List<Object> relativePosition(List<Object> pos, List<Object> origin) {
        return Arrays.asList(rounded(number(pos, 0) - number(origin, 0)), rounded(number(pos, 1) - number(origin, 1)),
            rounded(number(pos, 2) - number(origin, 2)), rounded(number(pos, 3)), rounded(number(pos, 4)));
    }
    /** Check snapshots stay untouched; strip their absolute block origins in the observer copy. */
    private static Map<String, Object> relativeBlocks(Map<String, Object> blocks, List<Object> endpoint, List<Object> frame, boolean alreadyRelative) {
        if (blocks == null || blocks.isEmpty()) return null;
        Map<String, Object> copy = new LinkedHashMap<>(blocks);
        List<?> origin = (List<?>) blocks.get("origin");
        copy.put("origin", Arrays.asList(
            (alreadyRelative ? Math.floor(number(endpoint, 0)) - 1 : ((Number)origin.get(0)).doubleValue()) - number(frame, 0),
            (alreadyRelative ? Math.floor(number(endpoint, 1)) - 1 : ((Number)origin.get(1)).doubleValue()) - number(frame, 1),
            (alreadyRelative ? Math.floor(number(endpoint, 2)) - 1 : ((Number)origin.get(2)).doubleValue()) - number(frame, 2)));
        return copy;
    }
    private List<Object> compactFlags(Player p) {
        return Arrays.asList(p.isOnGround(), p.isGliding(), p.isInsideVehicle(), p.getFallDistance());
    }
    private Map<String, Object> fullFlags(Player p, State state) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("on_ground", p.isOnGround()); f.put("gliding", p.isGliding()); f.put("fall_distance", p.getFallDistance());
        f.put("elytra_equipped", p.getInventory().getChestplate() != null && p.getInventory().getChestplate().getType() == Material.ELYTRA);
        Entity vehicle = p.getVehicle(); f.put("vehicle", vehicle == null ? null : Folia.isOwnedByCurrentRegion(vehicle) ? vehicle.getType().name() : "unowned");
        MovingData data = DataManager.getPlayerData(p).getGenericInstance(MovingData.class);
        if (data != null) {
            int remaining = Math.max(0, data.fireworksBoostTickExpire - fr.neatmonster.nocheatplus.utilities.TickTask.getTick());
            f.put("rocket_remaining_ticks", remaining); f.put("rocket_remaining_moves", data.fireworksBoostDuration);
            f.put("rocket_active", data.fireworksBoostDuration > 0 && data.fireworksBoostTickExpire >= fr.neatmonster.nocheatplus.utilities.TickTask.getTick());
            LocationData loc = cVehicle(p) ? data.vehicleMoves.getCurrentMove().from : data.playerMoves.getCurrentMove().from;
            f.put("in_liquid", loc.extraPropertiesValid ? loc.inLiquid : null);
            f.put("in_bubble_column", loc.extraPropertiesValid ? loc.inBubbleStream : null);
            f.put("wind_impulse_age_ticks", age(p.getTicksLived(), state.impulse));
        } else {
            f.put("rocket_remaining_ticks", null); f.put("rocket_remaining_moves", null); f.put("rocket_active", null); f.put("in_liquid", null); f.put("in_bubble_column", null); f.put("wind_impulse_age_ticks", age(p.getTicksLived(), state.impulse));
        }
        List<Object> effects = new ArrayList<>();
        for (PotionEffect effect : p.getActivePotionEffects()) effects.add(Arrays.asList(effect.getType().getName(), effect.getAmplifier(), effect.getDuration()));
        f.put("potions", effects); f.put("held", Arrays.asList(item(p.getInventory().getItemInMainHand()), item(p.getInventory().getItemInOffHand())));
        List<String> armour = new ArrayList<>(); for (ItemStack item : p.getInventory().getArmorContents()) armour.add(item(item)); f.put("armour", armour);
        int tick = p.getTicksLived(); f.put("recent_window_ticks", recentTicks);
        f.put("mace_attack_age_ticks", age(tick, state.mace)); f.put("wind_charge_launch_age_ticks", age(tick, state.charge));
        f.put("wind_burst_attack_age_ticks", age(tick, state.burst));
        f.put("wind_burst_observation", "enchanted-mace-attack"); return f;
    }
    private boolean cVehicle(Player p) { Context c = context(p); return c != null && c.vehicle; }
    private Integer age(int tick, int previous) { long age = (long) tick - previous; return age >= 0 && age <= recentTicks ? (int) age : null; }
    private static String item(ItemStack item) { return item == null ? "AIR" : item.getType().name(); }
    private static List<Object> position(Location loc) { return Arrays.asList(loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch()); }
    private static double number(List<Object> pos, int i) { return ((Number) pos.get(i)).doubleValue(); }
    @EventHandler(priority = EventPriority.MONITOR) public void quit(PlayerQuitEvent event) { players.remove(event.getPlayer().getUniqueId()); bots.remove(event.getPlayer().getUniqueId()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void attack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player) || event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        Player p = (Player) event.getDamager(); if (!owned(p)) return;
        ItemStack held = p.getInventory().getItemInMainHand();
        if (held.getType().name().equals("MACE")) {
            State s = state(p); s.mace = p.getTicksLived();
            if (held.getEnchantments().keySet().stream().anyMatch(e -> e.getKey().getKey().equals("wind_burst"))) s.burst = s.mace;
        }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void launch(ProjectileLaunchEvent event) {
        if (!event.getEntity().getType().name().contains("WIND_CHARGE") || !(event.getEntity().getShooter() instanceof Player)) return;
        Player p = (Player) event.getEntity().getShooter(); if (owned(p)) state(p).charge = p.getTicksLived();
    }
    @Override public void close() { Telemetry.install(null); HandlerList.unregisterAll(this); writer.close(); players.clear(); bots.clear(); contexts.remove(); }

    /** Cached LuckPerms metadata, including inherited/tempbot metadata. No permission wildcard inference. */
    static class BotCache {
        private final ConcurrentHashMap<UUID, Entry> entries = new ConcurrentHashMap<>();
        private final Object luckPerms;
        private final Class<?> api, userManager, user, cachedData, metaData;
        private static final class Entry {
            final Boolean bot, metaBot, tempbot; final long expires;
            Entry(Boolean metaBot, Boolean tempbot, long expires) {
                this.metaBot = metaBot; this.tempbot = tempbot; this.expires = expires;
                bot = Boolean.TRUE.equals(metaBot) || Boolean.TRUE.equals(tempbot) ? Boolean.TRUE : metaBot != null && tempbot != null ? Boolean.FALSE : null;
            }
        }
        BotCache() {
            Object lp = null; Class<?> a = null, um = null, u = null, cd = null, md = null;
            try {
                a = Class.forName("net.luckperms.api.LuckPerms");
                lp = Class.forName("net.luckperms.api.LuckPermsProvider").getMethod("get").invoke(null);
                um = Class.forName("net.luckperms.api.model.user.UserManager"); u = Class.forName("net.luckperms.api.model.user.User");
                cd = Class.forName("net.luckperms.api.cacheddata.CachedDataManager"); md = Class.forName("net.luckperms.api.cacheddata.CachedMetaData");
            } catch (ReflectiveOperationException | LinkageError ignored) {}
            luckPerms = lp; api = a; userManager = um; user = u; cachedData = cd; metaData = md;
        }
        Boolean bot(UUID uuid, long now) {
            Entry entry = entries.get(uuid); if (entry != null && entry.expires > now) return entry.bot;
            Entry updated = new Entry(lookup(uuid), lookupTempbot(uuid), now + 30000); entries.put(uuid, updated); return updated.bot;
        }
        Boolean metaBot(UUID uuid, long now) { bot(uuid, now); return entries.get(uuid).metaBot; }
        Boolean tempbot(UUID uuid, long now) { bot(uuid, now); return entries.get(uuid).tempbot; }
        Boolean lookupTempbot(UUID uuid) {
            if (luckPerms == null) return null;
            try {
                Object manager = api.getMethod("getUserManager").invoke(luckPerms);
                Object loaded = userManager.getMethod("getUser", UUID.class).invoke(manager, uuid);
                if (loaded == null) return null;
                if ("tempbot".equals(user.getMethod("getPrimaryGroup").invoke(loaded))) return true;
                Class<?> holder = Class.forName("net.luckperms.api.model.PermissionHolder");
                Class<?> options = Class.forName("net.luckperms.api.query.QueryOptions");
                Class<?> group = Class.forName("net.luckperms.api.model.group.Group");
                Object query = holder.getMethod("getQueryOptions").invoke(loaded);
                for (Object inherited : (Collection<?>) holder.getMethod("getInheritedGroups", options).invoke(loaded, query)) {
                    if ("tempbot".equals(group.getMethod("getName").invoke(inherited))) return true;
                }
                return false;
            } catch (ReflectiveOperationException | LinkageError e) { return null; }
        }
        Boolean lookup(UUID uuid) {
            if (luckPerms == null) return null;
            try {
                Object manager = api.getMethod("getUserManager").invoke(luckPerms);
                Object loaded = userManager.getMethod("getUser", UUID.class).invoke(manager, uuid); if (loaded == null) return null;
                Object cached = user.getMethod("getCachedData").invoke(loaded);
                Object meta = cachedData.getMethod("getMetaData").invoke(cached);
                String value = (String) metaData.getMethod("getMetaValue", String.class).invoke(meta, "6b6t-bot");
                return Boolean.parseBoolean(value);
            } catch (ReflectiveOperationException | LinkageError e) { return null; }
        }
        void remove(UUID uuid) { entries.remove(uuid); }
        void clear() { entries.clear(); }
    }
}
