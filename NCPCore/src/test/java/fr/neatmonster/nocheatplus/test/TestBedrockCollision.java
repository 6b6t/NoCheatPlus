package fr.neatmonster.nocheatplus.test;

import static org.junit.Assert.*;

import org.junit.Test;
import fr.neatmonster.nocheatplus.utilities.collision.BedrockCollision;

public class TestBedrockCollision {
    @Test
    public void fractionalSupportAtWorldHeightsHasNoExtraTolerance() {
        for (final double top : new double[] {0.1825, 0.45, 0.5, 0.899999, 0.95, 1.5}) {
            final double[] box = {0.1, 0, 0.1, 0.9, top, 0.9};
            for (final int y : new int[] {-64, -60, 0, 64, 200, 319}) {
                final double feet = y + top;
                assertTrue(BedrockCollision.supports(box, 0, y, 0, 0.2, feet - 0.025, 0.2, 0.8, feet, 0.8));
                assertFalse("Inside collision", BedrockCollision.supports(box, 0, y, 0,
                        0.2, feet - 0.025, 0.2, 0.8, Math.nextDown(feet), 0.8));
                assertFalse("Hovering", BedrockCollision.supports(box, 0, y, 0,
                        0.2, feet + 0.025, 0.2, 0.8, feet + 0.05, 0.8));
                assertFalse("Outside footprint", BedrockCollision.supports(box, 0, y, 0,
                        0.91, feet - 0.025, 0.2, 1.0, feet, 0.8));
            }
        }
    }

    @Test
    public void multipartSupportUsesTheContactedPart() {
        final double[] step = {0, 0, 0, 1, 0.5, 1, 0.5, 0.5, 0, 1, 1, 1};
        assertTrue(BedrockCollision.supports(step, 0, 0, 0, 0.1, 0.475, 0.2, 0.4, 0.5, 0.8));
        assertTrue(BedrockCollision.supports(step, 0, 0, 0, 0.6, 0.975, 0.2, 0.9, 1, 0.8));
        assertFalse("Higher part blocks the same footprint", BedrockCollision.supports(step, 0, 0, 0,
                0.4, 0.475, 0.2, 0.9, 0.5, 0.8));
        assertFalse("No ground above lower part", BedrockCollision.supports(step, 0, 0, 0,
                0.1, 0.975, 0.2, 0.4, 1, 0.8));
    }

    @Test
    public void solidInteriorsRemainSolidAndTangenciesAreFree() {
        final double[] cube = {0, 0, 0, 1, 1, 1};
        assertTrue(BedrockCollision.intersects(cube, 4, -60, 5, 4.1, -59.9, 5.1, 4.9, -59.1, 5.9));
        assertFalse(BedrockCollision.intersects(cube, 4, -60, 5, 5, -59.9, 5.1, 5.5, -59.1, 5.9));
        assertTrue("A box starting at the feet obstructs support below", BedrockCollision.containsFeet(
                cube, 0, 1, 0, 0.2, 1, 0.2, 0.8, 0.8));
        assertFalse(BedrockCollision.intersects(new double[0], 0, 0, 0, 0, 0, 0, 1, 1, 1));
        assertFalse(BedrockCollision.supports(new double[0], 0, 0, 0, 0, 0, 0, 1, 1, 1));
    }
}
