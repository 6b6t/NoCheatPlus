package fr.neatmonster.nocheatplus.test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.util.BoundingBox;
import org.junit.Test;

import fr.neatmonster.nocheatplus.compat.bukkit.model.BukkitShulkerBox;
import fr.neatmonster.nocheatplus.logging.StaticLog;
import fr.neatmonster.nocheatplus.utilities.map.BlockFlags;
import fr.neatmonster.nocheatplus.utilities.map.BlockProperties;
import fr.neatmonster.nocheatplus.utilities.map.FakeBlockCache;

/**
 * Ground on a shulker box lid that reaches into the neighbor block (from PR #3), only as far as the lid is really
 * out, and no ground next to a closed box. Box at (0, 64, 0); the lid bounds come from the real shape model.
 */
public class TestShulkerLidGround {

    private static final BlockFace[] SIDEWAYS = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.WEST, BlockFace.EAST};

    public TestShulkerLidGround() {
        StaticLog.setUseLogManager(false);
        BlockTests.initBlockProperties();
        StaticLog.setUseLogManager(true);
        // Same as MCAccessBukkitModern: the base flags without XZ100, and no F_GROUND_HEIGHT.
        BlockFlags.setBlockFlags(Material.SHULKER_BOX, BlockFlags.F_SOLID | BlockFlags.F_GROUND);
    }

    /** Server lid positions, as vanilla's float progress steps 0.1 per tick: opening (9 ticks), then closing. */
    private static List<Double> animation() {
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

    /** The model's shape for the box at (0, 64, 0) with its lid out by lid towards face (vanilla's box). */
    private static double[] lid(final BlockFace face, final double lid) {
        final BoundingBox box = new BoundingBox(0, 64, 0, 1, 65, 1).expand(face.getModX(), face.getModY(), face.getModZ(), lid);
        return BukkitShulkerBox.lidShape(box, 0, 64, 0);
    }

    private static boolean onGround(final Material type, final double[] bounds,
            final double minX, final double maxX, final double minZ, final double maxZ, final double feetY) {
        final FakeBlockCache bc = new FakeBlockCache();
        bc.set(0, 64, 0, type, bounds);
        final boolean ground = BlockProperties.isOnGround(bc, minX, feetY - 0.001, minZ, maxX, feetY, maxZ, 0L);
        bc.cleanup();
        return ground;
    }

    /** Feet box x 0.2 - 0.8, z minZ - maxZ (0.001 below the feet, like yOnGround). */
    private static boolean onGround(final Material type, final double[] bounds, final double minZ, final double maxZ, final double feetY) {
        return onGround(type, bounds, 0.2, 0.8, minZ, maxZ, feetY);
    }

    /**
     * A 0.6 wide footprint beside the box on the lid's side: its inner edge at overlap into the lid's outer edge
     * (negative = a gap), centered on the box along the other axis.
     */
    private static boolean besideOnLid(final BlockFace face, final double[] bounds, final double lid, final double overlap, final double feetY) {
        final double inner = -lid + overlap; // Distance of the footprint's inner edge from the box face (outwards < 0).
        switch (face) {
            case NORTH: return onGround(Material.SHULKER_BOX, bounds, 0.2, 0.8, inner - 0.6, inner, feetY);
            case SOUTH: return onGround(Material.SHULKER_BOX, bounds, 0.2, 0.8, 1.0 - inner, 1.6 - inner, feetY);
            case WEST: return onGround(Material.SHULKER_BOX, bounds, inner - 0.6, inner, 0.2, 0.8, feetY);
            case EAST: return onGround(Material.SHULKER_BOX, bounds, 1.0 - inner, 1.6 - inner, 0.2, 0.8, feetY);
            default: throw new IllegalArgumentException(face.name());
        }
    }

    @Test
    public void testSidewaysLid() {
        final double[] open = lid(BlockFace.NORTH, 0.5);
        assertTrue("On the part of the lid that sticks out.", onGround(Material.SHULKER_BOX, open, -0.9, -0.3, 65.0));
        assertFalse("Just past the lid.", onGround(Material.SHULKER_BOX, open, -1.2, -0.6, 65.0));
        assertTrue("On the box itself.", onGround(Material.SHULKER_BOX, open, 0.2, 0.8, 65.0));
    }

    @Test
    public void testOnlyShulkerBoxesReachOut() {
        // Neighbor blocks are only checked for shulker boxes.
        assertFalse("Other blocks are not checked from the neighbor block.", onGround(Material.STONE, lid(BlockFace.NORTH, 0.5), -0.9, -0.3, 65.0));
    }

    @Test
    public void testReviewCounterexample() {
        // REVIEW-CODEX finding 2: north lid out by 0.1, footprint z -0.8..-0.2 at the top: a 0.1 gap to the lid.
        assertFalse("0.1 lid, 0.1 gap: no ground.", onGround(Material.SHULKER_BOX, lid(BlockFace.NORTH, 0.1), -0.8, -0.2, 65.0));
        // Fully open, the same footprint stands on the lid (0.3 of it over the lid).
        assertTrue("Fully open: on the lid.", onGround(Material.SHULKER_BOX, lid(BlockFace.NORTH, 0.5), -0.8, -0.2, 65.0));
    }

    @Test
    public void testPartialSidewaysLidsOpeningAndClosing() {
        final List<Double> lids = animation();
        for (final BlockFace face : SIDEWAYS) {
            for (int i = 0; i < lids.size(); i++) {
                final double lid = lids.get(i);
                final double[] bounds = lid(face, lid);
                final String what = face + (i < 9 ? " opening" : " closing") + " lid " + lid;
                if (lid > 0.02) {
                    assertTrue(what + ": standing 0.02 on the lid.", besideOnLid(face, bounds, lid, 0.02, 65.0));
                }
                assertFalse(what + ": 0.01 past the lid.", besideOnLid(face, bounds, lid, -0.01, 65.0));
                assertFalse(what + ": 0.05 past the lid.", besideOnLid(face, bounds, lid, -0.05, 65.0));
                // Where the fully open lid would be, but this one isn't (yet or any more).
                if (lid < 0.45) {
                    assertFalse(what + ": where the open lid would end.", besideOnLid(face, bounds, 0.5, 0.02, 65.0));
                }
                // Not inside the lid nor above it.
                if (lid > 0.02) {
                    assertFalse(what + ": feet inside the lid.", besideOnLid(face, bounds, lid, 0.02, 64.6));
                    assertFalse(what + ": 0.03 above the lid.", besideOnLid(face, bounds, lid, 0.02, 65.03));
                }
            }
        }
    }

    @Test
    public void testUpwardLidLandingOpeningAndClosing() {
        final List<Double> lids = animation();
        for (int i = 0; i < lids.size(); i++) {
            final double lid = lids.get(i);
            final double[] bounds = lid(BlockFace.UP, lid);
            final String what = (i < 9 ? "opening" : "closing") + " lid " + lid;
            assertTrue(what + ": landing on the lid top.", onGround(Material.SHULKER_BOX, bounds, 0.2, 0.8, 65.0 + lid));
            assertFalse(what + ": 0.03 above the lid top.", onGround(Material.SHULKER_BOX, bounds, 0.2, 0.8, 65.03 + lid));
            if (lid < 0.45) {
                assertFalse(what + ": at the fully open top.", onGround(Material.SHULKER_BOX, bounds, 0.2, 0.8, 65.5));
            }
            // The upward lid adds nothing beside the box.
            assertFalse(what + ": beside the box at the lid top.", onGround(Material.SHULKER_BOX, bounds, -0.9, -0.3, 65.0 + lid));
        }
    }

    @Test
    public void testDownwardLidAddsNoGround() {
        final List<Double> lids = animation();
        for (final double lid : lids) {
            final double[] bounds = lid(BlockFace.DOWN, lid);
            assertTrue("On top of the box, lid " + lid, onGround(Material.SHULKER_BOX, bounds, 0.2, 0.8, 65.0));
            assertFalse("Beside the box, lid " + lid, onGround(Material.SHULKER_BOX, bounds, -0.9, -0.3, 65.0));
            assertFalse("Beside the lid, lid " + lid, onGround(Material.SHULKER_BOX, bounds, -0.9, -0.3, 64.0));
        }
    }

    @Test
    public void testNoGroundNextToAClosedBox() {
        final double[] closed = lid(BlockFace.NORTH, 0.0);
        for (final double feet : new double[] {64.0, 64.3, 64.5, 64.9, 65.0, 65.4}) {
            assertFalse("Beside a closed box (0.3 gap), feet at " + feet, onGround(Material.SHULKER_BOX, closed, -0.9, -0.3, feet));
            assertFalse("Beside a closed box (0.01 gap), feet at " + feet, onGround(Material.SHULKER_BOX, closed, -0.61, -0.01, feet));
        }
        for (final double feet : new double[] {64.1, 64.5, 64.9}) {
            // Touching its north face below its top: as on live, no ground (PR #3's F_GROUND_HEIGHT made this ground).
            assertFalse("Flush against the side of a closed box, feet at " + feet, onGround(Material.SHULKER_BOX, closed, -0.6, 0.0, feet));
        }
        assertTrue("On top of a closed box.", onGround(Material.SHULKER_BOX, closed, 0.2, 0.8, 65.0));
    }

    @Test
    public void testNoGroundInsideOrAboveTheLid() {
        final double[] open = lid(BlockFace.NORTH, 0.5);
        for (final double feet : new double[] {64.3, 64.6, 64.9}) {
            assertFalse("Feet inside the lid part, at " + feet, onGround(Material.SHULKER_BOX, open, -0.9, -0.3, feet));
        }
        assertFalse("Hovering 0.3 above the lid.", onGround(Material.SHULKER_BOX, open, -0.9, -0.3, 65.3));
    }
}
