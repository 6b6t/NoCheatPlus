package fr.neatmonster.nocheatplus.compat.bukkit;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.Map;

import org.bukkit.Material;
import org.junit.BeforeClass;
import org.junit.Test;

import fr.neatmonster.nocheatplus.compat.bukkit.model.BukkitStatic;
import fr.neatmonster.nocheatplus.players.IPlayerData;
import fr.neatmonster.nocheatplus.utilities.map.BlockFlags;
import fr.neatmonster.nocheatplus.utilities.map.BlockProperties;

public class TestBedrockEnderChest {

    @BeforeClass
    public static void initializeMaterials() {
        TestNativeEntityOwnership.initializeLegacyClassifier();
    }

    @Test
    public void recordedBedrockHeightIsGroundOnlyForBedrock() {
        final long flags = BlockFlags.getBlockFlags(Material.ENDER_CHEST);
        BlockFlags.setBlockFlags(Material.ENDER_CHEST, BlockFlags.SOLID_GROUND);
        try {
            final BlockCacheBukkitModern cache = cache(Material.ENDER_CHEST);
            assertFalse(ground(cache, 64.95));
            assertTrue(ground(cache, 64.875));
            cache.cleanup();

            final IPlayerData player = mock(IPlayerData.class);
            when(player.isBedrockPlayer()).thenReturn(true);
            cache.setPlayerData(player);
            assertTrue("Recorded standing height must not count as hovering", ground(cache, 64.95));
            assertTrue("Bedrock's wider rim supports the player", BlockProperties.isOnGround(
                    cache, 0.026, 64.925, 0.026, 0.06, 64.95, 0.06));
            assertFalse("Air above the lid is not ground", ground(cache, 65.0));
            assertFalse("No support outside the rim", BlockProperties.isOnGround(
                    cache, 0.976, 64.925, 0.2, 0.99, 64.95, 0.8));

            cache.cleanup();
            assertFalse("Pooled cache must not leak Bedrock bounds to Java", ground(cache, 64.95));
            assertTrue(ground(cache, 64.875));
        }
        finally {
            BlockFlags.setBlockFlags(Material.ENDER_CHEST, flags);
        }
    }

    @Test
    public void nonBinaryLidHeightWorksAcrossWorldHeights() {
        final long flags = BlockFlags.getBlockFlags(Material.ENDER_CHEST);
        BlockFlags.setBlockFlags(Material.ENDER_CHEST, BlockFlags.SOLID_GROUND);
        try {
            for (final int y : new int[] {-64, -60, 0, 64, 128, 200, 319}) {
                final BlockCacheBukkitModern cache = cache(Material.ENDER_CHEST, y);
                cache.setBedrockCache(true);
                final double top = y + 0.95;
                assertTrue("Ground at Y=" + y, ground(cache, top));
                assertFalse("Inside lid at Y=" + y, ground(cache, Math.nextDown(top)));
                assertFalse("Above lid at Y=" + y, ground(cache, top + 0.05));
            }
        }
        finally {
            BlockFlags.setBlockFlags(Material.ENDER_CHEST, flags);
        }
    }

    @Test
    public void otherChestTypesKeepTheirNativeModel() {
        for (final Material material : new Material[] {Material.CHEST, Material.TRAPPED_CHEST}) {
            final BlockCacheBukkitModern cache = cache(material);
            cache.setBedrockCache(true);
            assertArrayEquals(new double[] {0.0625, 0.0, 0.0625, 0.9375, 0.875, 0.9375},
                    cache.getBounds(0, 64, 0), 0.0);
        }
    }

    private static boolean ground(final BlockCacheBukkitModern cache, final double feet) {
        return BlockProperties.isOnGround(cache, 0.2, feet - 0.025, 0.2, 0.8, feet, 0.8);
    }

    private static BlockCacheBukkitModern cache(final Material material) {
        return cache(material, 64);
    }

    private static BlockCacheBukkitModern cache(final Material material, final int blockY) {
        return new BlockCacheBukkitModern(Map.of(material, new BukkitStatic(0.0625, 0.875))) {
            {
                minBlockY = -64;
                maxBlockY = 319;
            }

            @Override
            public Material fetchTypeId(final int x, final int y, final int z) {
                return x == 0 && y == blockY && z == 0 ? material : Material.AIR;
            }

            @Override
            public int fetchData(final int x, final int y, final int z) {
                return 0;
            }
        };
    }
}
