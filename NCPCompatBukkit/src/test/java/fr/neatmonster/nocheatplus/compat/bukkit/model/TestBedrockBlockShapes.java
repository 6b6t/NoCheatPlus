package fr.neatmonster.nocheatplus.compat.bukkit.model;

import static org.junit.Assert.*;
import java.util.Map;
import org.junit.Test;
import org.mockito.Mockito;
import fr.neatmonster.nocheatplus.logging.StaticLog;

public class TestBedrockBlockShapes {
    @Test
    public void onlyVerifiedClientVersionsUseTheTable() {
        for (final String version : new String[] {"26.50", "26.51", "26.52", "1.26.50.4", "1.26.52"}) {
            assertTrue(version, BedrockBlockShapes.supportsVersion(version));
        }
        for (final String version : new String[] {null, "", "26.5", "26.60", "26.30", "1.21.11", "unknown"}) {
            assertFalse(version, BedrockBlockShapes.supportsVersion(version));
        }
    }

    @Test
    public void pairedChestHalvesMeetWithoutCreatingAGap() {
        final double[] left = BedrockBlockShapes.get("minecraft:trapped_chest[facing=north,type=left,waterlogged=false]");
        final double[] right = BedrockBlockShapes.get("minecraft:trapped_chest[facing=north,type=right,waterlogged=false]");
        assertArrayEquals(new double[] {0.025, 0, 0.025, 1, 0.95, 0.975}, left, 0);
        assertArrayEquals(new double[] {0, 0, 0.025, 0.975, 0.95, 0.975}, right, 0);
        assertNull("Unsupported states must not invent a shape", BedrockBlockShapes.get("minecraft:stone"));
        assertNull("Offset-dependent blocks keep native behavior", BedrockBlockShapes.get(
                "minecraft:bamboo[age=0,leaves=none,stage=0]"));
    }

    @Test
    public void customGeyserBlocksAndUnknownStatesKeepNativeShapes() {
        final var access = BedrockBlockShapes.registryAccess(Registries.class);
        assertTrue(access.permits("minecraft:trapped_chest"));
        assertFalse(access.permits("minecraft:shelf_mushroom"));
        assertFalse(access.permits("minecraft:unknown"));
        assertTrue(BedrockBlockShapes.registryAccess(null).permits("minecraft:trapped_chest"));
        try (var log = Mockito.mockStatic(StaticLog.class)) {
            assertFalse("Incompatible Geyser internals fail closed", BedrockBlockShapes.registryAccess(String.class)
                    .permits("minecraft:trapped_chest"));
        }
    }

    public static class Registry {
        private final Map<?, ?> values;
        Registry(final Map<?, ?> values) { this.values = values; }
        public Map<?, ?> get() { return values; }
    }

    public static class Registries {
        public static final Registry JAVA_BLOCK_STATE_IDENTIFIER_TO_ID = new Registry(
                Map.of("minecraft:trapped_chest", 1, "minecraft:shelf_mushroom", 2));
        public static final Registry CUSTOM_BLOCK_STATE_OVERRIDES = new Registry(Map.of(2, "custom"));
    }
}
