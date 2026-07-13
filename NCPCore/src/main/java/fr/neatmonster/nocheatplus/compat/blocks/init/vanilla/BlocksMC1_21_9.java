/*
 * This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 */
package fr.neatmonster.nocheatplus.compat.blocks.init.vanilla;

import org.bukkit.Material;

import fr.neatmonster.nocheatplus.compat.blocks.BlockPropertiesSetup;
import fr.neatmonster.nocheatplus.compat.blocks.init.BlockInit;
import fr.neatmonster.nocheatplus.config.ConfPaths;
import fr.neatmonster.nocheatplus.config.ConfigFile;
import fr.neatmonster.nocheatplus.config.ConfigManager;
import fr.neatmonster.nocheatplus.config.WorldConfigProvider;
import fr.neatmonster.nocheatplus.logging.StaticLog;
import fr.neatmonster.nocheatplus.utilities.map.BlockFlags;
import fr.neatmonster.nocheatplus.utilities.map.BlockProperties;
import fr.neatmonster.nocheatplus.utilities.map.MaterialUtil;

/**
 * Blocks introduced after Minecraft 1.21.6 and retained by the 26.x releases.
 */
public class BlocksMC1_21_9 implements BlockPropertiesSetup {

    public BlocksMC1_21_9() {
        BlockInit.assertMaterialExists("COPPER_TORCH");
    }

    @Override
    public void setupBlockProperties(final WorldConfigProvider<?> worldConfigProvider) {
        BlockInit.setInstantPassable("COPPER_TORCH");
        BlockInit.setInstantPassable("COPPER_WALL_TORCH");

        for (final Material material : MaterialUtil.COPPER_CHESTS) {
            BlockProperties.setBlockProps(material,
                    new BlockProperties.BlockProps(BlockProperties.woodPickaxe, 3f, true));
            BlockFlags.addFlags(material, BlockFlags.SOLID_GROUND);
        }
        for (final Material material : MaterialUtil.COPPER_BARS) {
            BlockProperties.setBlockProps(material,
                    new BlockProperties.BlockProps(BlockProperties.woodPickaxe, 5f, true));
            BlockFlags.addFlags(material,
                    BlockFlags.SOLID_GROUND | BlockFlags.F_THIN_FENCE | BlockFlags.F_VARIABLE);
        }
        BlockProperties.setBlockProps("IRON_CHAIN",
                new BlockProperties.BlockProps(BlockProperties.woodPickaxe, 5f, true));
        BlockFlags.addFlags("IRON_CHAIN", BlockFlags.SOLID_GROUND);
        for (final Material material : MaterialUtil.COPPER_CHAINS) {
            BlockProperties.setBlockProps(material,
                    new BlockProperties.BlockProps(BlockProperties.woodPickaxe, 5f, true));
            BlockFlags.addFlags(material, BlockFlags.SOLID_GROUND);
        }
        for (final Material material : MaterialUtil.COPPER_LIGHTNING_RODS) {
            BlockProperties.setBlockProps(material,
                    new BlockProperties.BlockProps(BlockProperties.woodPickaxe, 3f, true));
            BlockFlags.addFlags(material, BlockFlags.SOLID_GROUND);
        }
        for (final Material material : MaterialUtil.COPPER_LANTERNS) {
            BlockProperties.setBlockProps(material,
                    new BlockProperties.BlockProps(BlockProperties.woodPickaxe, 3f, false));
            BlockFlags.addFlags(material, BlockFlags.SOLID_GROUND);
        }
        for (final Material material : MaterialUtil.WOODEN_SHELVES) {
            BlockProperties.setBlockProps(material,
                    new BlockProperties.BlockProps(BlockProperties.woodAxe, 2f, false));
            BlockFlags.addFlags(material, BlockFlags.SOLID_GROUND);
        }
        for (final Material material : MaterialUtil.COPPER_GOLEM_STATUES) {
            BlockProperties.setBlockProps(material,
                    new BlockProperties.BlockProps(BlockProperties.woodPickaxe, 3f, false));
            BlockFlags.addFlags(material, BlockFlags.SOLID_GROUND);
        }

        final ConfigFile config = ConfigManager.getConfigFile();
        if (config.getBoolean(ConfPaths.BLOCKBREAK_DEBUG,
                config.getBoolean(ConfPaths.CHECKS_DEBUG, false))) {
            StaticLog.logInfo("Added block-info for Minecraft 1.21.9 blocks.");
        }
    }
}
