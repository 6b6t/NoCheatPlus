package fr.neatmonster.nocheatplus.checks.moving.magic;

import org.bukkit.Location;
import org.bukkit.Material;

import fr.neatmonster.nocheatplus.utilities.map.BlockCache;

/** Short shelf-mushroom bounces using 26.3 collision restitution. */
public final class ShelfBounce {

    private static final double TOP_EPSILON = 1.0E-7;

    private ShelfBounce() {}

    public static double firstRise(final double lastYDistance, final double landingYDistance) {
        if (!Double.isFinite(lastYDistance) || !Double.isFinite(landingYDistance)
                || !(lastYDistance < 0.0) || !(landingYDistance < 0.0)) {
            return 0.0;
        }
        final double impact = (lastYDistance - BedBounce.GRAVITY) * BedBounce.DRAG;
        if (impact >= -BedBounce.GRAVITY || landingYDistance < impact - BedBounce.RISE_MARGIN) {
            return 0.0;
        }
        // Entity#restituteMovementAfterCollisions scales gravity and drag by the travelled fraction.
        final double fraction = Math.min(1.0, landingYDistance / impact);
        final double restitution = (fraction * BedBounce.GRAVITY - impact)
                * (1.0 + fraction * (BedBounce.DRAG - 1.0)) * 0.75;
        return Math.max(0.0, (restitution - BedBounce.GRAVITY) * BedBounce.DRAG);
    }

    /** Require a real collision top under the box, including the mushroom's gaps. */
    public static boolean isOnTop(final BlockCache cache, final double minX, final double minZ,
                                 final double maxX, final double maxZ, final double feetY) {
        if (cache == null || !Double.isFinite(feetY)) {
            return false;
        }
        final int y = Location.locToBlock(feetY);
        for (int x = Location.locToBlock(minX); x <= Location.locToBlock(maxX - TOP_EPSILON); x++) {
            for (int z = Location.locToBlock(minZ); z <= Location.locToBlock(maxZ - TOP_EPSILON); z++) {
                final Material type = cache.getType(x, y, z);
                if (type == null || !type.name().equals("SHELF_MUSHROOM")) continue;
                final double[] bounds = cache.getBounds(x, y, z);
                if (bounds == null) continue;
                for (int i = 0; i + 5 < bounds.length; i += 6) {
                    if (Math.abs(feetY - y - bounds[i + 4]) <= TOP_EPSILON
                            && maxX > x + bounds[i] && minX < x + bounds[i + 3]
                            && maxZ > z + bounds[i + 2] && minZ < z + bounds[i + 5]) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
