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
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Bed;

import fr.neatmonster.nocheatplus.utilities.map.BlockCache;

/** Collision bounds for the 26.3 straw bed when native shape fetching is unavailable. */
public class BukkitStrawBed implements BukkitShapeModel {

    @Override
    public double[] getShape(final BlockCache blockCache,
            final World world, final int x, final int y, final int z) {
        final Block block = world.getBlockAt(x, y, z);
        final BlockData blockData = block.getBlockData();
        final double height = blockData instanceof Bed && ((Bed) blockData).getPart() == Bed.Part.HEAD
                ? 0.3125 : 0.25;
        return new double[] {0.0, 0.0, 0.0, 1.0, height, 1.0};
    }

    @Override
    public int getFakeData(final BlockCache blockCache,
            final World world, final int x, final int y, final int z) {
        return 0;
    }
}
