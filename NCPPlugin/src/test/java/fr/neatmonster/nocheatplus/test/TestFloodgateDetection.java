package fr.neatmonster.nocheatplus.test;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.plugin.PluginManager;
import org.bukkit.entity.Player;
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

    @Test
    public void proxyIdentityFallbackIsBedrockButHasNoUnverifiedVersion() {
        assertTrue(BridgeMisc.isBedrockIdentity(".bedrock", UUID.randomUUID()));
        assertTrue(BridgeMisc.isBedrockIdentity("bedrock", BEDROCK));
        assertFalse(BridgeMisc.isBedrockIdentity("java", UUID.randomUUID()));
        final Player player = mock(Player.class);
        when(player.getName()).thenReturn(".bedrock");
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        try (var bukkit = mockStatic(Bukkit.class)) {
            final PluginManager plugins = mock(PluginManager.class);
            bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
            when(plugins.isPluginEnabled("floodgate")).thenReturn(false);
            final BridgeMisc.BedrockProfile profile = BridgeMisc.getBedrockProfile(player);
            assertTrue(profile.isBedrock());
            assertNull("Identity fallback cannot assume a client version", profile.getVersion());
        }
    }

    @Test
    public void collisionVersionGateMatchesOnlyPackagedTableVersions() {
        for (final String version : new String[] {"26.50", "26.51", "26.52", "26.50.4", "1.26.50", "1.26.50.4", "1.26.52"}) {
            assertTrue(version, BridgeMisc.supportsBedrockCollisionVersion(version));
        }
        for (final String version : new String[] {null, "26.49", "26.60", "1.26.60", "26.52.1"}) {
            assertFalse(String.valueOf(version), BridgeMisc.supportsBedrockCollisionVersion(version));
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
