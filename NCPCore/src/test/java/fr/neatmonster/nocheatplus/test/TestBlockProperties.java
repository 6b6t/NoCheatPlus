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

import org.junit.Test;

import fr.neatmonster.nocheatplus.utilities.map.BlockProperties;
import fr.neatmonster.nocheatplus.utilities.map.BlockProperties.MaterialBase;

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
}
