package fr.neatmonster.nocheatplus;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.junit.Test;
import fr.neatmonster.nocheatplus.checks.moving.MovingConfig;
import fr.neatmonster.nocheatplus.checks.moving.MovingData;
import fr.neatmonster.nocheatplus.checks.moving.MovingListener;

public class TestNativeWindChargeVelocity {
    @Test public void nativeImpulseUsesCurrentMomentumAndConsumesEstimateImmediately() {
        Player player = mock(Player.class);
        MovingData data = mock(MovingData.class);
        MovingConfig config = mock(MovingConfig.class);
        when(player.getTicksLived()).thenReturn(23);
        when(player.getVelocity()).thenReturn(new Vector(0.2, -1.0, 0));
        data.windChargeImpulseEntityTick = 23;
        data.windChargeImpulseSource = java.util.UUID.randomUUID();
        data.shouldApplyExplosionVelocity = true;
        data.explosionVelAxisY = 1.5;
        MovingListener.acceptNativeWindChargeVelocity(player, data, config, new Vector(0.1, -0.2, 0.3));
        verify(data).addVelocity(player, config, 0.2 + 0.1, -1.2, 0.3);
        assertFalse(data.shouldApplyExplosionVelocity);
        assertEquals(0.0, data.explosionVelAxisY, 0.0);
        verify(data, never()).removeAllVelocity();
        // An unrelated later callback cannot renew this wind's credit.
        MovingListener.acceptNativeWindChargeVelocity(player, data, config, new Vector(0, 3, 0));
        verify(data, times(1)).addVelocity(any(), any(), anyDouble(), anyDouble(), anyDouble());
    }

    @Test public void aLaterEntityTickCannotBankTheWindContext() {
        Player player = mock(Player.class);
        MovingData data = mock(MovingData.class);
        data.windChargeImpulseEntityTick = 23;
        data.windChargeImpulseSource = java.util.UUID.randomUUID();
        when(player.getTicksLived()).thenReturn(24);
        MovingListener.acceptNativeWindChargeVelocity(player, data, mock(MovingConfig.class), new Vector(0, 3, 0));
        verifyNoInteractions(data);
        verify(player, never()).getVelocity();
    }

    @Test public void lifecycleResetPreventsImpulseReuse() {
        Player player = mock(Player.class);
        MovingData data = mock(MovingData.class, CALLS_REAL_METHODS);
        data.windChargeImpulseEntityTick = 23;
        data.windChargeImpulseSource = java.util.UUID.randomUUID();
        when(player.getTicksLived()).thenReturn(23);
        data.clearWindChargeImpulse();
        MovingListener.acceptNativeWindChargeVelocity(player, data, mock(MovingConfig.class), new Vector(0, 3, 0));
        verify(player, never()).getVelocity();
    }

    @Test public void invalidNativeVectorCreatesNoAllowance() {
        Player player = mock(Player.class);
        MovingData data = mock(MovingData.class);
        data.windChargeImpulseEntityTick = 23;
        data.windChargeImpulseSource = java.util.UUID.randomUUID();
        when(player.getTicksLived()).thenReturn(23);
        when(player.getVelocity()).thenReturn(new Vector());
        MovingListener.acceptNativeWindChargeVelocity(player, data, mock(MovingConfig.class), new Vector(Double.NaN, 1, 0));
        verify(data, never()).addVelocity(any(), any(), anyDouble(), anyDouble(), anyDouble());
    }

    @Test public void ownerlessWindHitCreditsTheDirectProjectileWithoutPendingCredit() {
        Player player = mock(Player.class);
        MovingData data = mock(MovingData.class);
        MovingConfig config = mock(MovingConfig.class);
        org.bukkit.entity.Entity projectile = mock(org.bukkit.entity.Entity.class);
        when(projectile.getType()).thenReturn(fr.neatmonster.nocheatplus.compat.BridgeEntityType.WIND_CHARGE);
        when(player.getVelocity()).thenReturn(new Vector(0, -1, 0));
        // No shooter/Bukkit source context exists for an ownerless real projectile.
        MovingListener.acceptNativeWindChargeHitVelocity(player, data, config, projectile, new Vector(0, 0, .4));
        verify(data).addVelocity(player, config, 0, -1, .4);
        verify(data, never()).removeAllVelocity();
        assertNull(data.windChargeImpulseSource);
        assertNull(data.nativeWindChargeSource);
    }

    @Test public void unrelatedProjectileHitGetsNoWindAllowance() {
        Player player = mock(Player.class);
        MovingData data = mock(MovingData.class);
        MovingConfig config = mock(MovingConfig.class);
        org.bukkit.entity.Entity projectile = mock(org.bukkit.entity.Entity.class);
        when(projectile.getType()).thenReturn(org.bukkit.entity.EntityType.ARROW);
        MovingListener.acceptNativeWindChargeHitVelocity(player, data, config, null, new Vector(0, 0, .4));
        MovingListener.acceptNativeWindChargeHitVelocity(player, data, config, projectile, new Vector(0, 0, .4));
        verifyNoInteractions(data);
        verify(player, never()).getVelocity();
    }
}
