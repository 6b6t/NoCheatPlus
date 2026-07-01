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

import org.bukkit.attribute.Attribute;

import fr.neatmonster.nocheatplus.utilities.StringUtil;

/**
 * Attribute compatibility for Bukkit versions that renamed or added constants.
 */
public final class BridgeAttribute {

    private static final Map<String, Attribute> ALL = new HashMap<String, Attribute>();

    static {
        for (final Attribute attribute : Attribute.values()) {
            ALL.put(attribute.name().toLowerCase(Locale.ROOT), attribute);
        }
    }

    public static final Attribute MOVEMENT_SPEED = getFirstNotNull("movement_speed", "generic_movement_speed");
    public static final Attribute GRAVITY = getFirst("gravity", "generic_gravity");
    public static final Attribute SCALE = getFirst("scale", "generic_scale");
    public static final Attribute MINING_EFFICIENCY = getFirst("mining_efficiency", "player_mining_efficiency");
    public static final Attribute SUBMERGED_MINING_SPEED = getFirst("submerged_mining_speed", "player_submerged_mining_speed");
    public static final Attribute MOVEMENT_EFFICIENCY = getFirst("movement_efficiency", "generic_movement_efficiency");
    public static final Attribute WATER_MOVEMENT_EFFICIENCY = getFirst("water_movement_efficiency", "generic_water_movement_efficiency");
    public static final Attribute STEP_HEIGHT = getFirst("step_height", "generic_step_height");
    public static final Attribute INTERACTION_RANGE = getFirst("block_interaction_range", "player_block_interaction_range");
    public static final Attribute ATTACK_RANGE = getFirst("entity_interaction_range", "player_entity_interaction_range");
    public static final Attribute SNEAKING_SPEED = getFirst("sneaking_speed", "player_sneaking_speed");
    public static final Attribute JUMP_STRENGTH = getFirst("jump_strength", "generic_jump_strength");
    public static final Attribute BLOCK_BREAK_SPEED = getFirst("block_break_speed", "player_block_break_speed");
    public static final Attribute FALL_DAMAGE_MULTIPLIER = getFirst("fall_damage_multiplier", "generic_fall_damage_multiplier");
    public static final Attribute SAFE_FALL_DISTANCE = getFirst("safe_fall_distance", "generic_safe_fall_distance");

    private BridgeAttribute() {
    }

    public static Attribute get(final String name) {
        return ALL.get(name.toLowerCase(Locale.ROOT));
    }

    public static Attribute getFirst(final String... names) {
        for (final String name : names) {
            final Attribute attribute = get(name);
            if (attribute != null) {
                return attribute;
            }
        }
        return null;
    }

    public static Attribute getFirstNotNull(final String... names) {
        final Attribute attribute = getFirst(names);
        if (attribute == null) {
            throw new NullPointerException("Attribute not present: " + StringUtil.join(names, ", "));
        }
        return attribute;
    }
}
