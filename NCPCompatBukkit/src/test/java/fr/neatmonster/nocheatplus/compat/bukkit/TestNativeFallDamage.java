package fr.neatmonster.nocheatplus.compat.bukkit;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import org.bukkit.entity.Player;
import org.junit.Test;
import fr.neatmonster.nocheatplus.compat.AlmostBoolean;

/** Routing regressions; native modifiers/cooldown/events are verified on Fox. */
public class TestNativeFallDamage {
    private MCAccessBukkitModern access() {
        MCAccessBukkitModern access = mock(MCAccessBukkitModern.class);
        doCallRealMethod().when(access).dealFallDamage(any(Player.class), anyDouble());
        doCallRealMethod().when(access).dealFallDamageFiresAnEvent();
        return access;
    }

    @Test public void publicApiWorksWithoutNmsReflectionAndFiresItsOwnEvent() {
        MCAccessBukkitModern access = access();
        Player player = mock(Player.class);
        assertEquals(AlmostBoolean.YES, access.dealFallDamageFiresAnEvent());
        access.dealFallDamage(player, 9);
        verify(access).dealNativeFallDamage(player, 9);
        verifyNoInteractions(player);
    }

    @Test public void separateLandingsAreSubmittedSeparatelyEvenDuringImmunity() {
        MCAccessBukkitModern access = access();
        Player player = mock(Player.class);
        when(player.getNoDamageTicks()).thenReturn(100);
        access.dealFallDamage(player, 9);
        access.dealFallDamage(player, 5);
        var order = inOrder(access);
        order.verify(access).dealNativeFallDamage(player, 9);
        order.verify(access).dealNativeFallDamage(player, 5);
        verify(player, never()).getNoDamageTicks();
        verify(player, never()).damage(anyDouble());
    }

    @Test public void nativeFailureDoesNotRetryWithGenericDamage() {
        MCAccessBukkitModern access = access();
        Player player = mock(Player.class);
        doThrow(new IllegalStateException("native failure")).when(access).dealNativeFallDamage(player, 9);
        try { access.dealFallDamage(player, 9); fail("expected exception"); }
        catch (IllegalStateException expected) { }
        verifyNoInteractions(player);
        verify(access, times(1)).dealNativeFallDamage(player, 9);
    }
}
