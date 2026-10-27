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
package fr.neatmonster.nocheatplus.utilities.collision;

/** Exact additional Bedrock contacts; Java/Geyser-corrected contacts remain valid. */
public final class BedrockCollision {
    private BedrockCollision() {}

    public static boolean intersects(final double[] boxes, final int x, final int y, final int z,
            final double minX, final double minY, final double minZ,
            final double maxX, final double maxY, final double maxZ) {
        for (int i = 0; i < boxes.length; i += 6) {
            if (minX < x + boxes[i + 3] && maxX > x + boxes[i]
                    && minY < y + boxes[i + 4] && maxY > y + boxes[i + 1]
                    && minZ < z + boxes[i + 5] && maxZ > z + boxes[i + 2]) return true;
        }
        return false;
    }

    public static boolean containsFeet(final double[] boxes, final int x, final int y, final int z,
            final double minX, final double feetY, final double minZ, final double maxX, final double maxZ) {
        for (int i = 0; i < boxes.length; i += 6) {
            if (minX < x + boxes[i + 3] && maxX > x + boxes[i]
                    && minZ < z + boxes[i + 5] && maxZ > z + boxes[i + 2]
                    && y + boxes[i + 1] <= feetY && feetY < y + boxes[i + 4]) return true;
        }
        return false;
    }

    public static boolean supports(final double[] boxes, final int x, final int y, final int z,
            final double minX, final double minY, final double minZ,
            final double maxX, final double feetY, final double maxZ) {
        if (containsFeet(boxes, x, y, z, minX, feetY, minZ, maxX, maxZ)) return false;
        for (int i = 0; i < boxes.length; i += 6) {
            // Add the block height before comparing to preserve fractional world heights.
            final double top = y + boxes[i + 4];
            if (minY <= top && top <= feetY
                    && minX < x + boxes[i + 3] && maxX > x + boxes[i]
                    && minZ < z + boxes[i + 5] && maxZ > z + boxes[i + 2]) return true;
        }
        return false;
    }
}
