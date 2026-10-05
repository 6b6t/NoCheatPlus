package fr.neatmonster.nocheatplus;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.junit.Test;
import fr.neatmonster.nocheatplus.checks.moving.player.PendingFallDamage;

public class TestPendingFallDamage {
    private static class Fixture implements PendingFallDamage.Scheduler {
        final Player player = mock(Player.class);
        final World world = mock(World.class);
        final AtomicLong clock = new AtomicLong();
        final Object handle = new Object();
        Runnable tick, retired;
        int starts, cancels, applied;
        double damage;
        boolean owner = true, fail, retireDuringStart;
        final PendingFallDamage pending = new PendingFallDamage(this, clock::get, p -> owner,
                (p, e) -> { damage=e.getDamage(); applied++; if (fail) throw new IllegalStateException("damage failure"); });
        Fixture() {
            when(player.getUniqueId()).thenReturn(UUID.randomUUID());
            when(world.getUID()).thenReturn(UUID.randomUUID());
            when(player.getWorld()).thenReturn(world);
            when(player.isOnline()).thenReturn(true);
            when(player.isValid()).thenReturn(true);
            when(player.getNoDamageTicks()).thenReturn(10);
        }
        EntityDamageEvent event(double damage) {
            return new EntityDamageEvent(player, EntityDamageEvent.DamageCause.FALL, damage);
        }
        public Object start(Player p, Runnable tick, Runnable retired) {
            this.tick = tick; this.retired = retired; starts++;
            if (retireDuringStart) retired.run();
            return handle;
        }
        public void cancel(Object task) { if (task != null) cancels++; }
        void ready() { when(player.getNoDamageTicks()).thenReturn(0); }
    }
    @Test public void strongestRepeatedCallbackIsAppliedWithoutDoubleCharge() {
        Fixture f = new Fixture(); f.pending.queue(f.player, f.event(17));
        f.pending.queue(f.player, f.event(20)); f.pending.queue(f.player, f.event(5));
        f.ready(); f.tick.run(); assertEquals(20, f.damage, 0); assertEquals(1, f.applied);
    }
    @Test public void schedulingFailureRemovesPendingEntry() {
        Fixture f = new Fixture();
        PendingFallDamage pending = new PendingFallDamage(new PendingFallDamage.Scheduler() {
            public Object start(Player p, Runnable tick, Runnable retired) { throw new IllegalStateException(); }
            public void cancel(Object task) { }
        }, f.clock::get, p -> true, (p, e) -> fail("must not damage"));
        try { pending.queue(f.player, f.event(17)); fail("expected exception"); }
        catch (IllegalStateException expected) { }
        assertEquals(0, pending.size());
    }
    @Test public void teleportDuringSyntheticEventCannotQueueStaleCharge() {
        Fixture f = new Fixture(); Object identity = f.pending.identity(f.player);
        f.pending.cancel(f.player); f.pending.queue(f.player, f.event(17), identity);
        assertEquals(0, f.starts); assertEquals(0, f.pending.size());
    }
    @Test public void combatWaitsThenChargesOnce() {
        Fixture f = new Fixture(); f.pending.queue(f.player, f.event(17));
        f.tick.run(); assertEquals(0, f.applied); f.ready(); f.tick.run(); f.tick.run();
        assertEquals(1, f.applied); assertEquals(0, f.pending.size()); assertTrue(f.cancels > 0);
    }
    @Test public void repeatedCallbacksUseOneTaskAndDoNotRenewDeadline() {
        Fixture f = new Fixture(); f.pending.queue(f.player, f.event(17));
        f.clock.set(1_900_000_000L); f.pending.queue(f.player, f.event(20));
        assertEquals(1, f.starts); f.clock.set(2_000_000_000L); f.ready(); f.tick.run();
        assertEquals(0, f.applied); assertEquals(0, f.pending.size());
    }
    @Test public void tickDeadlineBoundsRepeatedInvulnerability() {
        Fixture f = new Fixture(); f.pending.queue(f.player, f.event(17));
        for (int i = 0; i < 41; i++) f.tick.run();
        assertEquals(0, f.pending.size()); assertEquals(0, f.applied);
    }
    @Test public void retiredBeforeHandleReturnedCleansUp() {
        Fixture f = new Fixture(); f.retireDuringStart = true; f.pending.queue(f.player, f.event(17));
        assertEquals(0, f.pending.size()); assertEquals(1, f.cancels);
    }
    @Test public void lifecycleCancellationPreventsOldChargeAfterRespawn() {
        Fixture f = new Fixture(); f.pending.queue(f.player, f.event(17)); Runnable old = f.tick;
        f.pending.cancel(f.player); f.pending.queue(f.player, f.event(5)); f.ready();
        old.run(); assertEquals(1, f.pending.size()); f.tick.run(); assertEquals(1, f.applied);
    }
    @Test public void deadDisconnectedAndInvalidPlayersAreDropped() {
        for (int kind = 0; kind < 3; kind++) {
            Fixture f = new Fixture(); f.pending.queue(f.player, f.event(17)); f.ready();
            if (kind == 0) when(f.player.isDead()).thenReturn(true);
            if (kind == 1) when(f.player.isOnline()).thenReturn(false);
            if (kind == 2) when(f.player.isValid()).thenReturn(false);
            f.tick.run(); assertEquals(0, f.applied); assertEquals(0, f.pending.size());
        }
    }
    @Test public void worldChangeDropsOldCharge() {
        Fixture f = new Fixture(); f.pending.queue(f.player, f.event(17)); f.ready();
        World other = mock(World.class); when(other.getUID()).thenReturn(UUID.randomUUID());
        when(f.player.getWorld()).thenReturn(other); f.tick.run(); assertEquals(0, f.applied);
    }
    @Test public void ownershipFailureDoesNotReadPlayer() {
        Fixture f = new Fixture(); f.pending.queue(f.player, f.event(17));
        clearInvocations(f.player); f.owner = false; f.tick.run();
        verifyNoInteractions(f.player); assertEquals(0, f.pending.size());
    }
    @Test public void damageExceptionCannotReplay() {
        Fixture f = new Fixture(); f.pending.queue(f.player, f.event(17)); f.ready(); f.fail = true;
        try { f.tick.run(); fail("expected exception"); } catch (IllegalStateException expected) { }
        f.tick.run(); assertEquals(1, f.applied); assertEquals(0, f.pending.size());
    }
    @Test public void cancelledEventAndPluginDisableDoNotCharge() {
        Fixture f = new Fixture(); EntityDamageEvent e = f.event(17); f.pending.queue(f.player, e);
        e.setCancelled(true); f.ready(); f.tick.run(); assertEquals(0, f.applied);
        f.pending.queue(f.player, f.event(17)); f.pending.clear(); f.tick.run();
        assertEquals(0, f.applied); assertEquals(0, f.pending.size());
    }
}
