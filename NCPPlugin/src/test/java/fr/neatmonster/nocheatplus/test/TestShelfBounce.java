package fr.neatmonster.nocheatplus.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNotNull;

import org.bukkit.Material;
import org.junit.Test;

import fr.neatmonster.nocheatplus.checks.moving.magic.BedBounce;
import fr.neatmonster.nocheatplus.checks.moving.magic.ShelfBounce;
import fr.neatmonster.nocheatplus.utilities.map.FakeBlockCache;

public class TestShelfBounce {

    @Test
    public void recordedSmallBounces() {
        // MagnusKrill's 26.3 trace: the landing is shortened by collision with the shelf top.
        assertEquals(-58.14973086421503 - -58.3125,
                ShelfBounce.firstRise(-0.22768848754498797, -58.3125 - -58.2032643993313), 1.0E-10);
        final double rise = ShelfBounce.firstRise(-0.1552320045166016, -58.3125 - -58.30224911560954);
        assertEquals(-58.21900072952579 - -58.3125, rise, 1.0E-10);
        assertTrue(BedBounce.isBounceLiftOff(rise, 7, 8, true, rise, true));
        assertFalse("Forged higher bounce", BedBounce.isBounceLiftOff(rise, 7, 8, true, rise + 0.01, true));
        assertFalse("Expired landing", BedBounce.isBounceLiftOff(rise, 7, 9, true, rise, true));
    }

    @Test
    public void rejectNonFallingOrImpossibleLandings() {
        for (final double invalid : new double[]{0.0, 0.1, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}) {
            assertEquals(0.0, ShelfBounce.firstRise(invalid, -0.01), 0.0);
            assertEquals(0.0, ShelfBounce.firstRise(-0.2, invalid), 0.0);
        }
        assertEquals("Landing cannot travel farther than free fall", 0.0, ShelfBounce.firstRise(-0.1, -0.5), 0.0);
        assertEquals("Impact below vanilla's bounce threshold", 0.0, ShelfBounce.firstRise(-0.001, -0.06), 0.0);
    }

    @Test
    public void requireActualMultipartShelfSupport() {
        final Material shelf = Material.matchMaterial("SHELF_MUSHROOM");
        assumeNotNull(shelf); // Also run against the native 26.3 API, which supplies this material.
        final FakeBlockCache cache = new FakeBlockCache();
        cache.set(0, 64, 0, shelf, new double[]{
                0.25, 0.375, 0.625, 0.75, 0.6875, 1.0,
                0.0625, 0.5, 0.375, 0.25, 0.6875, 1.0,
                0.25, 0.5, 0.375, 0.9375, 0.6875, 0.625,
                0.75, 0.5, 0.625, 0.9375, 0.6875, 1.0});
        assertTrue(ShelfBounce.isOnTop(cache, 0.2, 0.2, 0.8, 0.8, 64.6875));
        assertTrue("Edge support", ShelfBounce.isOnTop(cache, -0.4, 0.4, 0.1, 0.8, 64.6875));
        assertFalse("Outside the mushroom", ShelfBounce.isOnTop(cache, 0.1, 0.1, 0.9, 0.3, 64.6875));
        assertFalse("Touching the shape edge is not support", ShelfBounce.isOnTop(cache, -0.4, 0.4, 0.0625, 0.8, 64.6875));
        assertFalse("Near ground is not contact", ShelfBounce.isOnTop(cache, 0.2, 0.2, 0.8, 0.8, 64.6975));
        assertFalse("Below the top", ShelfBounce.isOnTop(cache, 0.2, 0.2, 0.8, 0.8, 64.6));
        cache.set(0, 64, 0, Material.STONE);
        assertFalse("Other block types", ShelfBounce.isOnTop(cache, 0.2, 0.2, 0.8, 0.8, 65.0));
        cache.cleanup();
    }
}
