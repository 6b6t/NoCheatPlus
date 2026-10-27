package fr.neatmonster.nocheatplus.compat.bukkit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Map;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.VoxelShape;
import org.junit.After;
import org.junit.Test;

import fr.neatmonster.nocheatplus.compat.bukkit.model.BukkitFetchableBounds;
import fr.neatmonster.nocheatplus.compat.bukkit.model.BukkitShapeModel;
import fr.neatmonster.nocheatplus.compat.BridgeMaterial;
import fr.neatmonster.nocheatplus.NCPAPIProvider;
import fr.neatmonster.nocheatplus.PluginTests;
import fr.neatmonster.nocheatplus.compat.MCAccess;
import fr.neatmonster.nocheatplus.components.NoCheatPlusAPI;
import fr.neatmonster.nocheatplus.logging.StaticLog;
import fr.neatmonster.nocheatplus.test.BlockTests;
import fr.neatmonster.nocheatplus.utilities.map.BlockFlags;
import fr.neatmonster.nocheatplus.utilities.map.BlockProperties;
import fr.neatmonster.nocheatplus.utilities.map.FakeBlockCache;
import fr.neatmonster.nocheatplus.utilities.map.MaterialUtil;

/**
 * Part 4 of the set-back candidate: hoppers, anvils, double chests, beds and bells use the vanilla collision shape
 * (Block#getCollisionShape), hoppers without the full-block flags that stretched it.
 */
public class TestVanillaShapes {

    @After
    public void resetLogging() {
        StaticLog.setUseLogManager(false);
    }

    private static final double S = 1.0 / 16.0;

    /** Vanilla 26.2 hopper (facing down): spout, funnel, bowl floor and the four rim walls. */
    private static final BoundingBox[] HOPPER = {
            new BoundingBox(6 * S, 0, 6 * S, 10 * S, 4 * S, 10 * S),
            new BoundingBox(4 * S, 4 * S, 4 * S, 12 * S, 10 * S, 12 * S),
            new BoundingBox(0, 10 * S, 0, 1, 11 * S, 1),
            new BoundingBox(0, 11 * S, 0, 2 * S, 1, 1),
            new BoundingBox(14 * S, 11 * S, 0, 1, 1, 1),
            new BoundingBox(2 * S, 11 * S, 0, 14 * S, 1, 2 * S),
            new BoundingBox(2 * S, 11 * S, 14 * S, 14 * S, 1, 1),
    };

    private static double[] fetch(final BoundingBox... boxes) {
        final World world = mock(World.class);
        final Block block = mock(Block.class);
        final VoxelShape shape = mock(VoxelShape.class);
        when(world.getBlockAt(3, 64, 5)).thenReturn(block);
        when(block.getCollisionShape()).thenReturn(shape);
        when(shape.getBoundingBoxes()).thenReturn(Arrays.asList(boxes));
        return new BukkitFetchableBounds().getShape(null, world, 3, 64, 5);
    }

    @Test
    public void fetchedBoundsAreTheCollisionBoxes() {
        assertArrayEquals(new double[] {0, 0, 0, 1, 9 * S, 1, 0.25, 9 * S, 0.25, 0.75, 1, 0.75},
                fetch(new BoundingBox(0, 0, 0, 1, 9 * S, 1), new BoundingBox(0.25, 9 * S, 0.25, 0.75, 1, 0.75)), 0.0);
        assertNull("An empty shape has no bounds (see TestEmptyCarpetGround).", fetch());
        assertEquals(42, fetch(HOPPER).length);
    }

    private static boolean onGround(final Material type, final double[] bounds, final double feetY) {
        final FakeBlockCache bc = new FakeBlockCache();
        bc.set(0, 64, 0, type, bounds);
        final boolean ground = BlockProperties.isOnGround(bc, 0.2, feetY - 0.001, 0.2, 0.8, feetY, 0.8, 0L);
        bc.cleanup();
        return ground;
    }

    @Test
    public void hopperBowl() {
        StaticLog.setUseLogManager(false);
        BlockTests.initBlockProperties();
        StaticLog.setUseLogManager(true);
        // As MCAccessBukkitModern with bounding boxes: no XZ100/HEIGHT100 for hoppers.
        BlockFlags.removeFlags(Material.HOPPER, BlockFlags.F_XZ100 | BlockFlags.F_HEIGHT100);
        final double[] hopper = fetch(HOPPER);
        assertTrue("Standing in the bowl (11/16), as in vanilla.", onGround(Material.HOPPER, hopper, 64 + 11 * S));
        assertFalse("No ground at the rim height over the middle of the bowl.", onGround(Material.HOPPER, hopper, 65.0));
        assertFalse("No ground at 0.9 over the bowl.", onGround(Material.HOPPER, hopper, 64.9));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void modelsForTheChangedBlocks() throws Exception {
        final NoCheatPlusAPI previousAPI = NCPAPIProvider.getNoCheatPlusAPI();
        StaticLog.setUseLogManager(false);
        final MCAccessBukkitModern access;
        // No server here: the constructor's NMS reflection helpers are mocked (not used by the block setup).
        try (org.mockito.MockedStatic<NCPAPIProvider> api = org.mockito.Mockito.mockStatic(NCPAPIProvider.class);
             org.mockito.MockedConstruction<?> a = org.mockito.Mockito.mockConstruction(fr.neatmonster.nocheatplus.compat.cbreflect.reflect.ReflectBase.class);
             org.mockito.MockedConstruction<?> b = org.mockito.Mockito.mockConstruction(fr.neatmonster.nocheatplus.compat.cbreflect.reflect.ReflectDamageSource.class);
             org.mockito.MockedConstruction<?> c = org.mockito.Mockito.mockConstruction(fr.neatmonster.nocheatplus.compat.cbreflect.reflect.ReflectLivingEntity.class);
             org.mockito.MockedConstruction<?> d = org.mockito.Mockito.mockConstruction(fr.neatmonster.nocheatplus.compat.cbreflect.reflect.ReflectDamageSources.class)) {
            api.when(NCPAPIProvider::getNoCheatPlusAPI).thenReturn(new PluginTests.UnitTestNoCheatPlusAPI());
            access = new MCAccessBukkitModern();
            StaticLog.setUseLogManager(false);
            NCPAPIProvider.getNoCheatPlusAPI().registerGenericInstance(MCAccess.class, access);
            BlockTests.initBlockProperties();
        }
        finally {
            StaticLog.setUseLogManager(true);
        }
        final Field field = MCAccessBukkitModern.class.getDeclaredField("shapeModels");
        field.setAccessible(true);
        final Map<Material, BukkitShapeModel> models = (Map<Material, BukkitShapeModel>) field.get(access);
        for (final Material mat : new Material[] {Material.ANVIL, Material.CHIPPED_ANVIL, Material.DAMAGED_ANVIL,
                Material.CHEST, Material.TRAPPED_CHEST, Material.ENDER_CHEST, Material.RED_BED, Material.BELL}) {
            assertTrue(mat + " fetches its vanilla shape", models.get(mat) instanceof BukkitFetchableBounds);
        }
        assertEquals("Hopper keeps its model (it fetches the shape itself).", "BukkitHopper",
                models.get(Material.HOPPER).getClass().getSimpleName());
        assertEquals(0L, BlockFlags.getBlockFlags(Material.HOPPER) & (BlockFlags.F_XZ100 | BlockFlags.F_HEIGHT100));

        for (final Material mat : BridgeMaterial.getAllBlocks("shelf_mushroom", "straw_bed")) {
            assertTrue(mat + " fetches its state-dependent shape", models.get(mat) instanceof BukkitFetchableBounds);
            final long flags = BlockFlags.getBlockFlags(mat);
            assertEquals(mat + " is solid ground", BlockFlags.SOLID_GROUND, flags & BlockFlags.SOLID_GROUND);
            assertEquals(mat + " must not become a full cube", 0L,
                    flags & (BlockFlags.F_IGN_PASSABLE | BlockFlags.F_XZ100 | BlockFlags.F_HEIGHT100));
            final boolean straw = mat.name().equals("STRAW_BED");
            assertEquals(mat + " bounce", !straw, (flags & BlockFlags.F_BOUNCE25) != 0);
            assertEquals(mat + " keeps separate fall behavior", 0L, flags & BlockFlags.F_BED);
            if (straw) assertFalse("Straw beds do not halve fall damage", MaterialUtil.BEDS.contains(mat));
            else assertSame("Shelf mushrooms break instantly", BlockProperties.instantType, BlockProperties.getBlockProps(mat));
        }
        for (final Material mat : BridgeMaterial.getAllBlocks("poplar_fence", "poplar_fence_gate")) {
            assertTrue(mat + " has a fence model", models.containsKey(mat));
            assertTrue(mat + " blocks phasing", (BlockFlags.getBlockFlags(mat) & BlockFlags.F_THICK_FENCE) != 0);
        }
        for (final Material mat : BridgeMaterial.getAllBlocks("red_poplar_leaves", "orange_poplar_leaves", "yellow_poplar_leaves")) {
            assertTrue(mat + " is a leaf", MaterialUtil.LEAVES.contains(mat));
            assertEquals(mat + " has full collision", BlockFlags.FULL_BOUNDS,
                    BlockFlags.getBlockFlags(mat) & BlockFlags.FULL_BOUNDS);
        }
        for (final Material mat : BridgeMaterial.getAllBlocks("red_shrub", "poplar_sapling")) {
            assertTrue(mat + " is passable", (BlockFlags.getBlockFlags(mat) & BlockFlags.F_IGN_PASSABLE) != 0);
            assertEquals(mat + " is not ground", 0L, BlockFlags.getBlockFlags(mat) & BlockFlags.SOLID_GROUND);
        }
        assertSame("Block setup must preserve the shared API", previousAPI, NCPAPIProvider.getNoCheatPlusAPI());
    }
}
