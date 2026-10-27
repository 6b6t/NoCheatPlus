package fr.neatmonster.nocheatplus.compat.bukkit;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.junit.BeforeClass;
import org.junit.Test;
import fr.neatmonster.nocheatplus.compat.bukkit.model.BukkitStatic;
import fr.neatmonster.nocheatplus.players.IPlayerData;
import fr.neatmonster.nocheatplus.utilities.map.MapUtil;

public class TestBedrockProfileCache {
    @BeforeClass public static void materials() { TestNativeEntityOwnership.initializeLegacyClassifier(); }

    @Test
    public void editionVersionOwnershipAndCleanupControlTheClientProfile() {
        final World world = mock(World.class);
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        when(world.isChunkLoaded(0, 0)).thenReturn(true);
        final Block block = mock(Block.class);
        final BlockData data = mock(BlockData.class);
        when(world.getBlockAt(0, 64, 0)).thenReturn(block);
        when(block.getType()).thenReturn(Material.TRAPPED_CHEST);
        when(block.getBlockData()).thenReturn(data);
        when(data.getAsString()).thenReturn("minecraft:trapped_chest[facing=north,type=single,waterlogged=false]");
        final IPlayerData player = mock(IPlayerData.class);
        when(player.getBedrockVersion()).thenReturn("1.26.52");
        final var cache = new BlockCacheBukkitModern(Map.of(Material.TRAPPED_CHEST, new BukkitStatic(0.0625, 0.875)));
        cache.setAccess(world);
        try (var ownership = mockStatic(MapUtil.class)) {
            ownership.when(() -> MapUtil.areChunksLoadedAndOwned(world, 0, 0, 0.0)).thenReturn(true);
            cache.setPlayerData(player);
            assertNull("Java does not use the client profile", cache.getBedrockBounds(0, 64, 0));
            when(player.isBedrockPlayer()).thenReturn(true);
            cache.setPlayerData(player);
            final double[] expected = {0.025, 0, 0.025, 0.975, 0.95, 0.975};
            assertArrayEquals(expected, cache.getBedrockBounds(0, 64, 0), 0);
            assertArrayEquals("Primary model remains Java/Geyser-corrected", new double[] {0.0625, 0, 0.0625, 0.9375, 0.875, 0.9375}, cache.getBounds(0, 64, 0), 0);
            when(player.getBedrockVersion()).thenReturn("26.60");
            cache.setPlayerData(player);
            assertNull("An unverified version cannot reuse an old profile", cache.getBedrockBounds(0, 64, 0));
            when(player.getBedrockVersion()).thenReturn("26.52");
            cache.setPlayerData(player);
            ownership.when(() -> MapUtil.areChunksLoadedAndOwned(world, 0, 0, 0.0)).thenReturn(false);
            assertNull("Ownership is checked even for cached data", cache.getBedrockBounds(0, 64, 0));
            ownership.when(() -> MapUtil.areChunksLoadedAndOwned(world, 0, 0, 0.0)).thenReturn(true);
            cache.cleanup();
            cache.setAccess(world);
            assertNull("Pooled cache returns to Java", cache.getBedrockBounds(0, 64, 0));
            cache.setPlayerData(player);
            assertArrayEquals(expected, cache.getBedrockBounds(0, 64, 0), 0);
        } finally { cache.cleanup(); }
    }
}
