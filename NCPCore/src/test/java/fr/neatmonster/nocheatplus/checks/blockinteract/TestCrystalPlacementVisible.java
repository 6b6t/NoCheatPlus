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
package fr.neatmonster.nocheatplus.checks.blockinteract;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.bukkit.Material;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.ItemStack;
import org.junit.Test;

import fr.neatmonster.nocheatplus.compat.Bridge1_9;

public class TestCrystalPlacementVisible {

    @Test
    public void skipsOnlyCrystalPlacementsOnObsidianOrBedrock() {
        final ItemStack crystal = mock(ItemStack.class);
        when(crystal.getType()).thenReturn(Bridge1_9.END_CRYSTAL_ITEM);

        assertTrue(Visible.isCrystalPlacement(Action.RIGHT_CLICK_BLOCK, crystal, Material.OBSIDIAN));
        assertTrue(Visible.isCrystalPlacement(Action.RIGHT_CLICK_BLOCK, crystal, Material.BEDROCK));
        assertFalse(Visible.isCrystalPlacement(Action.LEFT_CLICK_BLOCK, crystal, Material.OBSIDIAN));
        assertFalse(Visible.isCrystalPlacement(Action.RIGHT_CLICK_BLOCK, crystal, Material.STONE));
        assertFalse(Visible.isCrystalPlacement(Action.RIGHT_CLICK_BLOCK, null, Material.OBSIDIAN));
    }
}
