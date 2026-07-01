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
package fr.neatmonster.nocheatplus.components.modifier;

import org.bukkit.entity.Player;

/**
 * Encapsulate attribute access. Note that some of the methods may exclude
 * specific modifiers, or otherwise perform calculations, e.g. in order to
 * return a multiplier to be applied to typical walking speed.
 * 
 * @author asofold
 *
 */
public interface IAttributeAccess {

    /**
     * Generic speed modifier as a multiplier.
     *
     * @param player
     * @return A multiplier for the allowed speed, excluding the sprint boost
     *         modifier (!). If not possible to determine, it should
     *         Double.MAX_VALUE.
     */
    public double getSpeedAttributeMultiplier(Player player);

    /**
     * Sprint boost modifier as a multiplier.
     *
     * @param player
     * @return The sprint boost modifier as a multiplier. If not possible to
     *         determine, it should be Double.MAX_VALUE.
     */
    public double getSprintAttributeMultiplier(Player player);

    /**
     * Upstream name for {@link #getSpeedAttributeMultiplier(Player)}.
     *
     * @param player
     * @return
     */
    public default double getSpeedMultiplier(final Player player) {
        return getSpeedAttributeMultiplier(player);
    }

    /**
     * Upstream name for {@link #getSprintAttributeMultiplier(Player)}.
     *
     * @param player
     * @return
     */
    public default double getSprintMultiplier(final Player player) {
        return getSprintAttributeMultiplier(player);
    }

    /**
     * Player movement speed, including Bukkit walk speed and attributes.
     *
     * @param player
     * @return
     */
    public default float getMovementSpeed(final Player player) {
        final double multiplier = getSpeedAttributeMultiplier(player);
        return multiplier == Double.MAX_VALUE ? Float.MAX_VALUE : (player.getWalkSpeed() / 2.0f) * (float) multiplier;
    }

    /**
     * Player gravity. Vanilla default is 0.08.
     *
     * @param player
     * @return
     */
    public default double getGravity(final Player player) {
        return 0.08;
    }

    /**
     * Fall distance that does not cause fall damage. Vanilla default is 3.0.
     *
     * @param player
     * @return
     */
    public default double getSafeFallDistance(final Player player) {
        return 3.0;
    }

    /**
     * Fall damage multiplier. Vanilla default is 1.0.
     *
     * @param player
     * @return
     */
    public default double getFallDamageMultiplier(final Player player) {
        return 1.0;
    }

    /**
     * Block breaking speed multiplier. Vanilla default is 1.0.
     *
     * @param player
     * @return
     */
    public default double getBreakingSpeedMultiplier(final Player player) {
        return 1.0;
    }

    /**
     * Jump strength as a multiplier against vanilla player jump strength.
     *
     * @param player
     * @return
     */
    public default double getJumpGainMultiplier(final Player player) {
        return 1.0;
    }

    /**
     * Sneaking movement factor. Vanilla default is 0.3.
     *
     * @param player
     * @return
     */
    public default double getPlayerSneakingFactor(final Player player) {
        return 0.3;
    }

    /**
     * Maximum block interaction range. Vanilla default is 4.5.
     *
     * @param player
     * @return
     */
    public default double getPlayerMaxBlockReach(final Player player) {
        return 4.5;
    }

    /**
     * Maximum entity interaction range. Vanilla default is 3.0.
     *
     * @param player
     * @return
     */
    public default double getPlayerMaxAttackReach(final Player player) {
        return 3.0;
    }

    /**
     * Maximum step-up height. Vanilla player default is 0.6.
     *
     * @param player
     * @return
     */
    public default double getMaxStepUp(final Player player) {
        return 0.6;
    }

    /**
     * Movement efficiency through slowing blocks. Vanilla default is 0.0.
     *
     * @param player
     * @return
     */
    public default float getMovementEfficiency(final Player player) {
        return 0.0f;
    }

    /**
     * Movement efficiency while submerged in water. Vanilla default is 0.0.
     *
     * @param player
     * @return
     */
    public default float getWaterMovementEfficiency(final Player player) {
        return 0.0f;
    }

    /**
     * Underwater mining multiplier. Vanilla default is 0.2 without Aqua Affinity.
     *
     * @param player
     * @return
     */
    public default double getSubmergedMiningSpeedMultiplier(final Player player) {
        return 0.2;
    }

    /**
     * Mining efficiency addition. Vanilla default is 0.0.
     *
     * @param player
     * @return
     */
    public default double getMiningEfficiency(final Player player) {
        return 0.0;
    }

    /**
     * Entity scale multiplier. Vanilla default is 1.0.
     *
     * @param player
     * @return
     */
    public default double getEntityScale(final Player player) {
        return 1.0;
    }

}
