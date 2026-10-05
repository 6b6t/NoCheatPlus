package fr.neatmonster.nocheatplus.compat.bukkit;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.junit.Test;
import fr.neatmonster.nocheatplus.checks.moving.player.NoFall;
import fr.neatmonster.nocheatplus.compat.versions.ServerVersion;

public class TestNativeFallAttributes {
    @Test public void safeDistanceAndDamageMultiplierUseTheNative26xFormula() {
        String old = ServerVersion.getMinecraftVersion();
        try {
            ServerVersion.setMinecraftVersion("26.2");
            Player player = mock(Player.class);
            AttributeInstance safe = mock(AttributeInstance.class), multiplier = mock(AttributeInstance.class);
            when(safe.getValue()).thenReturn(5.0); when(multiplier.getValue()).thenReturn(0.5);
            when(player.getAttribute(Attribute.GENERIC_SAFE_FALL_DISTANCE)).thenReturn(safe);
            when(player.getAttribute(Attribute.GENERIC_FALL_DAMAGE_MULTIPLIER)).thenReturn(multiplier);
            assertEquals(3,NoFall.getDamage(player,12.9f),0);
        } finally { ServerVersion.setMinecraftVersion(old); }
    }
}
