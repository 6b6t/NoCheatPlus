/*
 * This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 */
package fr.neatmonster.nocheatplus.compat.bukkit.model;

import org.bukkit.World;

import fr.neatmonster.nocheatplus.utilities.map.BlockCache;

public class BukkitComposter implements BukkitShapeModel {

    private final double[] bounds;

    public BukkitComposter(final double minY, final double sideWidth,
            final double sideHeight, final double coreHeight) {
        bounds = new double[] {
                sideWidth, minY, sideWidth, 1.0 - sideWidth, minY + coreHeight, 1.0 - sideWidth,
                0.0, minY, 0.0, 1.0, minY + sideHeight, sideWidth,
                0.0, minY, 1.0 - sideWidth, 1.0, minY + sideHeight, 1.0,
                0.0, minY, 0.0, sideWidth, minY + sideHeight, 1.0,
                1.0 - sideWidth, minY, 0.0, 1.0, minY + sideHeight, 1.0
        };
    }

    @Override
    public double[] getShape(final BlockCache blockCache, final World world,
            final int x, final int y, final int z) {
        return bounds;
    }

    @Override
    public int getFakeData(final BlockCache blockCache, final World world,
            final int x, final int y, final int z) {
        return 0;
    }
}
