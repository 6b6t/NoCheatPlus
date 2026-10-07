/*
 * This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package fr.neatmonster.nocheatplus.compat.bukkit;

import java.lang.reflect.Method;

import org.bukkit.entity.Entity;

/**
 * The server's own collision rule between two entities (Mojang-mapped Entity.canCollideWith). If it can't be called
 * (other mappings, other server), the answer is false: no entity counts as something to stand on. Callers must own
 * both entities (Folia) before asking.
 */
final class NativeEntityCollision {

    private static final ClassValue<Method> HANDLE = new ClassValue<Method>() {
        @Override
        protected Method computeValue(final Class<?> type) {
            try {
                return type.getMethod("getHandle");
            }
            catch (ReflectiveOperationException e) {
                return null;
            }
        }
    };

    private static final ClassValue<Method> CAN_COLLIDE_WITH = new ClassValue<Method>() {
        @Override
        protected Method computeValue(final Class<?> type) {
            Class<?> base = type;
            while (base != null && !"net.minecraft.world.entity.Entity".equals(base.getName())) {
                base = base.getSuperclass();
            }
            if (base == null) {
                return null;
            }
            try {
                return type.getMethod("canCollideWith", base);
            }
            catch (ReflectiveOperationException e) {
                return null;
            }
        }
    };

    private NativeEntityCollision() {}

    /**
     * @return True only if the server says entity collides with other (so entity can stand on it).
     */
    static boolean canCollide(final Entity entity, final Entity other) {
        try {
            final Method handleEntity = HANDLE.get(entity.getClass());
            final Method handleOther = HANDLE.get(other.getClass());
            if (handleEntity == null || handleOther == null) {
                return false;
            }
            final Object nativeEntity = handleEntity.invoke(entity);
            final Object nativeOther = handleOther.invoke(other);
            if (nativeEntity == null || nativeOther == null) {
                return false;
            }
            final Method method = CAN_COLLIDE_WITH.get(nativeEntity.getClass());
            return method != null && Boolean.TRUE.equals(method.invoke(nativeEntity, nativeOther));
        }
        catch (Throwable t) {
            return false;
        }
    }
}
