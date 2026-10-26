/*
 * This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 */
package fr.neatmonster.nocheatplus.test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.bukkit.Material;
import org.junit.Test;

import fr.neatmonster.nocheatplus.compat.BridgeMaterial;
import fr.neatmonster.nocheatplus.utilities.map.BlockProperties;
import fr.neatmonster.nocheatplus.utilities.map.BlockProperties.MaterialBase;
import fr.neatmonster.nocheatplus.utilities.map.MaterialUtil;

public class TestBlockProperties {

    @Test
    public void testLegacyBreakingTimesIncludeCopperTier() {
        assertArrayEquals(new long[] {1000, 2000, 3000, 4000, 4000, 5000, 6000, 7000},
                BlockProperties.secToMs(1, 2, 3, 4, 5, 6, 7));
    }

    @Test
    public void testCopperToolHierarchy() {
        assertTrue(BlockProperties.isRightToolMaterial(null,
                MaterialBase.STONE, MaterialBase.COPPER, true));
        assertTrue(BlockProperties.isRightToolMaterial(null,
                MaterialBase.COPPER, MaterialBase.COPPER, true));
        assertFalse(BlockProperties.isRightToolMaterial(null,
                MaterialBase.IRON, MaterialBase.COPPER, true));
    }

    @Test
    public void testWoolShapesUseFastShearsProfileWithoutFullCubeClassification() {
        assertTrue(MaterialUtil.WOOL_SHEARS_BLOCKS.containsAll(MaterialUtil.WOOL_BLOCKS));
        assertTrue(MaterialUtil.WOOL_SHEARS_BLOCKS.containsAll(MaterialUtil.WOOL_STAIRS));
        assertTrue(MaterialUtil.WOOL_SHEARS_BLOCKS.containsAll(MaterialUtil.WOOL_SLABS));
    }

    @Test
    public void testShelfMushroomIsNotClassifiedAsBed() {
        assertFalse(MaterialUtil.BEDS.contains(BridgeMaterial.get("shelf_mushroom")));
    }

    @Test
    public void testStrawBedUsesSeparateShapeClassification() {
        final Material strawBed = BridgeMaterial.get("straw_bed");
        if (strawBed != null) {
            assertFalse(MaterialUtil.BEDS.contains(strawBed));
            assertTrue(MaterialUtil.STRAW_BEDS.contains(strawBed));
        }
    }
}
