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
package fr.neatmonster.nocheatplus.compat.bukkit.model;

import org.bukkit.World;
import org.bukkit.entity.Player;

import fr.neatmonster.nocheatplus.checks.moving.MovingData;
import fr.neatmonster.nocheatplus.checks.moving.model.PlayerMoveData;
import fr.neatmonster.nocheatplus.compat.Bridge1_17;
import fr.neatmonster.nocheatplus.players.DataManager;
import fr.neatmonster.nocheatplus.players.IPlayerData;
import fr.neatmonster.nocheatplus.utilities.map.BlockCache;

/**
 * Powder snow collision model.
 */
public class BukkitPowderSnow implements BukkitShapeModel {

    private static final double[] REDUCED_HEIGHT = new double[] {0.0, 0.0, 0.0, 1.0, 0.9, 1.0};
    private static final double[] FULL_HEIGHT = new double[] {0.0, 0.0, 0.0, 1.0, 1.0, 1.0};

    @Override
    public double[] getShape(final BlockCache blockCache, final World world, final int x, final int y, final int z) {
        final IPlayerData playerData = blockCache.getPlayerData();
        if (playerData == null) {
            return null;
        }
        final MovingData movingData = playerData.getGenericInstance(MovingData.class);
        if (movingData.noFallFallDistance > 2.5) {
            return REDUCED_HEIGHT;
        }
        final Player player = DataManager.getPlayer(playerData.getPlayerId());
        if (player != null && !player.isSneaking() && Bridge1_17.hasLeatherBootsOn(player)
                && getLastY(movingData) > y + 1 - 1e-5) {
            return FULL_HEIGHT;
        }
        return null;
    }

    @Override
    public int getFakeData(final BlockCache blockCache, final World world, final int x, final int y, final int z) {
        return 0;
    }

    private double getLastY(final MovingData movingData) {
        final PlayerMoveData lastMove = movingData.playerMoves.getLatestValidMove();
        if (lastMove == null) {
            return Double.NEGATIVE_INFINITY;
        }
        return lastMove.toIsValid ? lastMove.to.getY() : lastMove.from.getY();
    }
}
