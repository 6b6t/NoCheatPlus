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
