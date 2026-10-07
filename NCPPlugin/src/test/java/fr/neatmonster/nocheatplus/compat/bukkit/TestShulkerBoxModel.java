package fr.neatmonster.nocheatplus.compat.bukkit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Collections;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.ShulkerBox;
import org.bukkit.block.data.Directional;
import org.bukkit.inventory.Inventory;
import org.bukkit.entity.HumanEntity;
import org.bukkit.util.BoundingBox;
import org.junit.Test;

import fr.neatmonster.nocheatplus.compat.bukkit.model.BukkitShulkerBox;

/**
 * Upward lids retain the staged viewer model; sideways/downward lids follow physical bounds.
 */
public class TestShulkerBoxModel {

    static final BlockFace[] FACES = {BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.WEST, BlockFace.EAST};

    /** Vanilla's lid box (Shulker.getProgressAabb): the block expanded towards the facing by 0.5 * progress. */
    static BoundingBox vanillaBox(final int x, final int y, final int z, final BlockFace face, final double lid) {
        final BoundingBox box = new BoundingBox(x, y, z, x + 1, y + 1, z + 1);
        return box.expand(face.getModX(), face.getModY(), face.getModZ(), lid);
    }

    /** The expected model: the full block plus the lid towards the facing (relative coordinates). */
    static double[] expected(final BlockFace face, final double lid) {
        final double[] s = {0.0, 0.0, 0.0, 1.0, 1.0, 1.0};
        if (face.getModX() < 0) s[0] -= lid;
        if (face.getModY() < 0) s[1] -= lid;
        if (face.getModZ() < 0) s[2] -= lid;
        if (face.getModX() > 0) s[3] += lid;
        if (face.getModY() > 0) s[4] += lid;
        if (face.getModZ() > 0) s[5] += lid;
        return s;
    }

    /** Server lid positions, as vanilla's float progress steps 0.1 per tick: opening, then closing. */
    static List<Double> animation() {
        final List<Double> lids = new ArrayList<Double>();
        float progress = 0f;
        for (int i = 0; i < 9; i++) {
            progress += 0.1f;
            lids.add(0.5 * progress);
        }
        for (int i = 0; i < 9; i++) {
            progress -= 0.1f;
            lids.add(0.5 * progress);
        }
        return lids;
    }

    private static double[] shape(final BoundingBox box) {
        return shape(box, BlockFace.NORTH, false);
    }

    private static double[] shape(final BoundingBox box, final BlockFace face, final boolean viewed) {
        final World world = mock(World.class);
        final Block block = mock(Block.class);
        when(world.getBlockAt(10, 64, 20)).thenReturn(block);
        final Directional data = mock(Directional.class);
        when(block.getBlockData()).thenReturn(data);
        when(data.getFacing()).thenReturn(face);
        when(block.getBoundingBox()).thenReturn(box);
        if (face == BlockFace.UP) {
            final ShulkerBox state = mock(ShulkerBox.class);
            final Inventory inventory = mock(Inventory.class);
            when(block.getState()).thenReturn(state);
            when(state.getInventory()).thenReturn(inventory);
            when(inventory.getViewers()).thenReturn(viewed
                    ? Collections.singletonList(mock(HumanEntity.class)) : Collections.emptyList());
        }
        final double[] shape = new BukkitShulkerBox().getShape(null, world, 10, 64, 20);
        if (face == BlockFace.UP) {
            verify(block, never()).getBoundingBox();
        } else {
            verify(block, never()).getState();
        }
        return shape;
    }

    @Test
    public void closedIsAFullBlock() {
        for (final BlockFace face : FACES) {
            assertArrayEquals(face.name(), new double[] {0, 0, 0, 1, 1, 1}, shape(vanillaBox(10, 64, 20, face, 0.0), face, false), 0.0);
        }
    }

    @Test
    public void fullyOpenIsTheOpenShapeAsBefore() {
        // Same shapes as the old viewer-based model returned for an open box.
        assertArrayEquals(new double[] {0, 0, 0, 1, 1.5, 1}, shape(vanillaBox(10, 64, 20, BlockFace.UP, 0.5), BlockFace.UP, true), 1e-9);
        assertArrayEquals(new double[] {0, -0.5, 0, 1, 1, 1}, shape(vanillaBox(10, 64, 20, BlockFace.DOWN, 0.5), BlockFace.DOWN, true), 1e-9);
        assertArrayEquals(new double[] {0, 0, -0.5, 1, 1, 1}, shape(vanillaBox(10, 64, 20, BlockFace.NORTH, 0.5), BlockFace.NORTH, true), 1e-9);
        assertArrayEquals(new double[] {0, 0, 0, 1, 1, 1.5}, shape(vanillaBox(10, 64, 20, BlockFace.SOUTH, 0.5), BlockFace.SOUTH, true), 1e-9);
        assertArrayEquals(new double[] {-0.5, 0, 0, 1, 1, 1}, shape(vanillaBox(10, 64, 20, BlockFace.WEST, 0.5), BlockFace.WEST, true), 1e-9);
        assertArrayEquals(new double[] {0, 0, 0, 1.5, 1, 1}, shape(vanillaBox(10, 64, 20, BlockFace.EAST, 0.5), BlockFace.EAST, true), 1e-9);
    }

    @Test
    public void partialOpeningAndClosingFollowTheRealLid() {
        // Every server tick of an open and a close, sideways/downward: the lid exactly as far as it is out,
        // never the fully open shape.
        final List<Double> lids = animation();
        assertEquals(18, lids.size());
        for (final BlockFace face : FACES) {
            if (face == BlockFace.UP) continue;
            for (int i = 0; i < lids.size(); i++) {
                final double lid = lids.get(i);
                final String what = face + (i < 9 ? " opening" : " closing") + " lid " + lid;
                assertArrayEquals(what, expected(face, lid), shape(vanillaBox(10, 64, 20, face, lid), face, false), 1e-6);
            }
        }
    }

    @Test
    public void upwardOpeningAndClosingRetainStagedViewerBehavior() {
        final List<Double> lids = animation();
        lids.add(0.0);
        lids.add(0.5);
        for (final double lid : lids) {
            final BoundingBox box = vanillaBox(10, 64, 20, BlockFace.UP, lid);
            // Viewer present: full height immediately, even when the physical lid has barely opened.
            assertArrayEquals(expected(BlockFace.UP, 0.5), shape(box, BlockFace.UP, true), 0.0);
            // Last viewer gone: closed immediately, including every remaining closing-animation tick.
            assertArrayEquals(expected(BlockFace.UP, 0.0), shape(box, BlockFace.UP, false), 0.0);
        }
    }

    @Test
    public void reviewCounterexampleIsATenthNotHalf() {
        // REVIEW-CODEX finding 2: a north lid out by 0.1 was modelled as 0.5.
        assertArrayEquals(new double[] {0, 0, -0.1, 1, 1, 1}, shape(new BoundingBox(10, 64, 19.9, 11, 65, 21)), 1e-9);
    }

    @Test
    public void nothingButABoxPlusALid() {
        // No shape (empty box at 0,0,0), a box not around the block, a box past any lid: no extra ground.
        assertArrayEquals(new double[] {0, 0, 0, 1, 1, 1}, shape(new BoundingBox()), 0.0);
        assertArrayEquals(new double[] {0, 0, 0, 1, 1, 1}, shape(new BoundingBox(10, 64.2, 20, 11, 65, 21)), 0.0);
        assertArrayEquals(new double[] {0, 0, 0, 1, 1, 1}, shape(new BoundingBox(10, 65, 20, 11, 66, 21)), 0.0);
        assertArrayEquals(new double[] {0, 0, -0.5, 1, 1, 1}, shape(new BoundingBox(10, 64, 18, 11, 65, 21)), 1e-9);
        assertArrayEquals(new double[] {0, 0, 0, 1, 1.5, 1}, shape(new BoundingBox(10, 64, 20, 11, 70, 21)), 1e-9);
    }
}
