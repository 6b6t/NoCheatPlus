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

    @Test public void aShortDeployAndCloseUseNativeDistance() {
        Air air = new Air(88, 8f);
        NoFall.onGlidingMove(air.player, air.data, -0.2);
        assertEquals(0f, air.data.noFallGlideCarry, 0);
        assertTrue(air.data.noFallGlideEstablished);
        when(air.player.getFallDistance()).thenReturn(1f);
        NoFall.stopGlide(air.player, air.data);
        assertEquals(1f, air.data.noFallFallDistance, 0);
        assertEquals(88, air.data.noFallMaxY, 0);
        verify(air.player, never()).setFallDistance(anyFloat());
    }

    @Test public void repeatedGlideMovesDoNotCreateDebtOrChangeNativeDistance() {
        Air air = new Air(93, 2f);
        for (int move = 0; move < 100; move++) NoFall.onGlidingMove(air.player, air.data, -0.2);
        assertEquals(0, air.data.noFallGlideTicks);
        assertEquals(0f, air.data.noFallGlideCarry, 0);
        assertEquals(2f, air.data.noFallFallDistance, 0);
        NoFall.stopGlide(air.player, air.data);
        verify(air.player, never()).setFallDistance(anyFloat());
    }

    @Test public void oldCheckpointDebtIsDiscardedOnClose() {
        Air air = new Air(88, 1f);
        air.data.noFallGlideTicks = 4;
        air.data.noFallGlideCarry = 8f;
        air.data.noFallFallDistance = 8f;
        NoFall.stopGlide(air.player, air.data);
        assertEquals(1f, air.data.noFallFallDistance, 0);
        assertEquals(0f, air.data.noFallGlideCarry, 0);
        verify(air.player, never()).setFallDistance(anyFloat());
    }

    @Test public void completedFallsInvalidateDelayedRestores() {
        Air air = new Air(90, 6f);
        long generation = air.data.noFallGeneration;
        assertTrue(NoFall.mayRestoreCancelledFall(air.data, generation));
        // Teleports invalidate before their outcome is processed.
        air.data.noFallGeneration++;
        assertFalse(NoFall.mayRestoreCancelledFall(air.data, generation));
        generation = air.data.noFallGeneration;
        // Reset media, pearl, contact and respawn all clear NoFall data.
        air.data.clearNoFallData();
        assertFalse(NoFall.mayRestoreCancelledFall(air.data, generation));
        generation = air.data.noFallGeneration;
        NoFall.onVehicleLanded(air.data);
        assertFalse(NoFall.mayRestoreCancelledFall(air.data, generation));
    }

    @Test public void aConfirmedAirborneRideRespectsNativeResetAndGroundContactEndsIt() {
        Air air = new Air(90, 6f);
        // A real ride reset must not resurrect pre-mount distance on air dismount.
        when(air.player.getFallDistance()).thenReturn(0f);
        NoFall.onVehicleAir(air.player, air.data, true);
        verify(air.player, never()).setFallDistance(anyFloat());
        assertEquals(0f, air.data.noFallFallDistance, 0);
        assertEquals(90, air.data.noFallMaxY, 0);
        when(air.player.getFallDistance()).thenReturn(2f);
        NoFall.onVehicleAir(air.player, air.data, true);
        assertEquals(2f, air.data.noFallFallDistance, 0);
        verify(air.player, never()).setFallDistance(anyFloat());
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

    @Test public void aSteepDiveAlsoUsesNativeDistance() {
        Air air = new Air(90, 4f);
        NoFall.onGlidingMove(air.player, air.data, -0.8);
        assertEquals(4f, air.data.noFallFallDistance, 0);
        assertEquals(90, air.data.noFallMaxY, 0);
    }
    @Test public void teleportAfterGlideUsesTheCurrentNativeReset() {
        Player player = mock(Player.class);
        MovingData data = mock(MovingData.class);
        data.noFallGlideEstablished = true;
        data.noFallFallDistance = 1f;
        when(player.getFallDistance()).thenReturn(0f);
        assertEquals(0f, NoFall.distanceForTeleport(player, data), 0f);
        when(player.getFallDistance()).thenReturn(3f);
        assertEquals(3f, NoFall.distanceForTeleport(player, data), 0f);
        data.noFallGlideEstablished = false;
        assertEquals(3f, NoFall.distanceForTeleport(player, data), 0f);
        verify(player, never()).setFallDistance(anyFloat());
    }
    @Test public void ordinaryTeleportRespectsAuthoritativeResetOutsideGlide() {
        Air air = new Air(90, 1.086f);
        when(air.player.getFallDistance()).thenReturn(0f);
        assertEquals(0f, NoFall.distanceForTeleport(air.player, air.data), 0f);
        when(air.player.getFallDistance()).thenReturn(6f);
        assertEquals(6f, NoFall.distanceForTeleport(air.player, air.data), 0f);
        verify(air.player, never()).setFallDistance(anyFloat());
    }
}
