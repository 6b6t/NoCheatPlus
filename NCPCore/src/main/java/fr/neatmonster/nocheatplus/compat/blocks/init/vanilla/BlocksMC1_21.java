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
package fr.neatmonster.nocheatplus.compat.blocks.init.vanilla;

import org.bukkit.Material;

import fr.neatmonster.nocheatplus.compat.BridgeMaterial;
import fr.neatmonster.nocheatplus.compat.blocks.BlockPropertiesSetup;
import fr.neatmonster.nocheatplus.compat.blocks.init.BlockInit;
import fr.neatmonster.nocheatplus.compat.versions.ServerVersion;
import fr.neatmonster.nocheatplus.config.ConfPaths;
import fr.neatmonster.nocheatplus.config.ConfigFile;
import fr.neatmonster.nocheatplus.config.ConfigManager;
import fr.neatmonster.nocheatplus.config.WorldConfigProvider;
import fr.neatmonster.nocheatplus.logging.StaticLog;
import fr.neatmonster.nocheatplus.utilities.map.BlockFlags;
import fr.neatmonster.nocheatplus.utilities.map.BlockProperties;
import fr.neatmonster.nocheatplus.utilities.map.MaterialUtil;

public class BlocksMC1_21 implements BlockPropertiesSetup {

    public BlocksMC1_21() {
        BlockInit.assertMaterialExists("CREAKING_HEART");
    }

    @Override
    public void setupBlockProperties(WorldConfigProvider<?> worldConfigProvider) {
        setInstantPassableIfExists("OPEN_EYEBLOSSOM");
        setInstantPassableIfExists("CLOSED_EYEBLOSSOM");
        setInstantPassableIfExists("PALE_HANGING_MOSS");
        setInstantPassableIfExists("RESIN_CLUMP");

        BlockInit.setAsIfExists("PALE_MOSS_BLOCK", "MOSS_BLOCK");
        BlockInit.setAsIfExists("RESIN_BLOCK", "TNT");

        setBlockPropsIfExists("CREAKING_HEART", new BlockProperties.BlockProps(BlockProperties.woodAxe, 10f));
        setBlockFlagsIfExists("CREAKING_HEART", BlockFlags.FULLY_SOLID_BOUNDS);

        BlockInit.setAsIfExists("RESIN_BRICKS", "MUD_BRICKS");
        BlockInit.setAsIfExists("RESIN_BRICK_SLAB", "MUD_BRICK_SLAB");
        BlockInit.setAsIfExists("RESIN_BRICK_STAIRS", "MUD_BRICK_STAIRS");
        BlockInit.setAsIfExists("RESIN_BRICK_WALL", "MUD_BRICK_WALL");
        BlockInit.setAsIfExists("CHISELED_RESIN_BRICKS", "MUD_BRICKS");

        if (ServerVersion.compareMinecraftVersion("1.21.5") >= 0) {
            BlockInit.setAsIfExists("TEST_BLOCK", "BEDROCK");
            BlockInit.setAsIfExists("TEST_INSTANCE_BLOCK", "BEDROCK");
        }

        if (ServerVersion.compareMinecraftVersion("1.21.6") >= 0) {
            setBlockPropsIfExists("DRIED_GHAST", new BlockProperties.BlockProps(BlockProperties.noTool, 0.0f));
            setBlockFlagsIfExists("DRIED_GHAST", BlockFlags.SOLID_GROUND);
        }

        if (ServerVersion.compareMinecraftVersion("1.21.9") >= 0) {
            setInstantPassableIfExists("COPPER_TORCH");
            setInstantPassableIfExists("COPPER_WALL_TORCH");

            for (Material mat : MaterialUtil.COPPER_CHESTS) {
                BlockProperties.setBlockProps(mat, new BlockProperties.BlockProps(BlockProperties.woodPickaxe, 3f, true));
                BlockFlags.addFlags(mat, BlockFlags.SOLID_GROUND);
            }
            for (Material mat : MaterialUtil.COPPER_BARS) {
                BlockProperties.setBlockProps(mat, new BlockProperties.BlockProps(BlockProperties.woodPickaxe, 5f, true));
                BlockFlags.addFlags(mat, BlockFlags.SOLID_GROUND | BlockFlags.F_THIN_FENCE | BlockFlags.F_VARIABLE);
            }
            setBlockPropsIfExists("IRON_CHAIN", new BlockProperties.BlockProps(BlockProperties.woodPickaxe, 5f, true));
            addFlagsIfExists("IRON_CHAIN", BlockFlags.SOLID_GROUND);
            for (Material mat : MaterialUtil.COPPER_CHAINS) {
                BlockProperties.setBlockProps(mat, new BlockProperties.BlockProps(BlockProperties.woodPickaxe, 5f, true));
                BlockFlags.addFlags(mat, BlockFlags.SOLID_GROUND);
            }
            for (Material mat : MaterialUtil.COPPER_LIGHTNING_RODS) {
                BlockProperties.setBlockProps(mat, new BlockProperties.BlockProps(BlockProperties.woodPickaxe, 3f, true));
                BlockFlags.addFlags(mat, BlockFlags.SOLID_GROUND);
            }
            for (Material mat : MaterialUtil.COPPER_LANTERNS) {
                BlockProperties.setBlockProps(mat, new BlockProperties.BlockProps(BlockProperties.woodPickaxe, 3f, false));
                BlockFlags.addFlags(mat, BlockFlags.SOLID_GROUND);
            }
            for (Material mat : MaterialUtil.WOODEN_SHELVES) {
                BlockProperties.setBlockProps(mat, new BlockProperties.BlockProps(BlockProperties.woodAxe, 2f, false));
                BlockFlags.addFlags(mat, BlockFlags.SOLID_GROUND);
            }
            for (Material mat : MaterialUtil.COPPER_GOLEM_STATUES) {
                BlockProperties.setBlockProps(mat, new BlockProperties.BlockProps(BlockProperties.woodPickaxe, 3f, false));
                BlockFlags.addFlags(mat, BlockFlags.SOLID_GROUND);
            }
        }

        ConfigFile config = ConfigManager.getConfigFile();
        if (config.getBoolean(ConfPaths.BLOCKBREAK_DEBUG, config.getBoolean(ConfPaths.CHECKS_DEBUG, false))) {
            StaticLog.logInfo("Added block-info for Minecraft 1.21 blocks.");
        }
    }

    private void setInstantPassableIfExists(String id) {
        if (BridgeMaterial.has(id)) {
            BlockInit.setInstantPassable(id);
        }
    }

    private void setBlockPropsIfExists(String id, BlockProperties.BlockProps blockProps) {
        if (BridgeMaterial.has(id)) {
            BlockProperties.setBlockProps(id, blockProps);
        }
    }

    private void setBlockFlagsIfExists(String id, long flags) {
        if (BridgeMaterial.has(id)) {
            BlockFlags.setBlockFlags(id, flags);
        }
    }

    private void addFlagsIfExists(String id, long flags) {
        if (BridgeMaterial.has(id)) {
            BlockFlags.addFlags(id, flags);
        }
    }
}
