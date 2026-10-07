package fr.neatmonster.nocheatplus.compat.bukkit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.util.BoundingBox;
import org.junit.Test;

import fr.neatmonster.nocheatplus.compat.bukkit.model.BukkitShulkerBox;

/**
 * The shulker box shape is the box plus the lid as far as it is really out (the block's bounding box), also while
 * the lid opens or closes. Fully open and closed are as before.
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
        final World world = mock(World.class);
        final Block block = mock(Block.class);
        when(world.getBlockAt(10, 64, 20)).thenReturn(block);
        when(block.getBoundingBox()).thenReturn(box);
        final double[] shape = new BukkitShulkerBox().getShape(null, world, 10, 64, 20);
        // The real shape comes from the bounding box; the viewers (block state) aren't looked at.
        verify(block, never()).getState();
        return shape;
    }

    @Test
    public void closedIsAFullBlock() {
        for (final BlockFace face : FACES) {
            assertArrayEquals(face.name(), new double[] {0, 0, 0, 1, 1, 1}, shape(vanillaBox(10, 64, 20, face, 0.0)), 0.0);
        }
    }

    @Test
    public void fullyOpenIsTheOpenShapeAsBefore() {
        // Same shapes as the old viewer-based model returned for an open box.
        assertArrayEquals(new double[] {0, 0, 0, 1, 1.5, 1}, shape(vanillaBox(10, 64, 20, BlockFace.UP, 0.5)), 1e-9);
        assertArrayEquals(new double[] {0, -0.5, 0, 1, 1, 1}, shape(vanillaBox(10, 64, 20, BlockFace.DOWN, 0.5)), 1e-9);
        assertArrayEquals(new double[] {0, 0, -0.5, 1, 1, 1}, shape(vanillaBox(10, 64, 20, BlockFace.NORTH, 0.5)), 1e-9);
        assertArrayEquals(new double[] {0, 0, 0, 1, 1, 1.5}, shape(vanillaBox(10, 64, 20, BlockFace.SOUTH, 0.5)), 1e-9);
        assertArrayEquals(new double[] {-0.5, 0, 0, 1, 1, 1}, shape(vanillaBox(10, 64, 20, BlockFace.WEST, 0.5)), 1e-9);
        assertArrayEquals(new double[] {0, 0, 0, 1.5, 1, 1}, shape(vanillaBox(10, 64, 20, BlockFace.EAST, 0.5)), 1e-9);
    }

    @Test
    public void partialOpeningAndClosingFollowTheRealLid() {
        // Every server tick of an open and a close, all six orientations: the lid exactly as far as it is out,
        // never the fully open shape.
        final List<Double> lids = animation();
        assertEquals(18, lids.size());
        for (final BlockFace face : FACES) {
            for (int i = 0; i < lids.size(); i++) {
                final double lid = lids.get(i);
                final String what = face + (i < 9 ? " opening" : " closing") + " lid " + lid;
                assertArrayEquals(what, expected(face, lid), shape(vanillaBox(10, 64, 20, face, lid)), 1e-6);
            }
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
