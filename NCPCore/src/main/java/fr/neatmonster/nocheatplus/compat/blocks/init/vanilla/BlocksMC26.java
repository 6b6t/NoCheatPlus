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
 * Blocks introduced by the year-based Minecraft 26.x releases.
 */
public class BlocksMC26 implements BlockPropertiesSetup {

    public BlocksMC26() {
        BlockInit.assertMaterialExists("GOLDEN_DANDELION");
    }

    @Override
    public void setupBlockProperties(final WorldConfigProvider<?> worldConfigProvider) {
        BlockInit.setInstantPassable("GOLDEN_DANDELION");
        for (final Material material : MaterialUtil.CINNABAR_BLOCKS) {
            BlockProperties.setBlockProps(material,
                    new BlockProperties.BlockProps(BlockProperties.woodPickaxe, 1.5f, true));
            BlockFlags.addFlags(material, BlockFlags.SOLID_GROUND);
        }
        for (final Material material : MaterialUtil.SULFUR_BLOCKS) {
            BlockProperties.setBlockProps(material,
                    new BlockProperties.BlockProps(BlockProperties.woodPickaxe, 1.5f, true));
            BlockFlags.addFlags(material, BlockFlags.SOLID_GROUND);
        }
        BlockFlags.addFlags("SULFUR_SPIKE", BlockFlags.SOLID_GROUND | BlockFlags.F_VARIABLE);
        BlockProperties.setBlockProps("SULFUR_SPIKE",
                new BlockProperties.BlockProps(BlockProperties.woodPickaxe, 1.5f));

        final ConfigFile config = ConfigManager.getConfigFile();
        if (config.getBoolean(ConfPaths.BLOCKBREAK_DEBUG,
                config.getBoolean(ConfPaths.CHECKS_DEBUG, false))) {
            StaticLog.logInfo("Added block-info for Minecraft 26 blocks.");
        }
    }
}
