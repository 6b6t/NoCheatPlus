package fr.neatmonster.nocheatplus.test;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.plugin.PluginManager;
import org.junit.Test;

import fr.neatmonster.nocheatplus.compat.BridgeMisc;
import fr.neatmonster.nocheatplus.logging.StaticLog;
import fr.neatmonster.nocheatplus.utilities.ReflectionUtil;

public class TestFloodgateDetection {

    private static final UUID BEDROCK = UUID.fromString("00000000-0000-0000-0009-01f6495d02a7");
    private static final String API = "org.geysermc.floodgate.api.FloodgateApi";

    @Test
    public void useAuthenticatedApiResult() {
        try (var bukkit = mockStatic(Bukkit.class); var reflection = mockStatic(ReflectionUtil.class)) {
            final PluginManager plugins = mock(PluginManager.class);
            bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
            when(plugins.isPluginEnabled("floodgate")).thenReturn(true);
            reflection.when(() -> ReflectionUtil.getClass(API)).thenReturn(FloodgateApi.class);
            assertTrue(BridgeMisc.isFloodgatePlayer(BEDROCK));
            assertEquals("1.26.52", BridgeMisc.getFloodgatePlayerVersion(BEDROCK));
            assertNull(BridgeMisc.getFloodgatePlayerVersion(UUID.randomUUID()));
            assertFalse("UUID format alone cannot identify Bedrock", BridgeMisc.isFloodgatePlayer(
                    UUID.fromString("00000000-0000-0000-0009-000000000001")));

            when(plugins.isPluginEnabled("floodgate")).thenReturn(false);
            assertFalse("Disabled Floodgate must not select Bedrock bounds", BridgeMisc.isFloodgatePlayer(BEDROCK));
        }
    }

    @Test
    public void absentOrIncompatibleApiDoesNotGrantBedrockBounds() {
        try (var bukkit = mockStatic(Bukkit.class); var reflection = mockStatic(ReflectionUtil.class);
                var logging = mockStatic(StaticLog.class)) {
            final PluginManager plugins = mock(PluginManager.class);
            bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
            when(plugins.isPluginEnabled("floodgate")).thenReturn(true);
            assertFalse(BridgeMisc.isFloodgatePlayer(BEDROCK));
            reflection.when(() -> ReflectionUtil.getClass(API)).thenReturn(String.class);
            assertFalse(BridgeMisc.isFloodgatePlayer(BEDROCK));
            assertNull(BridgeMisc.getFloodgatePlayerVersion(BEDROCK));
            reflection.when(() -> ReflectionUtil.getClass(API)).thenReturn(FailingApi.class);
            assertFalse(BridgeMisc.isFloodgatePlayer(BEDROCK));
        }
    }

    public static class FloodgateApi {
        public static FloodgateApi getInstance() {
            return new FloodgateApi();
        }

        public boolean isFloodgatePlayer(final UUID playerId) {
            return BEDROCK.equals(playerId);
        }

        public FloodgatePlayer getPlayer(final UUID playerId) {
            return BEDROCK.equals(playerId) ? new FloodgatePlayer() : null;
        }
    }

    public static class FloodgatePlayer {
        public String getVersion() { return "1.26.52"; }
    }

    public static class FailingApi {
        public static Object getInstance() {
            throw new IllegalStateException("API unavailable");
        }
    }
}
