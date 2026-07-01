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

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.bukkit.entity.EntityType;

/**
 * Entity type compatibility for Bukkit versions that renamed or added constants.
 */
public final class BridgeEntityType {

    private static final Map<String, EntityType> ALL = new HashMap<String, EntityType>();

    static {
        for (final EntityType type : EntityType.values()) {
            ALL.put(type.name().toLowerCase(Locale.ROOT), type);
        }
    }

    public static final EntityType WIND_CHARGE = getFirst("wind_charge");
    public static final EntityType BREEZE_WIND_CHARGE = getFirst("breeze_wind_charge");

    private BridgeEntityType() {
    }

    public static EntityType get(final String name) {
        return ALL.get(name.toLowerCase(Locale.ROOT));
    }

    public static EntityType getFirst(final String... names) {
        for (final String name : names) {
            final EntityType type = get(name);
            if (type != null) {
                return type;
            }
        }
        return null;
    }

    public static boolean isWindCharge(final EntityType type) {
        return type != null && (type == WIND_CHARGE || type == BREEZE_WIND_CHARGE);
    }
}
