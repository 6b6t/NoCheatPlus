package fr.neatmonster.nocheatplus.telemetry;

import java.util.*;
import fr.neatmonster.nocheatplus.utilities.map.BlockCache;
import fr.neatmonster.nocheatplus.utilities.map.BlockFlags;
import fr.neatmonster.nocheatplus.utilities.map.MapUtil;

/** Cache-only: missing nodes stay null. No fetch/create methods, world calls or shape calculations. */
public final class CachedBlockSnapshot {
    private CachedBlockSnapshot() {}
    public static Map<String, Object> capture(BlockCache cache, int x, int y, int z) {
        if (cache == null) return null;
        List<Object> cells = new ArrayList<>(36); int present = 0;
        for (int dy = -1; dy <= 2; dy++) for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
            BlockCache.IBlockCacheNode node = cache.getBlockCacheNode(x + dx, y + dy, z + dz);
            if (node == null) cells.add(null);
            else {
                present++;
                int shape = !node.isBoundsFetched() ? 0 : node.getBounds() == null ? 1 : MapUtil.isFullBounds(node.getBounds()) ? 2 : 3;
                cells.add(Arrays.asList(node.getType().getKey().toString(), Long.toUnsignedString(BlockFlags.getBlockFlags(node.getType())), shape));
            }
        }
        if (present == 0) return null;
        return new TelemetryRecord(0, Map.of("origin", Arrays.asList(x - 1, y - 1, z - 1), "size", Arrays.asList(3, 4, 3), "cells", cells)).fields;
    }
}
