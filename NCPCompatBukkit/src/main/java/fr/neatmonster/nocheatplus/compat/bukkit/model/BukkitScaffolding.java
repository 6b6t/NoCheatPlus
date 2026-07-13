/*
 * This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 */
package fr.neatmonster.nocheatplus.compat.bukkit.model;

import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Scaffolding;
import org.bukkit.entity.Player;

import fr.neatmonster.nocheatplus.checks.moving.MovingData;
import fr.neatmonster.nocheatplus.checks.moving.model.PlayerMoveData;
import fr.neatmonster.nocheatplus.players.DataManager;
import fr.neatmonster.nocheatplus.players.IPlayerData;
import fr.neatmonster.nocheatplus.utilities.map.BlockCache;

public class BukkitScaffolding implements BukkitShapeModel {

    private static final double[] STABLE_SHAPE = {
            0.0, 0.875, 0.0, 1.0, 1.0, 1.0,
            0.0, 0.0, 0.0, 0.125, 1.0, 0.125,
            0.875, 0.0, 0.0, 1.0, 1.0, 0.125,
            0.0, 0.0, 0.875, 0.125, 1.0, 1.0,
            0.875, 0.0, 0.875, 1.0, 1.0, 1.0
    };
    private static final double[] UNSTABLE_SHAPE = {
            0.0, 0.0, 0.0, 1.0, 0.125, 1.0,
            0.0, 0.875, 0.0, 1.0, 1.0, 1.0,
            0.0, 0.0, 0.0, 0.125, 1.0, 0.125,
            0.875, 0.0, 0.0, 1.0, 1.0, 0.125,
            0.0, 0.0, 0.875, 0.125, 1.0, 1.0,
            0.875, 0.0, 0.875, 1.0, 1.0, 1.0
    };
    private static final double[] BOTTOM_PLATE = {0.0, 0.0, 0.0, 1.0, 0.125, 1.0};
    private static final double[] UPPER_PLATE = {0.0, 0.875, 0.0, 1.0, 1.0, 1.0};
    private static final double[] BOTH_PLATES = {
            0.0, 0.875, 0.0, 1.0, 1.0, 1.0,
            0.0, 0.0, 0.0, 1.0, 0.125, 1.0
    };

    @Override
    public double[] getShape(final BlockCache blockCache, final World world,
            final int x, final int y, final int z) {
        final BlockData blockData = world.getBlockAt(x, y, z).getBlockData();
        if (!(blockData instanceof Scaffolding)) {
            return new double[] {0.0, 0.0, 0.0, 1.0, 1.0, 1.0};
        }
        final Scaffolding scaffolding = (Scaffolding) blockData;
        final boolean hasBottom = scaffolding.getDistance() != 0 && scaffolding.isBottom();
        final IPlayerData playerData = blockCache.getPlayerData();
        if (playerData == null) {
            return hasBottom ? UNSTABLE_SHAPE : STABLE_SHAPE;
        }
        final MovingData movingData = playerData.getGenericInstance(MovingData.class);
        final double lastY = getLastY(movingData);
        final Player player = DataManager.getPlayer(playerData.getPlayerId());
        if (player != null && lastY > y + 1 - 1e-5 && !player.isSneaking()) {
            return hasBottom ? BOTH_PLATES : UPPER_PLATE;
        }
        if (hasBottom && lastY > y + 0.125 - 1e-5) {
            return BOTTOM_PLATE;
        }
        return null;
    }

    @Override
    public int getFakeData(final BlockCache blockCache, final World world,
            final int x, final int y, final int z) {
        return 0;
    }

    private static double getLastY(final MovingData movingData) {
        final PlayerMoveData lastMove = movingData.playerMoves.getLatestValidMove();
        if (lastMove == null) {
            return Double.NEGATIVE_INFINITY;
        }
        return lastMove.toIsValid ? lastMove.to.getY() : lastMove.from.getY();
    }
}
