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
package fr.neatmonster.nocheatplus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.io.File;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

import fr.neatmonster.nocheatplus.checks.CheckType;
import fr.neatmonster.nocheatplus.checks.combined.Combined;
import fr.neatmonster.nocheatplus.checks.combined.CombinedData;
import fr.neatmonster.nocheatplus.checks.moving.ElytraResidual;
import fr.neatmonster.nocheatplus.checks.moving.MovingConfig;
import fr.neatmonster.nocheatplus.checks.moving.MovingData;
import fr.neatmonster.nocheatplus.checks.moving.MovingListener;
import fr.neatmonster.nocheatplus.checks.moving.location.tracking.LocationTrace.TraceEntryPool;
import fr.neatmonster.nocheatplus.checks.moving.util.AuxMoving;
import fr.neatmonster.nocheatplus.checks.moving.vehicle.VehicleChecks;
import fr.neatmonster.nocheatplus.compat.Folia;
import fr.neatmonster.nocheatplus.players.DataManager;
import fr.neatmonster.nocheatplus.utilities.location.PlayerLocation;
import fr.neatmonster.nocheatplus.utilities.map.BlockProperties;
import fr.neatmonster.nocheatplus.checks.workaround.WRPT;
import fr.neatmonster.nocheatplus.logging.LogManager;
import fr.neatmonster.nocheatplus.players.IPlayerData;

/**
 * Elytra unequip window and the player lifecycle: every reset path closes an open
 * window on the real {@link MovingData} and keeps the once-per-40-moves count
 * (REVIEW-CODE findings 1 and 4).
 */
public class TestElytraResidualLifecycle {

    private static final double CAP = 2.7;

    private MovingData data;
    private MovingConfig config;
    private IPlayerData pData;
    private AuxMoving aux;
    private Player player;
    private World world;

    @Before
    public void setup() throws Exception {
        final LogManager logs = mock(LogManager.class);
        final PluginTests.UnitTestNoCheatPlusAPI api = new PluginTests.UnitTestNoCheatPlusAPI() {
            @Override public LogManager getLogManager() { return logs; }
        };
        NCPAPIProvider.setNoCheatPlusAPI(api);
        api.registerGenericInstance(WRPT.class, new WRPT());
        api.registerGenericInstance(TraceEntryPool.class, new TraceEntryPool(100));
        aux = mock(AuxMoving.class);
        api.registerGenericInstance(AuxMoving.class, aux);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getVersion).thenReturn("Folia (MC: 26.2)");
            config = new MovingConfig(api.getWorldDataManager().getWorldData(UUID.randomUUID().toString()));
        }
        pData = mock(IPlayerData.class);
        when(pData.getGenericInstance(MovingConfig.class)).thenReturn(config);
        when(pData.getGenericInstance(CombinedData.class)).thenReturn(new CombinedData());
        data = new MovingData(config, pData);
        when(pData.getGenericInstance(MovingData.class)).thenReturn(data);
        world = mock(World.class);
        final UUID id = UUID.randomUUID();
        when(world.getUID()).thenReturn(id);
        when(world.getName()).thenReturn(id.toString());
        player = mock(Player.class);
        when(player.getName()).thenReturn("LabElytra");
        when(player.isOnline()).thenReturn(true);
        when(player.getWorld()).thenReturn(world);
        when(pData.isCheckActive(CheckType.MOVING, player)).thenReturn(true);
    }

    /** Open a fresh window (as if 40 moves passed since the last one). */
    private void openWindow() {
        data.elytraResidual.sinceBegin = ElytraResidual.REARM_MOVES;
        assertTrue(ElytraResidual.begin(data.elytraResidual, 1.6, -0.3, 160, CAP));
        assertTrue(ElytraResidual.allow(data.elytraResidual, 1.6, -0.3, false, false, false));
        assertEquals(1, data.elytraResidual.sinceBegin);
    }

    private void assertClosedAndCooldownKept(final String path) {
        assertFalse(path + " closes the window", data.elytraResidual.open);
        assertEquals(path + " keeps the re-arm count", 1, data.elytraResidual.sinceBegin);
        assertFalse(path + ": no credit afterwards",
                ElytraResidual.allow(data.elytraResidual, 1.6, -0.3, false, false, false));
        assertFalse(path + ": no new window inside 40 moves",
                ElytraResidual.begin(data.elytraResidual, 1.6, -0.3, 160, CAP));
    }

    /** A listener without its constructor, with the fields the reset paths use. */
    private MovingListener listener() throws Exception {
        final MovingListener listener = mock(MovingListener.class, CALLS_REAL_METHODS);
        set(listener, MovingListener.class, "aux", aux);
        set(listener, MovingListener.class, "hoverTicks", ConcurrentHashMap.newKeySet());
        set(listener, MovingListener.class, "playersEnforce", ConcurrentHashMap.newKeySet());
        set(listener, MovingListener.class, "useLoc", new Location(null, 0, 0, 0));
        set(listener, fr.neatmonster.nocheatplus.checks.CheckListener.class, "mcAccess",
                NCPAPIProvider.getNoCheatPlusAPI().getGenericInstanceHandle(fr.neatmonster.nocheatplus.compat.MCAccess.class));
        return listener;
    }

    private static void set(final Object target, final Class<?> owner, final String name, final Object value) throws Exception {
        if (value instanceof Location && java.util.Arrays.stream(owner.getDeclaredFields()).noneMatch(f -> f.getName().equals(name))) {
            return; // Merged with NoFall v4: shared scratch Locations are invocation-local there.
        }
        final Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object call(final Object target, final String name, final Class<?>[] types, final Object... args) throws Exception {
        final Method method = MovingListener.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    @Test
    public void sameWorldRelogEndsTheWindowOnLeaveAndOnJoin() throws Exception {
        final Location loc = new Location(world, 100.5, 200.0, 16.5);
        data.setSetBack(loc);

        // Leave: player data outlives the logout.
        openWindow();
        data.onPlayerLeave();
        assertClosedAndCooldownKept("leave");

        // Join in the same world keeps the old set back and fly data, but never the window.
        openWindow();
        assertTrue(data.hasSetBack());
        assertTrue("same world: the join path does not call clearFlyData", data.hasSetBackWorldChanged(loc));
        call(listener(), "dataOnJoin",
                new Class<?>[] {Player.class, Location.class, boolean.class, MovingData.class, MovingConfig.class, boolean.class},
                player, loc.clone(), false, data, config, false);
        assertTrue("set back kept", data.hasSetBack());
        assertClosedAndCooldownKept("join");

        // Respawn uses the same path.
        openWindow();
        call(listener(), "dataOnJoin",
                new Class<?>[] {Player.class, Location.class, boolean.class, MovingData.class, MovingConfig.class, boolean.class},
                player, loc.clone(), true, data, config, false);
        assertClosedAndCooldownKept("respawn");
    }

    @Test
    public void setBackPreparationEndsTheWindow() {
        openWindow();
        data.prepareSetBack(new Location(world, 10.5, 100.0, 10.5));
        assertClosedAndCooldownKept("prepareSetBack");
    }

    @Test
    public void cancelledMoveEndsTheWindow() throws Exception {
        openWindow();
        final Location from = new Location(world, 10.5, 100.0, 10.5);
        try (MockedStatic<Combined> combined = mockStatic(Combined.class)) {
            call(listener(), "onCancelledMove",
                    new Class<?>[] {Player.class, Location.class, int.class, long.class, MovingData.class,
                            MovingConfig.class, CombinedData.class, IPlayerData.class},
                    player, from, 1, 1000L, data, config, new CombinedData(), pData);
        }
        assertClosedAndCooldownKept("onCancelledMove");
    }

    @Test
    public void legacySetToCorrectionEndsTheWindow() throws Exception {
        final Location to = new Location(world, 10.5, 100.0, 10.5);
        data.setTeleported(to);
        openWindow();
        call(listener(), "onPlayerMoveMonitorNotCancelledHasTeleported",
                new Class<?>[] {Player.class, Location.class, MovingData.class, IPlayerData.class, boolean.class},
                player, to.clone(), data, pData, false);
        assertClosedAndCooldownKept("legacy SET_TO");
    }

    @Test
    public void changedEndpointEndsTheWindow() throws Exception {
        openWindow();
        // The last move is not valid or ends elsewhere: something (a plugin, a set back) changed the endpoint.
        data.playerMoves.invalidate();
        final Location from = new Location(world, 10.5, 100.0, 10.5);
        final Location to = new Location(world, 11.5, 99.8, 10.5);
        when(player.getLocation()).thenReturn(to.clone());
        try (MockedStatic<Combined> combined = mockStatic(Combined.class)) {
            call(listener(), "onMoveMonitorNotCancelled",
                    new Class<?>[] {Player.class, Location.class, Location.class, long.class, long.class,
                            CombinedData.class, MovingData.class, MovingConfig.class, IPlayerData.class},
                    player, from, to, 1000L, 1L, new CombinedData(), data, config, pData);
        }
        assertClosedAndCooldownKept("changed endpoint");
    }

    /** Fill a scratch location the way Bukkit's getLocation(Location) does. */
    private org.mockito.stubbing.Answer<Location> at(final World in, final double x, final double y, final double z) {
        return call -> {
            final Location target = call.getArgument(0);
            target.setWorld(in);
            target.setX(x);
            target.setY(y);
            target.setZ(z);
            return target;
        };
    }

    private VehicleChecks vehicleChecks() throws Exception {
        final VehicleChecks checks = mock(VehicleChecks.class, CALLS_REAL_METHODS);
        set(checks, VehicleChecks.class, "aux", aux);
        set(checks, VehicleChecks.class, "useLocEnter", new Location(null, 0, 0, 0));
        set(checks, VehicleChecks.class, "useLocVehicleEnter", new Location(null, 0, 0, 0));
        return checks;
    }

    /** REVIEW-CODE-2 finding 2: mounting during a window. */
    @Test
    public void vehicleEnterEndsTheWindow() throws Exception {
        openWindow();
        final Entity vehicle = mock(Entity.class);
        when(vehicle.getLocation(any(Location.class))).thenAnswer(at(world, 10.5, 100.0, 10.5));
        when(player.getLocation(any(Location.class))).thenAnswer(at(world, 10.5, 100.5, 10.5));
        // Merged with NoFall v4: VehicleChecks reads fresh (invocation-local) locations.
        when(vehicle.getLocation()).thenReturn(new Location(world, 10.5, 100.0, 10.5));
        when(player.getLocation()).thenReturn(new Location(world, 10.5, 100.5, 10.5));
        final Method enter = VehicleChecks.class.getDeclaredMethod("dataOnVehicleEnter",
                Player.class, Entity.class, MovingData.class, IPlayerData.class);
        enter.setAccessible(true);
        enter.invoke(vehicleChecks(), player, vehicle, data, pData);
        assertClosedAndCooldownKept("vehicle enter");
    }

    /** REVIEW-CODE-2 finding 2: dismount, here through the missed-exit path (no VehicleExitEvent). */
    @Test
    public void vehicleExitEndsTheWindow() throws Exception {
        openWindow();
        final Block air = mock(Block.class);
        when(air.getType()).thenReturn(Material.AIR);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(air);
        when(world.getBlockAt(any(Location.class))).thenReturn(air);
        when(player.getLocation()).thenReturn(new Location(world, 10.5, 120.0, 10.5));
        try (MockedStatic<Folia> folia = mockStatic(Folia.class);
             MockedStatic<DataManager> manager = mockStatic(DataManager.class);
             MockedStatic<BlockProperties> blocks = mockStatic(BlockProperties.class)) {
            folia.when(() -> Folia.isOwnedByCurrentRegion(any(Entity.class))).thenReturn(true);
            folia.when(() -> Folia.isOwnedByCurrentRegion(any(Location.class))).thenReturn(true);
            manager.when(() -> DataManager.getPlayerData(player)).thenReturn(pData);
            vehicleChecks().onVehicleLeaveMiss(player, data, config, pData);
        }
        assertClosedAndCooldownKept("vehicle exit (missed event)");
    }

    /** REVIEW-CODE-2 finding 2: the move monitor resets a riding player's positions. */
    @Test
    public void monitorVehicleAndWorldChangeBranchesEndTheWindow() throws Exception {
        openWindow();
        final Entity vehicle = mock(Entity.class);
        when(vehicle.getLocation(any(Location.class))).thenAnswer(at(world, 10.5, 100.0, 10.5));
        when(player.isInsideVehicle()).thenReturn(true);
        when(player.getVehicle()).thenReturn(vehicle);
        final Location from = new Location(world, 10.5, 100.0, 10.5);
        final Location to = new Location(world, 10.6, 100.0, 10.5);
        when(player.getLocation()).thenReturn(to.clone());
        final Class<?>[] types = {Player.class, Location.class, Location.class, long.class, long.class,
                CombinedData.class, MovingData.class, MovingConfig.class, IPlayerData.class};
        try (MockedStatic<Combined> combined = mockStatic(Combined.class)) {
            call(listener(), "onMoveMonitorNotCancelled", types, player, from, to, 1000L, 1L, new CombinedData(), data, config, pData);
        }
        assertClosedAndCooldownKept("monitor, in a vehicle");

        // Different world: a teleport event should follow, but the window ends here already.
        openWindow();
        when(player.isInsideVehicle()).thenReturn(false);
        final World other = mock(World.class);
        when(other.getName()).thenReturn("other");
        final Location away = new Location(other, 10.5, 100.0, 10.5);
        when(player.getLocation()).thenReturn(from.clone());
        try (MockedStatic<Combined> combined = mockStatic(Combined.class)) {
            call(listener(), "onMoveMonitorNotCancelled", types, player, from, away, 1000L, 1L, new CombinedData(), data, config, pData);
        }
        assertClosedAndCooldownKept("monitor, world change");
    }

    /** REVIEW-CODE-2 finding 2: the shared player-position reset is the invariant behind every caller. */
    @Test
    public void everyPlayerPositionResetEndsTheWindow() {
        openWindow();
        data.resetPlayerPositions((PlayerLocation) null);
        assertClosedAndCooldownKept("resetPlayerPositions");
    }

    @Test
    public void clearFlyDataStillEndsTheWindow() {
        openWindow();
        data.clearFlyData();
        assertClosedAndCooldownKept("clearFlyData");
    }

    /** The checks pass the flying modifiers, rocket lifetime and pitch to the window. */
    @Test
    public void wiringPassesTheModifiedCapAndPitch() throws IOException {
        final String moving = "NCPCore/src/main/java/fr/neatmonster/nocheatplus/checks/moving/";
        File dir = new File("").getAbsoluteFile();
        while (dir != null && !new File(dir, moving + "ElytraResidual.java").isFile()) {
            dir = dir.getParentFile();
        }
        assertTrue("sources found", dir != null);
        final String sf = new String(Files.readAllBytes(new File(dir, moving + "player/SurvivalFly.java").toPath()), StandardCharsets.UTF_8);
        final String listener = new String(Files.readAllBytes(new File(dir, moving + "MovingListener.java").toPath()), StandardCharsets.UTF_8);
        final int open = listener.indexOf("ElytraResidual.begin(");
        final int cap = listener.lastIndexOf("ElytraResidual.elytraCap(", open);
        assertTrue("the window opens with the modified cap", cap > 0 && open - cap < 800);
        final String capCall = listener.substring(cap, open);
        assertTrue(capCall.contains("getFasterMovementAmplifier(player)"));
        assertTrue(capCall.contains("getSpeedAttributeMultiplier(player)"));
        assertTrue(capCall.contains("data.walkSpeed"));
        // The start ceiling only with an unexpired firework boost.
        assertTrue(listener.contains("final int rocketTicks = Math.max(0, data.fireworksBoostTickExpire - tick);"));
        assertTrue(listener.contains("elytraCap, rocketTicks)"));
        // The move's pitch is the look vanilla used for that tick.
        assertTrue(sf.contains("ElytraResidual.allow(data.elytraResidual, hDistance, yDistance, to.getPitch(),"));
        // CreativeFly's own cap uses the same three modifiers.
        final String cf = new String(Files.readAllBytes(new File(dir, moving + "player/CreativeFly.java").toPath()), StandardCharsets.UTF_8);
        assertTrue(cf.contains("fSpeed = 1.0 + 0.2 * (speedModifier + 1.0)"));
        assertTrue(cf.contains("fSpeed *= data.walkSpeed / Magic.DEFAULT_WALKSPEED"));
    }
}
