/*
 * This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package fr.neatmonster.nocheatplus.checks.moving.player;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import fr.neatmonster.nocheatplus.compat.BridgeHealth;

/** Bounded, entity-owned fallback. No player access occurs from retirement callbacks. */
public final class PendingFallDamage {
    public interface Scheduler {
        Object start(Player player, Runnable tick, Runnable retired);
        void cancel(Object task);
    }
    private final ConcurrentHashMap<UUID, Charge> charges = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Object> identities = new ConcurrentHashMap<>();
    private final Scheduler scheduler;
    private final LongSupplier clock;
    private final Predicate<Player> owns;
    private final BiConsumer<Player, EntityDamageEvent> apply;

    public PendingFallDamage(Scheduler scheduler, LongSupplier clock, Predicate<Player> owns,
            BiConsumer<Player, EntityDamageEvent> apply) {
        this.scheduler = scheduler;
        this.clock = clock;
        this.owns = owns;
        this.apply = apply;
    }

    public Object identity(Player player) {
        return identities.computeIfAbsent(player.getUniqueId(), ignored -> new Object());
    }

    public void queue(Player player, EntityDamageEvent event) {
        queue(player, event, identity(player));
    }

    public void queue(Player player, EntityDamageEvent event, Object identity) {
        if (identities.get(player.getUniqueId()) != identity) return;
        final UUID id = player.getUniqueId();
        final Charge charge = new Charge(player, event);
        final Charge old = charges.putIfAbsent(id, charge);
        if (old != null) {
            // Coalesce repeated callbacks within one invulnerability window without renewing its age.
            // Separate falls inside this window are not independently accounted for yet.
            if (old.player == player) {
                if (BridgeHealth.getRawDamage(event) > BridgeHealth.getRawDamage(old.event)) old.event = event;
                return;
            }
            old.stop();
            queue(player, event, identity);
            return;
        }
        try {
            charge.task = scheduler.start(player, charge::tick, charge::stop);
            if (charge.task == null) charge.stop();
            // Retirement can run before start returns its handle.
            if (charge.stopped) scheduler.cancel(charge.task);
        } catch (RuntimeException | Error failure) {
            charge.stop();
            throw failure;
        }
    }

    public void cancel(Player player) {
        identities.remove(player.getUniqueId());
        Charge charge = charges.get(player.getUniqueId());
        if (charge != null && charge.player == player) charge.stop();
    }

    public void clear() {
        identities.clear();
        for (Charge charge : charges.values()) charge.stop();
    }

    public int size() { return charges.size(); }

    private final class Charge {
        final Player player;
        final UUID id;
        final UUID world;
        final long created = clock.getAsLong();
        volatile EntityDamageEvent event;
        volatile Object task;
        volatile boolean stopped;
        int ticks;

        Charge(Player player, EntityDamageEvent event) {
            this.player = player;
            this.id = player.getUniqueId();
            this.world = player.getWorld().getUID();
            this.event = event;
        }

        void tick() {
            if (stopped) return;
            try {
                // Both deadlines bound sustained invulnerability and stalled scheduling.
                if (++ticks > 40 || clock.getAsLong() - created >= 2_000_000_000L
                        || !owns.test(player)) { stop(); return; }
                if (!player.isOnline() || !player.isValid() || player.isDead()
                        || !world.equals(player.getWorld().getUID())) { stop(); return; }
                if (player.getNoDamageTicks() > 0) return;
                final EntityDamageEvent ready = event;
                // Remove before damage: exceptions and reentrant damage callbacks cannot replay it.
                stop();
                if (!ready.isCancelled()) apply.accept(player, ready);
            } catch (RuntimeException | Error failure) {
                stop();
                throw failure;
            }
        }

        void stop() {
            stopped = true;
            charges.remove(id, this);
            scheduler.cancel(task);
        }
    }
}
