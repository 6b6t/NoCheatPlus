package fr.neatmonster.nocheatplus.compat.bukkit;

import static org.junit.Assert.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import org.bukkit.Material;
import org.junit.BeforeClass;
import org.junit.Test;
import fr.neatmonster.nocheatplus.compat.bukkit.model.BedrockBlockShapes;
import fr.neatmonster.nocheatplus.compat.bukkit.model.BukkitStatic;
import fr.neatmonster.nocheatplus.utilities.map.BlockFlags;
import fr.neatmonster.nocheatplus.utilities.map.BlockProperties;

public class TestBedrockGroundProfiles {
    @BeforeClass
    public static void materials() { TestNativeEntityOwnership.initializeLegacyClassifier(); }

    @Test
    public void everyProfileHasRealSupportAndNoEmptyProfileCreatesGround() throws Exception {
        final long flags = BlockFlags.getBlockFlags(Material.STONE);
        BlockFlags.setBlockFlags(Material.STONE, BlockFlags.SOLID_GROUND | BlockFlags.FULL_BOUNDS);
        int states = 0;
        final var blocks = new HashSet<String>();
        try (var raw = getClass().getResourceAsStream("/bedrock/collisions-26.50.tsv.gz");
                var reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(raw), StandardCharsets.UTF_8))) {
            String row;
            while ((row = reader.readLine()) != null) {
                if (!row.startsWith("minecraft:")) continue;
                final String state = row.split("\t")[0];
                final double[] shape = BedrockBlockShapes.get(state);
                assertNotNull(state, shape);
                blocks.add(state.split("\\[")[0]);
                states++;
                if (shape.length == 0) continue;
                int highest = 0;
                for (int i = 6; i < shape.length; i += 6) if (shape[i + 4] > shape[highest + 4]) highest = i;
                final double x = (shape[highest] + shape[highest + 3]) / 2;
                final double z = (shape[highest + 2] + shape[highest + 5]) / 2;
                for (final int y : new int[] {-60, 0, 200}) {
                    final var cache = cache(shape, y, false);
                    cache.setBedrockCache(true);
                    final double top = y + shape[highest + 4];
                    assertTrue(state + " Y=" + y, BlockProperties.isOnGround(cache,
                            x - 0.001, top - 0.025, z - 0.001, x + 0.001, top, z + 0.001));
                }
            }
            assertEquals(13357, states);
            assertEquals(191, blocks.size());
        } finally { BlockFlags.setBlockFlags(Material.STONE, flags); }
    }

    @Test
    public void passableAcceptsClientFreeSpaceButRejectsSolidVolume() {
        final long flags = BlockFlags.getBlockFlags(Material.STONE);
        BlockFlags.setBlockFlags(Material.STONE, BlockFlags.SOLID_GROUND | BlockFlags.FULL_BOUNDS);
        try {
            final var cache = cache(new double[] {0, 0, 0, 0.5, 1, 1}, 64, false);
            assertFalse(BlockProperties.isPassableBox(cache, 0, 64, 0, 0.6, 64.2, 0.2, 0.9, 64.9, 0.8));
            cache.setBedrockCache(true);
            assertTrue(BlockProperties.isPassableBox(cache, 0, 64, 0, 0.6, 64.2, 0.2, 0.9, 64.9, 0.8));
            assertFalse(BlockProperties.isPassableBox(cache, 0, 64, 0, 0.1, 64.2, 0.2, 0.4, 64.9, 0.8));
            cache.cleanup();
            assertFalse("Cache reuse must restore Java validation", BlockProperties.isPassableBox(cache,
                    0, 64, 0, 0.6, 64.2, 0.2, 0.9, 64.9, 0.8));
        } finally { BlockFlags.setBlockFlags(Material.STONE, flags); }
    }

    @Test
    public void shorterClientShapeStillKeepsTranslatedJavaGround() {
        final long flags = BlockFlags.getBlockFlags(Material.STONE);
        BlockFlags.setBlockFlags(Material.STONE, BlockFlags.SOLID_GROUND | BlockFlags.FULL_BOUNDS);
        try {
            final var cache = cache(new double[] {0.1, 0, 0.1, 0.9, 0.5, 0.9}, 64, false);
            assertTrue(ground(cache, 65));
            cache.setBedrockCache(true);
            assertTrue("Shorter Bedrock geometry cannot remove Geyser-corrected Java support", ground(cache, 65));
            assertTrue("Client support remains valid too", ground(cache, 64.5));
            assertTrue("Client-free body volume is still passable", BlockProperties.isPassableBox(cache,
                    0, 64, 0, 0.2, 64.6, 0.2, 0.8, 64.9, 0.8));
        } finally { BlockFlags.setBlockFlags(Material.STONE, flags); }
    }

    @Test
    public void translatedJavaContactsStayValidAndAnObstructingBlockStillBlocks() {
        final long flags = BlockFlags.getBlockFlags(Material.STONE);
        BlockFlags.setBlockFlags(Material.STONE, BlockFlags.SOLID_GROUND | BlockFlags.FULL_BOUNDS);
        try {
            final double[] raised = {0.1, 0, 0.1, 0.9, 1.5, 0.9};
            final var cache = cache(raised, 64, false);
            cache.setBedrockCache(true);
            assertTrue("Native translated top remains valid", ground(cache, 65));
            assertTrue("Bedrock top is additional support", ground(cache, 65.5));
            assertFalse("Raw shapes do not add hovering credit", ground(cache, 65.55));
            assertFalse("Ignored blocks cannot add support", BlockProperties.isOnGround(cache,
                    0.2, 65.475, 0.2, 0.8, 65.5, 0.8, BlockFlags.F_GROUND));
            final var obstructed = cache(raised, 64, true);
            obstructed.setBedrockCache(true);
            assertFalse("Feet inside the block above cannot count as Bedrock support", ground(obstructed, 65.5));
        } finally { BlockFlags.setBlockFlags(Material.STONE, flags); }
    }

    private static boolean ground(final BlockCacheBukkitModern cache, final double top) {
        return BlockProperties.isOnGround(cache, 0.2, top - 0.025, 0.2, 0.8, top, 0.8);
    }

    private static BlockCacheBukkitModern cache(final double[] client, final int blockY, final boolean blockedAbove) {
        return new BlockCacheBukkitModern(Map.of(Material.STONE, new BukkitStatic(0, 1))) {
            { minBlockY = -64; maxBlockY = 319; }
            @Override public Material fetchTypeId(final int x, final int y, final int z) {
                return x == 0 && z == 0 && (y == blockY || blockedAbove && y == blockY + 1) ? Material.STONE : Material.AIR;
            }
            @Override public int fetchData(final int x, final int y, final int z) { return 0; }
            @Override public double[] getBedrockBounds(final int x, final int y, final int z) {
                return isBedrockCache() && x == 0 && y == blockY && z == 0 ? client : null;
            }
        };
    }
}
