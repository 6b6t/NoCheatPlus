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
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.ShulkerBox;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;

import fr.neatmonster.nocheatplus.utilities.map.BlockCache;

public class BukkitShulkerBox implements BukkitShapeModel {

    private static final double[] FULL_BLOCK = {0.0, 0.0, 0.0, 1.0, 1.0, 1.0};

    @Override
    public double[] getShape(final BlockCache blockCache, 
            final World world, final int x, final int y, final int z) {

        final Block block = world.getBlockAt(x, y, z);
        final BlockState state = block.getState();
        if (!(state instanceof ShulkerBox)) {
            return FULL_BLOCK;
        }
        if (((ShulkerBox) state).getInventory().getViewers().isEmpty()) {
            return FULL_BLOCK;
        }
        BlockFace face = BlockFace.UP;
        final BlockData blockData = block.getBlockData();
        if (blockData instanceof Directional) {
            face = ((Directional) blockData).getFacing();
        }
        switch (face) {
            case DOWN:
                return new double[] {0.0, -0.5, 0.0, 1.0, 1.0, 1.0};
            case NORTH:
                return new double[] {0.0, 0.0, -0.5, 1.0, 1.0, 1.0};
            case SOUTH:
                return new double[] {0.0, 0.0, 0.0, 1.0, 1.0, 1.5};
            case WEST:
                return new double[] {-0.5, 0.0, 0.0, 1.0, 1.0, 1.0};
            case EAST:
                return new double[] {0.0, 0.0, 0.0, 1.5, 1.0, 1.0};
            case UP:
            default:
                return new double[] {0.0, 0.0, 0.0, 1.0, 1.5, 1.0};
        }
    }

    @Override
    public int getFakeData(final BlockCache blockCache, 
            final World world, final int x, final int y, final int z) {
        return 0;
    }

}
