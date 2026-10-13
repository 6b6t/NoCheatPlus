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

    public static boolean exists(Player player) {
        return player.getPersistentDataContainer().has(STATE, PersistentDataType.LONG_ARRAY);
    }

    public static void clear(Player player) {
        var container = player.getPersistentDataContainer();
        container.remove(STATE);
        container.remove(WORLD);
    }

    public static void save(Player player, MovingData data, Location accepted) {
        // Legacy glide fields remain readable, but no pre-deploy debt is created.
        // A pending landing confirmation is part of the fall until settled:
        // the next owner settles it from its own native state (NoFall.resolveHandoff).
        final boolean handoff = data.noFallHandoffDistance > 0f && validDebt(data.noFallHandoffDistance)
                && validCoordinate(data.noFallHandoffX) && validCoordinate(data.noFallHandoffY)
                && validCoordinate(data.noFallHandoffZ);
        final boolean owed = data.noFallFallDistance > 0f || data.noFallGlideCarry > 0f
                || NoFall.retainedNativeResetDebt(data) > 0f || handoff
                || data.noFallCurrentLocOnWindChargeHit != null;
        if (!owed || !Double.isFinite(data.noFallMaxY) || !Float.isFinite(data.noFallFallDistance)
                || !Float.isFinite(data.noFallGlideCarry)
                || !validDebt(data.noFallCancelledAirFallDebt) || !validDebt(data.noFallSetBackFallDebt)
                || !validDebt(data.noFallContactFallDebt) || accepted.getWorld() == null) {
            clear(player);
            return;
        }
        var container = player.getPersistentDataContainer();
        container.set(WORLD, PersistentDataType.STRING, accepted.getWorld().getKey().toString());
        container.set(STATE, PersistentDataType.LONG_ARRAY,
                new long[] {Double.doubleToRawLongBits(accepted.getY()),
                        // A departure above the tracked maximum (a reverted landing
                        // back on its arc) starts its descent there; restore rejects
                        // a maximum below the saved position.
                        Double.doubleToRawLongBits(Math.max(data.noFallMaxY, accepted.getY())),
                        Double.doubleToRawLongBits(data.noFallFallDistance),
                        Double.doubleToRawLongBits(data.noFallCurrentLocOnWindChargeHit == null
                                ? Double.NaN : data.noFallCurrentLocOnWindChargeHit.getY()),
                        Double.doubleToRawLongBits(data.noFallGlideTicks),
                        Double.doubleToRawLongBits(data.noFallGlideCarry),
                        Double.doubleToRawLongBits(data.noFallGlideEstablished ? 1.0 : 0.0),
                        Double.doubleToRawLongBits(data.noFallCancelledAirFallDebt),
                        Double.doubleToRawLongBits(data.noFallSetBackFallDebt),
                        Double.doubleToRawLongBits(data.noFallContactFallDebt),
                        Double.doubleToRawLongBits(accepted.getX()), Double.doubleToRawLongBits(accepted.getZ()),
                        Double.doubleToRawLongBits(5.0),
                        Double.doubleToRawLongBits(data.noFallGlideLimitPending ? 1.0 : 0.0),
                        Double.doubleToRawLongBits(handoff ? data.noFallHandoffDistance : 0.0),
                        Double.doubleToRawLongBits(handoff ? data.noFallHandoffX : 0.0),
                        Double.doubleToRawLongBits(handoff ? data.noFallHandoffY : 0.0),
                        Double.doubleToRawLongBits(handoff ? data.noFallHandoffZ : 0.0),
                        Double.doubleToRawLongBits(handoff && data.noFallHandoffSilent ? 1.0 : 0.0)});
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
        // v5 = v4 plus the pending landing confirmation (distance, contact, may native process it silently).
        final boolean v5 = state.length == 19 && state[12] == 5.0 && validDebt(state[14])
                && validCoordinate(state[15]) && validCoordinate(state[16]) && validCoordinate(state[17])
                && (state[18] == 0.0 || state[18] == 1.0);
        final boolean limit = (state.length == 14 && state[12] == 4.0 || v5) && (state[13] == 0.0 || state[13] == 1.0);
        final boolean migration = limit || state.length == 13 && state[12] == 3.0;
        final boolean explicit = state.length == 12 || migration;
        final boolean glide = (state.length == 7 || explicit) && Double.isFinite(state[4]) && state[4] >= 0 && state[4] <= 20 * 60 * 30
                && Double.isFinite(state[5]) && state[5] >= 0 && state[5] <= Float.MAX_VALUE
                && (state[6] == 0.0 || state[6] == 1.0);
        if ((state.length != 4 && !glide)
                || explicit && (!validDebt(state[7]) || !validDebt(state[8]) || !validDebt(state[9])
                    || !Double.isFinite(state[10]) || !Double.isFinite(state[11])
                    || Math.abs(state[10]) > 30_000_000 || Math.abs(state[11]) > 30_000_000) || !Double.isFinite(state[0]) || !Double.isFinite(state[1])
                || !Double.isFinite(state[2]) || state[2] < 0 || state[2] > Float.MAX_VALUE || state[1] < state[0]
                || Math.abs(state[0]) > 30_000_000 || Math.abs(state[1]) > 30_000_000
                || (!Double.isNaN(state[3]) && (!Double.isFinite(state[3]) || Math.abs(state[3]) > 30_000_000))
                || !current.getWorld().getKey().toString().equals(world)
                || !migration && !preserveOrdinaryTeleport && placementChanged(current, state)) {
            clear(player);
            return;
        }
        if (!migration && (player.isGliding() || glide && (state[4] > 0 || state[5] > 0 || state[6] == 1.0))) {
            // Native entity data carries the glide distance across workers. Old
            // seven-value snapshots must not revive the removed pre-glide debt.
            NoFall.onGlidingMove(player, data, 0.0);
            if (!player.isGliding()) NoFall.stopGlide(player, data);
            clear(player);
            return;
        }
        if (!migration && placementChanged(current, state)) {
            // Fox ordinary teleports arrive through join. Native entity data
            // owns retention/resets; the checkpoint must not resurrect old debt.
            data.clearWindChargeImpulse();
            NoFall.afterTeleport(player, data, current, (float) state[2], true);
            clear(player);
            return;
        }
        // Neither age nor repeated migration forgives an unfinished descent.
        // Successful ordinary teleports/death/respawn explicitly clear the checkpoint.
        data.noFallFallDistance = Math.max(data.noFallFallDistance, (float) state[2]);
        // New checkpoints are invalidated by approved ordinary teleport/reset
        // events. Coordinate drift and Fox's migration relocation are not reset
        // evidence. Re-anchor at arrival without charging the placement delta.
        data.noFallMaxY = Math.max(data.noFallMaxY, migration
                ? current.getY() + Math.max(state[2], state[1] - state[0]) : state[1]);
        data.noFallCurrentLocOnWindChargeHit = Double.isNaN(state[3]) ? null :
                new Location(current.getWorld(), current.getX(), state[3], current.getZ());
        data.clearGlideDebt();
        if (migration) {
            data.noFallGlideEstablished = state[6] == 1.0 && player.isGliding();
            data.noFallGlideLimitPending = limit && state[13] == 1.0;
            data.noFallGlideAccountingTick = player.getTicksLived() - 1;
        }
        if (explicit) {
            data.noFallCancelledAirFallDebt = Math.max(data.noFallCancelledAirFallDebt, (float) state[7]);
            data.noFallSetBackFallDebt = Math.max(data.noFallSetBackFallDebt, (float) state[8]);
            data.noFallContactFallDebt = Math.max(data.noFallContactFallDebt, (float) state[9]);
        }
        if (v5 && state[14] > 0.0 && state[14] > data.noFallHandoffDistance) {
            // Still unconfirmed: the contact keeps its own position, so the next
            // owner settles it against that contact, not against its arrival.
            data.noFallHandoffDistance = (float) state[14];
            data.noFallHandoffX = state[15];
            data.noFallHandoffY = state[16];
            data.noFallHandoffZ = state[17];
            data.noFallHandoffSilent = state[18] == 1.0;
        }
    }

    private static boolean validDebt(double value) {
        return Double.isFinite(value) && value >= 0 && value <= Float.MAX_VALUE;
    }

    private static boolean validCoordinate(double value) {
        return Double.isFinite(value) && Math.abs(value) <= 30_000_000;
    }

    private static boolean placementChanged(Location current, double[] state) {
        return Math.abs(current.getY() - state[0]) > 1.0e-5
                || state.length >= 12 && (Math.abs(current.getX() - state[10]) > 1.0e-5
                    || Math.abs(current.getZ() - state[11]) > 1.0e-5);
    }
}
