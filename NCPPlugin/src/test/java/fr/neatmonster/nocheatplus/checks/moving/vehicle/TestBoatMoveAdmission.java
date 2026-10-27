package fr.neatmonster.nocheatplus.checks.moving.vehicle;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.junit.Test;
import fr.neatmonster.nocheatplus.checks.moving.MovingConfig;
import fr.neatmonster.nocheatplus.checks.moving.MovingData;
import fr.neatmonster.nocheatplus.checks.moving.util.AuxMoving;
import fr.neatmonster.nocheatplus.players.DataManager;
import fr.neatmonster.nocheatplus.players.IPlayerData;
import fr.neatmonster.nocheatplus.utilities.entity.PassengerUtil;

public class TestBoatMoveAdmission {
    private enum Correction { NONE, STARTING, PENDING }
    @Test
    public void knownBoatsAreCheckedOnTheVehicleMoveEvent() throws Exception {
        admission(EntityType.BOAT, Correction.NONE, true);
    }

    @Test
    public void otherKnownVehiclesKeepTheirExistingEventPath() throws Exception {
        admission(EntityType.MINECART, Correction.NONE, false);
    }

    @Test
    public void pendingCorrectionDoesNotAdmitAnotherBoatPosition() throws Exception {
        admission(EntityType.BOAT, Correction.PENDING, false);
    }

    @Test
    public void correctionSetupDoesNotAdmitAnotherBoatPosition() throws Exception {
        admission(EntityType.BOAT, Correction.STARTING, false);
    }

    private void admission(final EntityType kind, final Correction correction, final boolean expected) throws Exception {
        final VehicleChecks checks = mock(VehicleChecks.class, CALLS_REAL_METHODS);
        final AuxMoving aux = mock(AuxMoving.class);
        final PassengerUtil passengers = mock(PassengerUtil.class);
        for (final var entry : Map.of("normalVehicles", Set.of(kind), "aux", aux, "passengerUtil", passengers).entrySet()) {
            final var field = VehicleChecks.class.getDeclaredField(entry.getKey());
            field.setAccessible(true);
            field.set(checks, entry.getValue());
        }
        final Vehicle boat = mock(Vehicle.class);
        final Player player = mock(Player.class);
        final World world = mock(World.class);
        final IPlayerData pData = mock(IPlayerData.class);
        final MovingData data = mock(MovingData.class);
        final MovingConfig config = mock(MovingConfig.class);
        final var ignored = MovingConfig.class.getField("ignoredVehicles");
        ignored.setAccessible(true);
        ignored.set(config, new HashSet<EntityType>());
        when(boat.getType()).thenReturn(kind);
        when(boat.getWorld()).thenReturn(world);
        when(boat.isValid()).thenReturn(true);
        when(passengers.getFirstPlayerPassenger(boat)).thenReturn(player);
        when(pData.getGenericInstance(MovingData.class)).thenReturn(data);
        when(pData.getGenericInstance(MovingConfig.class)).thenReturn(config);
        if (correction == Correction.PENDING) data.vehicleSetBackTaskId = new Object();
        data.isVehicleSetBack = correction == Correction.STARTING;
        final var boundary = new IllegalStateException("entered collision preparation");
        when(aux.useVehicleMoveInfo()).thenThrow(boundary);
        final var event = new VehicleMoveEvent(boat, new Location(world, 0, 10, 0), new Location(world, 0.5, 10, 0));
        boolean entered = false;
        try (var players = mockStatic(DataManager.class)) {
            players.when(() -> DataManager.getPlayerData(player)).thenReturn(pData);
            try {
                checks.onVehicleMove(event);
            }
            catch (IllegalStateException reached) {
                assertSame(boundary, reached);
                entered = true;
            }
        }
        assertEquals(expected, entered);
    }
}
