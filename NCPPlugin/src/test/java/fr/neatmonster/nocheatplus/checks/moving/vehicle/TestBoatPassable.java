package fr.neatmonster.nocheatplus.checks.moving.vehicle;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import org.bukkit.Material;
import fr.neatmonster.nocheatplus.actions.ActionList;
import fr.neatmonster.nocheatplus.actions.Action;
import fr.neatmonster.nocheatplus.checks.ViolationData;
import fr.neatmonster.nocheatplus.checks.moving.location.setback.SetBackEntry;
import org.junit.Before;
import org.junit.Test;

import fr.neatmonster.nocheatplus.test.BlockTests;
import fr.neatmonster.nocheatplus.checks.moving.MovingData;
import fr.neatmonster.nocheatplus.checks.moving.MovingConfig;
import fr.neatmonster.nocheatplus.checks.moving.model.VehicleMoveInfo;
import fr.neatmonster.nocheatplus.checks.moving.model.MoveInfo;
import fr.neatmonster.nocheatplus.players.IPlayerData;
import fr.neatmonster.nocheatplus.checks.moving.location.setback.DefaultSetBackStorage;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import fr.neatmonster.nocheatplus.utilities.location.RichEntityLocation;
import fr.neatmonster.nocheatplus.utilities.map.FakeBlockCache;

public class TestBoatPassable {
    private FakeBlockCache blocks;

    @Before
    public void setup() {
        BlockTests.initBlockProperties();
        blocks = new FakeBlockCache();
    }

    private RichEntityLocation at(final double x, final double y, final double z) {
        final RichEntityLocation location = mock(RichEntityLocation.class);
        when(location.getX()).thenReturn(x);
        when(location.getY()).thenReturn(y);
        when(location.getZ()).thenReturn(z);
        when(location.getWidth()).thenReturn(1.375);
        when(location.getBoxMarginVertical()).thenReturn(0.5625);
        when(location.getBlockCache()).thenReturn(blocks);
        return location;
    }

    private boolean blocked(final double x0, final double y0, final double z0,
            final double x1, final double y1, final double z1) {
        final var from = at(x0, y0, z0);
        final var to = at(x1, y1, z1);
        when(from.isSamePos(to)).thenReturn(x0 == x1 && y0 == y1 && z0 == z1);
        return VehicleEnvelope.isBoatMoveBlocked(from, to);
    }

    private SetBackEntry correction(final RichEntityLocation from, final RichEntityLocation to,
            final SetBackEntry fallback) throws Exception {
        final VehicleMoveInfo info = mock(VehicleMoveInfo.class);
        for (final String end : new String[]{"from", "to"}) {
            final var field = MoveInfo.class.getField(end);
            field.setAccessible(true);
            field.set(info, end.equals("from") ? from : to);
        }
        final MovingData data = mock(MovingData.class);
        final MovingConfig config = mock(MovingConfig.class);
        final DefaultSetBackStorage storage = mock(DefaultSetBackStorage.class);
        when(storage.getValidSafeMediumEntry()).thenReturn(fallback);
        final var storageField = MovingData.class.getField("vehicleSetBacks");
        storageField.setAccessible(true);
        storageField.set(data, storage);
        final ActionList actions = mock(ActionList.class);
        doReturn(new Action[0]).when(actions).getActions(anyDouble());
        final var actionsField = MovingConfig.class.getField("vehicleEnvelopeActions");
        actionsField.setAccessible(true);
        actionsField.set(config, actions);
        final VehicleEnvelope check = mock(VehicleEnvelope.class, CALLS_REAL_METHODS);
        final ViolationData result = mock(ViolationData.class);
        when(result.willCancel()).thenReturn(true);
        doReturn(result).when(check).executeActions(any(ViolationData.class));
        return check.checkBoatPassable(mock(Player.class), mock(Entity.class), info,
                data, config, mock(IPlayerData.class));
    }

    @Test
    public void correctionStopsAtTheWallInsteadOfAnOldCheckpointAndKeepsTheLook() throws Exception {
        blocks.set(1, 1, 0, Material.STONE);
        final var from = at(0, 1, 0.5);
        when(from.getWorldName()).thenReturn("world");
        final var to = at(0.5, 1, 0.5);
        when(to.getYaw()).thenReturn(73f);
        when(to.getPitch()).thenReturn(-12f);
        final var old = new SetBackEntry().set("world", -5, 1, 0.5, 0, 0, 1, 0);
        final var target = correction(from, to, old);
        assertEquals(0.3125, target.getX(), 0.001);
        assertEquals(1, target.getY(), 0);
        assertEquals(73, target.getYaw(), 0);
        assertEquals(-12, target.getPitch(), 0);
        assertEquals(-5, old.getX(), 0);
    }

    @Test
    public void longDownwardAttemptStopsAtTheFloor() throws Exception {
        blocks.set(0, 0, 0, Material.STONE);
        final var from = at(0.5, 3, 0.5);
        when(from.getWorldName()).thenReturn("world");
        final var old = new SetBackEntry().set("world", 0.5, 3, 0.5, 0, 0, 1, 0);
        final var target = correction(from, at(0.5, -7, 0.5), old);
        assertEquals(1, target.getY(), 0.003);
        assertTrue("The correction must stay on the clear side of the floor", target.getY() >= 1 - 1.0E-7);
    }

    @Test
    public void correctionCannotSelectAClearEndpointBeyondAWall() throws Exception {
        blocks.set(3, 1, 0, Material.STONE);
        final var from = at(0.5, 1, 0.5);
        when(from.getWorldName()).thenReturn("world");
        final var old = new SetBackEntry().set("world", 0.5, 1, 0.5, 0, 0, 1, 0);
        assertEquals(2.3125, correction(from, at(6.5, 1, 0.5), old).getX(), 0.002);
    }

    @Test
    public void existingOverlapUsesTheVerifiedCheckpoint() throws Exception {
        blocks.set(0, 1, 0, Material.STONE);
        blocks.set(3, 1, 0, Material.STONE);
        final var old = new SetBackEntry().set("world", -3, 1, 0.5, 0, 0, 1, 0);
        assertSame(old, correction(at(0.5, 1, 0.5), at(3.5, 1, 0.5), old));
    }

    @Test
    public void slowPhasingCannotEnterAWallFromExactContact() {
        blocks.set(1, 1, 0, Material.STONE);
        assertTrue(blocked(0.3125, 1, 0.5, 0.3625, 1, 0.5));
    }

    @Test
    public void tinyStepsCannotEnterTheLiveGrassWall() {
        blocks.set(-134, 108, -423, Material.GRASS_BLOCK);
        assertTrue(blocked(-132.3125, 108.2, -422.27, -132.3175, 108.2, -422.27));
    }

    @Test
    public void freeEndpointsCannotTunnelAcrossAWall() {
        blocks.set(3, 1, 0, Material.STONE);
        assertTrue(blocked(0.5, 1, 0.5, 6.5, 1, 0.5));
    }

    @Test
    public void banksAndGroundCanBeTouchedWithoutPenetrating() {
        for (int x = -2; x <= 3; x++) blocks.set(x, 0, 0, Material.STONE);
        for (int z = -2; z <= 3; z++) blocks.set(1, 1, z, Material.STONE);
        assertFalse(blocked(0.3125, 1, 0.5, 0.3125, 1, 2.5));
    }

    @Test
    public void waterAndIceTravelRemainClear() {
        for (final Material surface : new Material[]{Material.WATER, Material.ICE, Material.BLUE_ICE}) {
            blocks = new FakeBlockCache();
            for (int x = -2; x <= 6; x++) blocks.set(x, 0, 0, surface);
            assertFalse(surface.toString(), blocked(0.5, 1, 0.5, 4.5, 1, 0.5));
        }
    }

    @Test
    public void boatHeightAllowsClearanceBelowALowCeiling() {
        for (int x = -2; x <= 4; x++) {
            blocks.set(x, 1, 0, Material.OAK_TRAPDOOR, 8, new double[]{0, 0.8125, 0, 1, 1, 1});
        }
        assertFalse(blocked(0.5, 1, 0.5, 2.5, 1, 0.5));
    }

    @Test
    public void fallingThroughAFloorIsBlocked() {
        blocks.set(0, 0, 0, Material.STONE);
        assertTrue(blocked(0.5, 1.1, 0.5, 0.5, 0.8, 0.5));
        assertFalse(blocked(0.5, 1.1, 0.5, 0.5, 1, 0.5));
    }

    @Test
    public void boatFlightInOpenAirHasNoSpeedOrGravityRestriction() {
        assertFalse(blocked(0, 20, 0, 5, 24, 0));
        assertFalse(blocked(0, 24, 0, 5, 20, 0));
        assertFalse(blocked(0, 20, 0, 5, 20, 0));
        assertFalse(blocked(0, 20, 0, 100, 20, 0));
    }

    @Test
    public void boatCheckAcceptsOpenAirFlightWithoutRunningThePhysicsEnvelope() throws Exception {
        final VehicleMoveInfo info = mock(VehicleMoveInfo.class);
        for (final String end : new String[]{"from", "to"}) {
            final var field = MoveInfo.class.getField(end);
            field.setAccessible(true);
            field.set(info, end.equals("from") ? at(0, 20, 0) : at(5, 24, 0));
        }
        final VehicleEnvelope check = mock(VehicleEnvelope.class, CALLS_REAL_METHODS);
        final MovingData data = mock(MovingData.class);
        final DefaultSetBackStorage storage = mock(DefaultSetBackStorage.class);
        final var field = MovingData.class.getField("vehicleSetBacks");
        field.setAccessible(true);
        field.set(data, storage);
        when(info.to.isPassableBox()).thenReturn(true);
        assertNull(check.checkBoatPassable(mock(Player.class), mock(Entity.class), info,
                data, mock(MovingConfig.class), mock(IPlayerData.class)));
        verify(check, never()).check(any(), any(), any(), anyBoolean(), any(), any(), any(), any());
        verify(storage).setSafeMediumEntry(info.to);
    }

    @Test
    public void existingOverlapCanBeEscapedAndRotationIsIgnored() {
        blocks.set(0, 1, 0, Material.STONE);
        assertFalse(blocked(0.5, 1, 0.5, 2.5, 1, 0.5));
        assertFalse(blocked(0.5, 1, 0.5, 0.5, 1, 0.5));
    }
}
