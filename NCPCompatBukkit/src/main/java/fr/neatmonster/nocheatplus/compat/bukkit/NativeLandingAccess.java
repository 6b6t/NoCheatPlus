package fr.neatmonster.nocheatplus.compat.bukkit;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import fr.neatmonster.nocheatplus.compat.Folia;

/** Mojang-mapped native collision and landing entry points. No player is cached. */
final class NativeLandingAccess {
    private static final ClassValue<Method> HANDLES = new ClassValue<Method>() {
        @Override protected Method computeValue(Class<?> type) {
            try { return type.getMethod("getHandle"); }
            catch (ReflectiveOperationException e) { throw new IllegalStateException("Native entity handle unavailable", e); }
        }
    };
    private static final ClassValue<Methods> METHODS = new ClassValue<Methods>() {
        @Override protected Methods computeValue(Class<?> type) {
            try { return new Methods(type); }
            catch (ReflectiveOperationException e) { throw new IllegalStateException("Native landing entry points unavailable", e); }
        }
    };
    private static final ClassValue<Field[]> OLD_POSITION = new ClassValue<Field[]>() {
        @Override protected Field[] computeValue(Class<?> type) {
            try { return new Field[] { type.getField("xOld"), type.getField("yOld"), type.getField("zOld") }; }
            catch (ReflectiveOperationException e) { throw new IllegalStateException("Native previous position unavailable", e); }
        }
    };

    private static final ClassValue<Method> ENTITY_TYPE = new ClassValue<Method>() {
        @Override protected Method computeValue(Class<?> type) {
            try { return type.getMethod("getType"); }
            catch (ReflectiveOperationException e) { throw new IllegalStateException("Native entity type unavailable", e); }
        }
    };
    private static final ClassValue<Method> UPDATE_INTERVAL = new ClassValue<Method>() {
        @Override protected Method computeValue(Class<?> type) {
            try { return type.getMethod("updateInterval"); }
            catch (ReflectiveOperationException e) { throw new IllegalStateException("Native tracking interval unavailable", e); }
        }
    };
    private static final java.util.Map<Entity, BoatHistory> BOATS = new java.util.WeakHashMap<>();
    private static final class BoatHistory {
        java.util.UUID world;
        int lastQueryTick, window;
        boolean pending;
        long taskToken;
        final java.util.List<BoatFrame> frames = new java.util.ArrayList<>();
    }
    private static final class BoatFrame {
        final int tick;
        final org.bukkit.util.BoundingBox box;
        BoatFrame(int tick, org.bukkit.util.BoundingBox box) { this.tick = tick; this.box = box; }
    }

    private static boolean sameBox(org.bukkit.util.BoundingBox a, org.bukkit.util.BoundingBox b) {
        return Math.abs(a.getMinX() - b.getMinX()) <= 0.0000001
                && Math.abs(a.getMinY() - b.getMinY()) <= 0.0000001
                && Math.abs(a.getMinZ() - b.getMinZ()) <= 0.0000001
                && Math.abs(a.getMaxX() - b.getMaxX()) <= 0.0000001
                && Math.abs(a.getMaxY() - b.getMaxY()) <= 0.0000001
                && Math.abs(a.getMaxZ() - b.getMaxZ()) <= 0.0000001;
    }

    static boolean supportsSweptBoat(Entity entity, double minX, double feetY, double minZ,
            double maxX, double maxZ) {
        return recordAndFind(entity, minX, feetY, minZ, maxX, maxZ, true);
    }

    private static boolean recordAndFind(Entity entity, double minX, double feetY, double minZ,
            double maxX, double maxZ, boolean queried) {
        if (!(entity instanceof org.bukkit.entity.Boat) || !Folia.isOwnedByCurrentRegion(entity)) return false;
        try {
            final Object nativeEntity = handle(entity);
            final Field[] fields = OLD_POSITION.get(nativeEntity.getClass());
            final org.bukkit.Location current = entity.getLocation();
            final double dx = fields[0].getDouble(nativeEntity) - current.getX();
            final double dy = fields[1].getDouble(nativeEntity) - current.getY();
            final double dz = fields[2].getDouble(nativeEntity) - current.getZ();
            if (!Double.isFinite(dx) || !Double.isFinite(dy) || !Double.isFinite(dz)) return false;
            final org.bukkit.util.BoundingBox box = entity.getBoundingBox();
            final int tick = entity.getTicksLived();
            final Object type = ENTITY_TYPE.get(nativeEntity.getClass()).invoke(nativeEntity);
            final int interval = (Integer) UPDATE_INTERVAL.get(type.getClass()).invoke(type);
            if (interval < 1 || interval > 20) return false;
            // Vanilla boats interpolate three ticks after each tracked update.
            // Retain only the real boxes that the client can still be displaying.
            final int window = interval + 3;
            final java.util.UUID world = current.getWorld() == null ? null : current.getWorld().getUID();
            final org.bukkit.util.BoundingBox previous = box.clone().shift(dx, dy, dz);
            final BoatHistory history;
            boolean supported = false;
            synchronized (BOATS) {
                history = BOATS.computeIfAbsent(entity, ignored -> new BoatHistory());
                if (!java.util.Objects.equals(history.world, world)
                        || !history.frames.isEmpty() && (history.frames.get(history.frames.size() - 1).tick > tick
                                || history.frames.get(history.frames.size() - 1).tick < tick - 1)) {
                    history.frames.clear();
                }
                history.world = world;
                history.window = window;
                if (queried) history.lastQueryTick = tick;
                // Native teleports reset old position; do not keep the abandoned path.
                for (BoatFrame frame : history.frames) {
                    if (frame.tick == tick - 1 && !sameBox(frame.box, previous)) {
                        history.frames.clear(); break;
                    }
                }
                history.frames.removeIf(frame -> frame.tick < tick - window || frame.tick >= tick - 1);
                history.frames.add(new BoatFrame(tick - 1, previous));
                history.frames.add(new BoatFrame(tick, box.clone()));
                for (int i = 1; i < history.frames.size(); i++) {
                    final org.bukkit.util.BoundingBox old = history.frames.get(i - 1).box;
                    final org.bukkit.util.BoundingBox now = history.frames.get(i).box;
                    final double descent = old.getMaxY() - now.getMaxY();
                    if (descent <= 0.00001 || feetY < now.getMaxY() - 0.00001
                            || feetY > old.getMaxY() + 0.00001) continue;
                    final double fraction = Math.max(0.0, Math.min(1.0, (feetY - now.getMaxY()) / descent));
                    final double offsetX = (old.getMinX() - now.getMinX()) * fraction;
                    final double offsetZ = (old.getMinZ() - now.getMinZ()) * fraction;
                    if (now.getMaxX() + offsetX > minX && now.getMinX() + offsetX < maxX
                            && now.getMaxZ() + offsetZ > minZ && now.getMinZ() + offsetZ < maxZ) {
                        supported = true; break;
                    }
                }
            }
            if (queried) startTracking(entity, history);
            return supported;
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("Native moving-boat contact query failed", e); }
    }

    private static void startTracking(Entity entity, BoatHistory history) {
        if (org.bukkit.Bukkit.getServer() == null) return;
        final org.bukkit.plugin.Plugin plugin = org.bukkit.Bukkit.getPluginManager().getPlugin("NoCheatPlus");
        if (plugin == null) return;
        final long token;
        synchronized (BOATS) {
            if (history.pending) return;
            history.pending = true;
            token = ++history.taskToken;
        }
        trackNext(new java.lang.ref.WeakReference<>(entity), history, plugin, token);
    }

    private static void trackNext(java.lang.ref.WeakReference<Entity> reference, BoatHistory history,
            org.bukkit.plugin.Plugin plugin, long token) {
        synchronized (BOATS) {
            if (history.taskToken != token || !history.pending) return;
        }
        final Entity entity = reference.get();
        if (entity == null) { stopTracking(history, token); return; }
        final Object task;
        try {
            task = Folia.runSyncDelayedTaskForEntity(entity, plugin, ignored -> {
                final Entity boat = reference.get();
                if (boat == null || !Folia.isOwnedByCurrentRegion(boat) || !boat.isValid()) {
                    stopTracking(history, token); return;
                }
                final int tick = boat.getTicksLived();
                synchronized (BOATS) {
                    if (history.taskToken != token || !history.pending) return;
                    if (tick < history.lastQueryTick
                            || tick - history.lastQueryTick > history.window) {
                        history.pending = false; history.taskToken++; return;
                    }
                }
                // Sample on the boat owner even while the standing client sends no
                // position packets. A weak reference and short idle expiry keep the
                // history from retaining an abandoned entity or scheduling forever.
                try {
                    recordAndFind(boat, 0, Double.NaN, 0, 0, 0, false);
                    trackNext(reference, history, plugin, token);
                } catch (RuntimeException error) {
                    stopTracking(history, token); throw error;
                }
            }, () -> stopTracking(history, token), 1L);
        } catch (RuntimeException error) {
            stopTracking(history, token); throw error;
        }
        if (task == null || task instanceof Integer && (Integer) task == -1) stopTracking(history, token);
    }

    private static void stopTracking(BoatHistory history, long token) {
        synchronized (BOATS) {
            if (history.taskToken == token) { history.pending = false; history.taskToken++; }
        }
    }

    private static final class Methods {
        final Method landing, collision, impulse;
        final Field below;
        Methods(Class<?> type) throws ReflectiveOperationException {
            landing = type.getMethod("doCheckFallDamage", double.class, double.class, double.class, boolean.class);
            below = type.getField("verticalCollisionBelow");
            impulse = type.getMethod("isIgnoringFallDamageFromCurrentImpulse");
            Class<?> entity = type;
            while (entity.getSuperclass() != null && !entity.getName().equals("net.minecraft.world.entity.Entity")) {
                entity = entity.getSuperclass();
            }
            collision = type.getMethod("canCollideWith", entity);
        }
    }
    private static Object handle(Entity entity) throws ReflectiveOperationException {
        return HANDLES.get(entity.getClass()).invoke(entity);
    }
    static boolean dispatch(Player player) {
        if (!Folia.isOwnedByCurrentRegion(player)) throw new IllegalStateException("Native landing requires entity ownership");
        try {
            Object handle = handle(player);
            // The packet descent has already been counted. Invoke the ordinary
            // landing with zero displacement, so block effects, impulse limits,
            // attributes, enchantments, immunity and cancellation stay native.
            METHODS.get(handle.getClass()).landing.invoke(handle, 0.0, 0.0, 0.0, true);
            return true;
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("Native landing failed", e); }
    }
    static boolean canCollide(Player player, Entity other) {
        if (!Folia.isOwnedByCurrentRegion(player) || !Folia.isOwnedByCurrentRegion(other)) return false;
        try {
            Object handle = handle(player);
            return (Boolean) METHODS.get(handle.getClass()).collision.invoke(handle, handle(other));
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("Native collision query failed", e); }
    }
    static boolean hasCollisionBelow(Player player) {
        if (!Folia.isOwnedByCurrentRegion(player)) return false;
        try {
            Object handle = handle(player);
            return METHODS.get(handle.getClass()).below.getBoolean(handle);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("Native collision query failed", e); }
    }
    static boolean hasNativeImpulse(Player player) {
        if (!Folia.isOwnedByCurrentRegion(player)) return false;
        try {
            Object handle = handle(player);
            return (Boolean) METHODS.get(handle.getClass()).impulse.invoke(handle);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("Native impulse query failed", e); }
    }

}
