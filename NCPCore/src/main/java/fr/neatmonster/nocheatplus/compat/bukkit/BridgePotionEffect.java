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

import org.bukkit.potion.PotionEffectType;

import fr.neatmonster.nocheatplus.utilities.StringUtil;

/**
 * Potion effect compatibility for Bukkit versions that renamed or added effect
 * constants.
 */
public final class BridgePotionEffect {

    public static final PotionEffectType SLOWNESS = getFirstNotNull("SLOWNESS", "SLOW");
    public static final PotionEffectType HASTE = getFirstNotNull("HASTE", "FAST_DIGGING");
    public static final PotionEffectType MINING_FATIGUE = getFirstNotNull("MINING_FATIGUE", "SLOW_DIGGING");
    public static final PotionEffectType JUMP_BOOST = getFirstNotNull("JUMP_BOOST", "JUMP");
    public static final PotionEffectType LEVITATION = getFirst("LEVITATION");
    public static final PotionEffectType SLOW_FALLING = getFirst("SLOW_FALLING");
    public static final PotionEffectType WEAVING = getFirst("WEAVING");
    public static final PotionEffectType WIND_CHARGED = getFirst("WIND_CHARGED");
    public static final PotionEffectType OOZING = getFirst("OOZING");
    public static final PotionEffectType INFESTED = getFirst("INFESTED");
    public static final PotionEffectType RAID_OMEN = getFirst("RAID_OMEN");
    public static final PotionEffectType TRIAL_OMEN = getFirst("TRIAL_OMEN");

    private BridgePotionEffect() {
    }

    public static PotionEffectType getFirst(final String... names) {
        for (final String name : names) {
            final PotionEffectType type = PotionEffectType.getByName(name);
            if (type != null) {
                return type;
            }
        }
        return null;
    }

    public static PotionEffectType getFirstNotNull(final String... names) {
        final PotionEffectType type = getFirst(names);
        if (type == null) {
            throw new NullPointerException("PotionEffect not present: " + StringUtil.join(names, ", "));
        }
        return type;
    }
}
