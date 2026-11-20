package fr.neatmonster.nocheatplus.checks.blockinteract;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.bukkit.Material;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.ItemStack;
import org.junit.Test;

import fr.neatmonster.nocheatplus.compat.Bridge1_9;

public class TestCrystalPlacementVisible {

    @Test
    public void testOffHandCrystalPlacementBases() {
        final ItemStack crystal = new ItemStack(Bridge1_9.END_CRYSTAL_ITEM);
        assertFalse(Visible.isCrystalPlacement(Action.RIGHT_CLICK_BLOCK, new ItemStack(Material.AIR), Material.OBSIDIAN));
        assertTrue(Visible.isCrystalPlacement(Action.RIGHT_CLICK_BLOCK, crystal, Material.OBSIDIAN));
        assertTrue(Visible.isCrystalPlacement(Action.RIGHT_CLICK_BLOCK, crystal, Material.BEDROCK));
        assertFalse(Visible.isCrystalPlacement(Action.RIGHT_CLICK_BLOCK, crystal, Material.CRYING_OBSIDIAN));
        assertFalse(Visible.isCrystalPlacement(Action.RIGHT_CLICK_BLOCK, crystal, Material.RESPAWN_ANCHOR));
        assertFalse(Visible.isCrystalPlacement(Action.LEFT_CLICK_BLOCK, crystal, Material.OBSIDIAN));
    }

    @Test
    public void testMainHandNonCrystalRemainsVisibleChecked() {
        assertFalse(Visible.isCrystalPlacement(Action.RIGHT_CLICK_BLOCK, new ItemStack(Material.DIAMOND_SWORD), Material.OBSIDIAN));
        assertFalse(Visible.isCrystalPlacement(Action.RIGHT_CLICK_BLOCK, new ItemStack(Material.COBBLESTONE), Material.BEDROCK));
        assertFalse(Visible.isCrystalPlacement(Action.RIGHT_CLICK_BLOCK, new ItemStack(Material.WATER_BUCKET), Material.OBSIDIAN));
    }

    @Test
    public void testEmptyMainHandNoOpAllowsOffHandCrystalEvent() {
        final ItemStack air = new ItemStack(Material.AIR);
        final ItemStack crystal = new ItemStack(Bridge1_9.END_CRYSTAL_ITEM);
        assertTrue(Visible.isEmptyMainHandForOffHandCrystal(Action.RIGHT_CLICK_BLOCK, air, crystal, Material.OBSIDIAN));
        assertTrue(Visible.isEmptyMainHandForOffHandCrystal(Action.RIGHT_CLICK_BLOCK, air, crystal, Material.BEDROCK));
        assertFalse(Visible.isEmptyMainHandForOffHandCrystal(Action.RIGHT_CLICK_BLOCK, air, crystal, Material.CHEST));
        assertFalse(Visible.isEmptyMainHandForOffHandCrystal(Action.RIGHT_CLICK_BLOCK, air, new ItemStack(Material.DIAMOND_SWORD), Material.OBSIDIAN));
        assertFalse(Visible.isEmptyMainHandForOffHandCrystal(Action.RIGHT_CLICK_BLOCK,
                new ItemStack(Material.COBBLESTONE), crystal, Material.OBSIDIAN));
        assertFalse(Visible.isEmptyMainHandForOffHandCrystal(Action.LEFT_CLICK_BLOCK, air, crystal, Material.OBSIDIAN));
    }
}
