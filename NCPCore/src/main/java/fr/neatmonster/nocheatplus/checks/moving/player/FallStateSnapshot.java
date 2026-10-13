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

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import fr.neatmonster.nocheatplus.checks.moving.MovingData;

/** Entity-owned descent state carried by Fox's ordinary player-data checkpoint. */
public final class FallStateSnapshot {
    private static final NamespacedKey STATE = new NamespacedKey("nocheatplus", "fall_state_v2");
    private static final NamespacedKey WORLD = new NamespacedKey("nocheatplus", "fall_world_v2");
    private FallStateSnapshot() { }

    public static void clear(Player player) {
        var container = player.getPersistentDataContainer();
        container.remove(STATE);
        container.remove(WORLD);
    }

    public static void save(Player player, MovingData data, Location accepted) {
        if (!(data.noFallFallDistance > 0) && data.noFallCurrentLocOnWindChargeHit == null
                || !Double.isFinite(data.noFallMaxY)
                || !Float.isFinite(data.noFallFallDistance)) {
            clear(player);
            return;
        }
        var container = player.getPersistentDataContainer();
        container.set(WORLD, PersistentDataType.STRING, accepted.getWorld().getKey().toString());
        container.set(STATE, PersistentDataType.LONG_ARRAY,
                new long[] {Double.doubleToRawLongBits(accepted.getY()),
                        Double.doubleToRawLongBits(data.noFallMaxY), Double.doubleToRawLongBits(data.noFallFallDistance),
                        Double.doubleToRawLongBits(data.noFallCurrentLocOnWindChargeHit == null
                                ? Double.NaN : data.noFallCurrentLocOnWindChargeHit.getY())});
    }

    public static void restore(Player player, MovingData data) {
        restore(player, data, false);
    }

    public static void restore(Player player, MovingData data, boolean preserveOrdinaryTeleport) {
        var container = player.getPersistentDataContainer();
        final long[] bits;
        final String world;
        try {
            bits = container.get(STATE, PersistentDataType.LONG_ARRAY);
            world = container.get(WORLD, PersistentDataType.STRING);
        } catch (IllegalArgumentException | ClassCastException invalid) {
            clear(player);
            return;
        }
        double[] state = bits == null ? null : java.util.stream.LongStream.of(bits).mapToDouble(Double::longBitsToDouble).toArray();
        Location current = player.getLocation();
        if (state == null) return;
        if (state.length != 4 || !Double.isFinite(state[0]) || !Double.isFinite(state[1])
                || !Double.isFinite(state[2]) || state[2] < 0 || state[2] > Float.MAX_VALUE || state[1] < state[0]
                || Math.abs(state[0]) > 30_000_000 || Math.abs(state[1]) > 30_000_000
                || (!Double.isNaN(state[3]) && (!Double.isFinite(state[3]) || Math.abs(state[3]) > 30_000_000))
                || !current.getWorld().getKey().toString().equals(world)
                || !preserveOrdinaryTeleport && Math.abs(current.getY() - state[0]) > 1.0e-5) {
            clear(player);
            return;
        }
        if (Math.abs(current.getY() - state[0]) > 1.0e-5) {
            // Fox ordinary teleports arrive through join. Carry only the descent
            // before placement, never the spatial gap or its wind context.
            data.clearWindChargeImpulse();
            NoFall.afterTeleport(player, data, current, (float) state[2], true);
            clear(player);
            return;
        }
        // Neither age nor repeated migration forgives an unfinished descent.
        // Successful ordinary teleports/death/respawn explicitly clear the checkpoint.
        data.noFallFallDistance = Math.max(data.noFallFallDistance, (float) state[2]);
        data.noFallMaxY = Math.max(data.noFallMaxY, state[1]);
        data.noFallCurrentLocOnWindChargeHit = Double.isNaN(state[3]) ? null :
                new Location(current.getWorld(), current.getX(), state[3], current.getZ());
    }
}
