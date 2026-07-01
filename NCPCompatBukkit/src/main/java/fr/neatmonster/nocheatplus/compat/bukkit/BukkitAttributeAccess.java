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

import java.util.UUID;

import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.entity.Player;

import fr.neatmonster.nocheatplus.compat.AttribUtil;
import fr.neatmonster.nocheatplus.components.modifier.IAttributeAccess;
import fr.neatmonster.nocheatplus.utilities.ReflectionUtil;

public class BukkitAttributeAccess implements IAttributeAccess {

    public BukkitAttributeAccess() {
        if (ReflectionUtil.getClass("org.bukkit.attribute.AttributeInstance") == null) {
            throw new RuntimeException("Service not available.");
        }
    }

    private int operationToInt(final Operation operation) {
        switch (operation) {
            case ADD_NUMBER:
                return 0;
            case ADD_SCALAR:
                return 1;
            case MULTIPLY_SCALAR_1:
                return 2;
            default:
                throw new RuntimeException("Unknown operation: " + operation);
        }
    }

    /**
     * The first modifier with the given id that can be found, or null if none
     * is found.
     * 
     * @param attrInst
     * @param id
     * @return
     */
    private AttributeModifier getModifier(final AttributeInstance attrInst, final UUID id) {
        for (final AttributeModifier mod : attrInst.getModifiers()) {
            if (id.equals(mod.getUniqueId())) {
                return mod;
            }
        }
        return null;
    }

    private double getMultiplier(final AttributeModifier mod) {
        return AttribUtil.getMultiplier(operationToInt(mod.getOperation()), mod.getAmount());
    }

    private AttributeInstance getAttributeInstance(final Player player, final Attribute attribute) {
        if (attribute == null) {
            return null;
        }
        try {
            return player.getAttribute(attribute);
        }
        catch (RuntimeException e) {
            return null;
        }
    }

    private double getAttributeValue(final Player player, final Attribute attribute, final double fallback) {
        final AttributeInstance attrInst = getAttributeInstance(player, attribute);
        return attrInst == null ? fallback : attrInst.getValue();
    }

    private double getAttributeMultiplier(final Player player, final Attribute attribute, final double fallback) {
        final AttributeInstance attrInst = getAttributeInstance(player, attribute);
        if (attrInst == null || attrInst.getBaseValue() == 0.0) {
            return fallback;
        }
        return attrInst.getValue() / attrInst.getBaseValue();
    }

    private double clamp(final double value, final double minimum, final double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    @Override
    public double getSpeedAttributeMultiplier(final Player player) {
        final AttributeInstance attrInst = getAttributeInstance(player, BridgeAttribute.MOVEMENT_SPEED);
        if (attrInst == null || attrInst.getBaseValue() == 0.0) {
            return Double.MAX_VALUE;
        }
        final double val = attrInst.getValue() / attrInst.getBaseValue();
        final AttributeModifier mod = getModifier(attrInst, AttribUtil.ID_SPRINT_BOOST);
        return mod == null ? val : (val / getMultiplier(mod));
    }

    @Override
    public double getSprintAttributeMultiplier(final Player player) {
        final AttributeInstance attrInst = getAttributeInstance(player, BridgeAttribute.MOVEMENT_SPEED);
        if (attrInst == null) {
            return Double.MAX_VALUE;
        }
        final AttributeModifier mod = getModifier(attrInst, AttribUtil.ID_SPRINT_BOOST);
        return mod == null ? 1.0 : getMultiplier(mod);
    }

    @Override
    public double getGravity(final Player player) {
        return clamp(getAttributeValue(player, BridgeAttribute.GRAVITY, 0.08), -1.0, 1.0);
    }

    @Override
    public double getSafeFallDistance(final Player player) {
        return clamp(getAttributeValue(player, BridgeAttribute.SAFE_FALL_DISTANCE, 3.0), -1024.0, 1024.0);
    }

    @Override
    public double getFallDamageMultiplier(final Player player) {
        return clamp(getAttributeValue(player, BridgeAttribute.FALL_DAMAGE_MULTIPLIER, 1.0), 0.0, 100.0);
    }

    @Override
    public double getBreakingSpeedMultiplier(final Player player) {
        return clamp(getAttributeValue(player, BridgeAttribute.BLOCK_BREAK_SPEED, 1.0), 0.0, 1024.0);
    }

    @Override
    public double getJumpGainMultiplier(final Player player) {
        return clamp(getAttributeMultiplier(player, BridgeAttribute.JUMP_STRENGTH, 1.0), 0.0, 76.19047619047619);
    }

    @Override
    public double getPlayerSneakingFactor(final Player player) {
        return clamp(getAttributeValue(player, BridgeAttribute.SNEAKING_SPEED, 0.3), 0.0, 1.0);
    }

    @Override
    public double getPlayerMaxBlockReach(final Player player) {
        return clamp(getAttributeValue(player, BridgeAttribute.INTERACTION_RANGE, 4.5), 0.0, 64.0);
    }

    @Override
    public double getPlayerMaxAttackReach(final Player player) {
        return clamp(getAttributeValue(player, BridgeAttribute.ATTACK_RANGE, 3.0), 0.0, 64.0);
    }

    @Override
    public double getMaxStepUp(final Player player) {
        return clamp(getAttributeValue(player, BridgeAttribute.STEP_HEIGHT, 0.6), 0.0, 10.0);
    }

    @Override
    public float getMovementEfficiency(final Player player) {
        return (float) clamp(getAttributeValue(player, BridgeAttribute.MOVEMENT_EFFICIENCY, 0.0), 0.0, 1.0);
    }

    @Override
    public float getWaterMovementEfficiency(final Player player) {
        return (float) clamp(getAttributeValue(player, BridgeAttribute.WATER_MOVEMENT_EFFICIENCY, 0.0), 0.0, 1.0);
    }

    @Override
    public double getSubmergedMiningSpeedMultiplier(final Player player) {
        return clamp(getAttributeValue(player, BridgeAttribute.SUBMERGED_MINING_SPEED, 0.2), 0.0, 20.0);
    }

    @Override
    public double getMiningEfficiency(final Player player) {
        return clamp(getAttributeValue(player, BridgeAttribute.MINING_EFFICIENCY, 0.0), 0.0, 1024.0);
    }

    @Override
    public double getEntityScale(final Player player) {
        return clamp(getAttributeValue(player, BridgeAttribute.SCALE, 1.0), 0.0625, 16.0);
    }

}
