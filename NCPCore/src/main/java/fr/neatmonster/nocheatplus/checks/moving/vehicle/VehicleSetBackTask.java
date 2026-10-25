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
package fr.neatmonster.nocheatplus.checks.moving.vehicle;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import fr.neatmonster.nocheatplus.NCPAPIProvider;
import fr.neatmonster.nocheatplus.checks.moving.MovingData;
import fr.neatmonster.nocheatplus.checks.moving.MovingConfig;
import fr.neatmonster.nocheatplus.checks.moving.util.AuxMoving;
import fr.neatmonster.nocheatplus.compat.BridgeMisc;
import fr.neatmonster.nocheatplus.compat.Folia;
import fr.neatmonster.nocheatplus.logging.StaticLog;
import fr.neatmonster.nocheatplus.players.DataManager;
import fr.neatmonster.nocheatplus.players.IPlayerData;
import fr.neatmonster.nocheatplus.utilities.entity.PassengerUtil;

/**
 * Task for scheduling a vehicle set back. Resets the vehicleSetBackTaskId in
 * the MovingData for the player.
 * 
 * @author mc_dev
 *
 */
public class VehicleSetBackTask implements Runnable{
    private final Entity  vehicle;
    private final Player player;
    private final Location location;
    private final boolean debug;
    private final Entity[] passengers;

    /**
     * 
     * @param vehicle
     * @param player
     *            The player because of whom this teleport is happening. This
     *            should be the player in charge of steering, but that needn't
     *            be the case in future.
     * @param location
     * @param debug
     */
    public VehicleSetBackTask(Entity vehicle, Player player, Location location, boolean debug, Entity... passengers) {
        this.vehicle = vehicle;
        this.player = player;
        this.location = location;
        this.debug = debug;
        this.passengers = passengers.length == 0 ? vehicle.getPassengers().toArray(new Entity[0]) : passengers;
    }

    @Override
    public void run() {
        final IPlayerData pData = DataManager.getPlayerData(player);
        final MovingData data = pData.getGenericInstance(MovingData.class);
        data.vehicleSetBackTaskId = null;
        try{
            if (Folia.isFoliaServer()) {
                correctOnFolia(data, pData);
                return;
            }
            NCPAPIProvider.getNoCheatPlusAPI().getGenericInstance(PassengerUtil.class).teleportWithPassengers(
                    vehicle, player, location, debug, passengers, true, pData);
        }
        catch(Throwable t){
            StaticLog.logSevere(t);
        }
    }

    private void correctOnFolia(final MovingData data, final IPlayerData pData) {
        final Plugin plugin = Bukkit.getPluginManager().getPlugin("NoCheatPlus");
        data.isVehicleSetBack = true;
        final var teleport = Folia.teleportEntityWithPassengersAsync(vehicle, location,
                BridgeMisc.TELEPORT_CAUSE_CORRECTION_OF_POSITION);
        data.vehicleSetBackTaskId = teleport;
        teleport.whenComplete((success, error) -> Folia.runSyncTaskForEntity(vehicle, plugin, task -> {
            if (data.vehicleSetBackTaskId != teleport) return;
            data.vehicleSetBackTaskId = null;
            data.isVehicleSetBack = false;
            if (error != null) {
                StaticLog.logSevere(error);
                return;
            }
            if (!Boolean.TRUE.equals(success)) {
                StaticLog.logWarning("Vehicle correction failed for " + player.getName());
                return;
            }
            if (!vehicle.getPassengers().contains(player)) return;
            final MovingConfig config = pData.getGenericInstance(MovingConfig.class);
            final AuxMoving aux = NCPAPIProvider.getNoCheatPlusAPI().getGenericInstance(AuxMoving.class);
            aux.resetVehiclePositions(vehicle, vehicle.getLocation(), data, config);
            aux.resetPositionsAndMediumProperties(player, player.getLocation(), data, config);
            data.timeSinceSetBack = 0;
        }, () -> {
            if (data.vehicleSetBackTaskId == teleport) {
                data.vehicleSetBackTaskId = null;
                data.isVehicleSetBack = false;
            }
        }));
    }
}
