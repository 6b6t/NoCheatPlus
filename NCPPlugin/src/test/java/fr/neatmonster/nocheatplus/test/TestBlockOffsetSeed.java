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
package fr.neatmonster.nocheatplus.test;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import fr.neatmonster.nocheatplus.utilities.location.LocUtil;

public class TestBlockOffsetSeed {

    /** Vanilla Mth.getSeed, x * 3129871 overflows as int there. */
    private static long vanillaSeed(final int x, final int y, final int z) {
        long seed = (long) (x * 3129871) ^ (long) z * 116129781L ^ (long) y;
        seed = seed * seed * 42317861L + seed * 11L;
        return seed >> 16;
    }

    private static int random(final int min, final int max) {
        return min + (int) (Math.random() * ((long) max - min + 1));
    }

    @Test
    public void testRandomSeedJava() {
        for (int i = 0; i < 100000; i++) {
            final int x = random(-30000000, 30000000);
            final int y = random(-64, 320);
            final int z = random(-30000000, 30000000);
            assertEquals("x=" + x + " y=" + y + " z=" + z, vanillaSeed(x, y, z), LocUtil.randomSeedJava(x, y, z));
        }
    }
}
