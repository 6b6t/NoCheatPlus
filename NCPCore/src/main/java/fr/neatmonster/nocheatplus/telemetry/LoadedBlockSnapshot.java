package fr.neatmonster.nocheatplus.telemetry;

import java.util.*;
import org.bukkit.Location;
import org.bukkit.World;
import fr.neatmonster.nocheatplus.compat.Folia;
import fr.neatmonster.nocheatplus.utilities.location.RichBoundsLocation;
import fr.neatmonster.nocheatplus.utilities.map.BlockCache;
import fr.neatmonster.nocheatplus.utilities.map.BlockFlags;
import fr.neatmonster.nocheatplus.utilities.map.MapUtil;

/** Rare telemetry rejection/correction only: owned, already-loaded blocks; never load chunks or compute shapes. */
public final class LoadedBlockSnapshot {
    private LoadedBlockSnapshot() {}
    public static Map<String, Object> capture(RichBoundsLocation loc) {
        return capture(loc.getWorld(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), loc.getBlockCache(), false);
    }
    /** Same cell layout, with an origin relative to floor(from/to), never an absolute block position. */
    public static Map<String, Object> captureRelative(Location loc) {
        return capture(loc.getWorld(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), null, true);
    }
    private static Map<String, Object> capture(World world, int x, int y, int z, BlockCache cache, boolean relative) {
        if (world == null) return null;
        int present = 0;
        Location probe = new Location(world, 0, 0, 0);
        List<Object> cells = new ArrayList<>(36);
        for (int dy=-1;dy<=2;dy++) for (int dz=-1;dz<=1;dz++) for (int dx=-1;dx<=1;dx++) {
            int bx=x+dx, by=y+dy, bz=z+dz;
            probe.setX(bx); probe.setY(by); probe.setZ(bz);
            // Ownership is checked before even querying loaded state at region/chunk edges.
            if (!Folia.isOwnedByCurrentRegion(probe) || !world.isChunkLoaded(Math.floorDiv(bx,16),Math.floorDiv(bz,16))) {
                cells.add(null); continue;
            }
            if (by < world.getMinHeight() || by >= world.getMaxHeight()) { cells.add(null); continue; }
            var type = world.getBlockAt(bx,by,bz).getType();
            var node = cache == null ? null : cache.getBlockCacheNode(bx,by,bz);
            int shape = node == null || node.getType() != type || !node.isBoundsFetched() ? 0
                : node.getBounds() == null ? 1 : MapUtil.isFullBounds(node.getBounds()) ? 2 : 3;
            cells.add(Arrays.asList(type.getKey().toString(),Long.toUnsignedString(BlockFlags.getBlockFlags(type)),shape));
            present++;
        }
        if (present == 0) return null;
        return new TelemetryRecord(0,Map.of("origin",relative ? Arrays.asList(-1,-1,-1) : Arrays.asList(x-1,y-1,z-1),"size",Arrays.asList(3,4,3),"cells",cells)).fields;
    }
}
