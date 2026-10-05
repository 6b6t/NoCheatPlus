package fr.neatmonster.nocheatplus;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.Test;
import fr.neatmonster.nocheatplus.checks.moving.MovingData;
import fr.neatmonster.nocheatplus.checks.moving.player.NoFall;

public class TestNoFallGlideDebt {
    private static final class Air {
        final Player player = mock(Player.class);
        final MovingData data = mock(MovingData.class, CALLS_REAL_METHODS);
        Air(double y, float fall) {
            World world = mock(World.class);
            when(player.getLocation()).thenReturn(new Location(world, 16.5, y, 16.5));
            when(player.getFallDistance()).thenReturn(fall);
            data.noFallFallDistance = fall;
            data.noFallMaxY = y;
        }
    }

    @Test public void aShortDeployKeepsTheFallThatHappenedBeforeIt() {
        Air air = new Air(88, 8f);
        NoFall.onGlidingMove(air.player, air.data, -0.2);
        assertEquals(1, air.data.noFallGlideTicks);
        assertEquals(8f, air.data.noFallGlideCarry, 0);
        assertEquals(8f, air.data.noFallFallDistance, 0);
        for (int tick = 0; tick < 4; tick++) NoFall.onGlidingMove(air.player, air.data, -0.2);
        assertFalse(air.data.noFallGlideEstablished);
        assertEquals(8f, air.data.noFallFallDistance, 0);
        // Vanilla zeroes fallDistance when the elytra opens. Closing it must put the debt back.
        when(air.player.getFallDistance()).thenReturn(0f);
        NoFall.stopGlide(air.player, air.data);
        verify(air.player).setFallDistance(8f);
        assertEquals(0, air.data.noFallGlideTicks);
        assertEquals(8f, air.data.noFallGlideCarry, 0);
    }

    @Test public void aRealGlideHandsTheLandingBackToVanilla() {
        Air air = new Air(93, 0f);
        for (int tick = 0; tick < NoFall.GLIDE_ESTABLISH_TICKS; tick++) NoFall.onGlidingMove(air.player, air.data, -0.2);
        assertTrue(air.data.noFallGlideEstablished);
        assertEquals(0f, air.data.noFallGlideCarry, 0);
        assertEquals(0f, air.data.noFallFallDistance, 0);
        NoFall.stopGlide(air.player, air.data);
        verify(air.player, never()).setFallDistance(anyFloat());
    }

    @Test public void anAirborneVehicleKeepsTheFallAndAGroundedOneEndsIt() {
        Air air = new Air(90, 6f);
        // Mounting a vehicle zeroes the Bukkit fall distance. Leaving in the air must restore it.
        when(air.player.getFallDistance()).thenReturn(0f);
        NoFall.onVehicleAir(air.player, air.data, true);
        verify(air.player).setFallDistance(6f);
        assertEquals(6f, air.data.noFallFallDistance, 0);
        NoFall.onVehicleLanded(air.data);
        assertEquals(0f, air.data.noFallFallDistance, 0);
        assertEquals(0f, air.data.noFallGlideCarry, 0);
        assertTrue(air.data.noFallSkipAirCheck);
    }

    @Test public void aBoatUnderTheFeetIsSupportAndADistantBoxIsNot() {
        // Oak boat summoned at y=88, feet measured at 88.4825 during the cancelled landing.
        assertTrue(NoFall.boxSupportsFeet(16.5, 88.4825, 16.5, 15.8125, 88.0, 15.8125, 17.1875, 88.5625, 17.1875));
        // Same boat after it settled on the stone pad. Feet were 81.5625.
        assertTrue(NoFall.boxSupportsFeet(16.5, 81.5625, 16.5, 15.8125, 81.0, 15.8125, 17.1875, 81.5625, 17.1875));
        // A stacked second boat. Feet were 82.125.
        assertTrue(NoFall.boxSupportsFeet(16.5, 82.125, 16.5, 15.8125, 81.5625, 15.8125, 17.1875, 82.125, 17.1875));
        assertFalse(NoFall.boxSupportsFeet(16.5, 90.0, 16.5, 15.8125, 81.0, 15.8125, 17.1875, 81.5625, 17.1875));
    }

    @Test public void aSteepDiveDoesNotUseTheGentleClear() {
        Air air = new Air(90, 4f);
        NoFall.onGlidingMove(air.player, air.data, -0.8);
        assertEquals(4f, air.data.noFallFallDistance, 0);
        assertEquals(90, air.data.noFallMaxY, 0);
    }
}
