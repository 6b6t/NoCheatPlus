package fr.neatmonster.nocheatplus;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

import fr.neatmonster.nocheatplus.checks.CheckType;
import fr.neatmonster.nocheatplus.checks.ViolationData;
import fr.neatmonster.nocheatplus.checks.moving.MovingConfig;
import fr.neatmonster.nocheatplus.checks.moving.MovingData;
import fr.neatmonster.nocheatplus.checks.moving.location.tracking.LocationTrace.TraceEntryPool;
import fr.neatmonster.nocheatplus.checks.moving.model.PlayerMoveData;
import fr.neatmonster.nocheatplus.checks.moving.player.SurvivalFly;
import fr.neatmonster.nocheatplus.checks.workaround.WRPT;
import fr.neatmonster.nocheatplus.players.DataManager;
import fr.neatmonster.nocheatplus.players.IPlayerData;
import fr.neatmonster.nocheatplus.utilities.location.PlayerLocation;

public class TestFallingSetBack {
    private SurvivalFly check;
    private MovingData data;
    private MovingConfig config;
    private World world;
    private Player player;
    private ViolationData actions;
    private ScopedValue<Object> context;
    private Constructor<?> contextConstructor;
    private Method handleViolation;

    @Before
    @SuppressWarnings("unchecked")
    public void setup() throws Exception {
        final PluginTests.UnitTestNoCheatPlusAPI api = new PluginTests.UnitTestNoCheatPlusAPI() {
            @Override public fr.neatmonster.nocheatplus.compat.blocks.changetracker.BlockChangeTracker getBlockChangeTracker() {
                return null;
            }
        };
        NCPAPIProvider.setNoCheatPlusAPI(api);
        api.registerGenericInstance(WRPT.class, new WRPT());
        api.registerGenericInstance(TraceEntryPool.class, new TraceEntryPool(100));
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getVersion).thenReturn("Folia (MC: 26.2)");
            config = new MovingConfig(api.getWorldDataManager().getWorldData("falling-test"));
        }
        data = new MovingData(config, mock(IPlayerData.class));
        world = mock(World.class);
        when(world.getName()).thenReturn("world");
        player = mock(Player.class);
        try (MockedStatic<DataManager> ignored = mockStatic(DataManager.class)) {
            check = spy(new SurvivalFly());
        }
        actions = mock(ViolationData.class);
        when(actions.willCancel()).thenReturn(true);
        doReturn(actions).when(check).executeActions(any(ViolationData.class));
        data.setSetBack(new Location(world, 0, 100, 0));
        final var field = SurvivalFly.class.getDeclaredField("CONTEXT");
        field.setAccessible(true);
        context = (ScopedValue<Object>) field.get(null);
        contextConstructor = Class.forName(SurvivalFly.class.getName() + "$CheckContext").getDeclaredConstructor();
        contextConstructor.setAccessible(true);
        handleViolation = SurvivalFly.class.getDeclaredMethod("handleViolation", long.class, double.class,
                Player.class, PlayerLocation.class, PlayerLocation.class, MovingData.class, MovingConfig.class);
        handleViolation.setAccessible(true);
    }

    @Test
    public void flightAfterFallingCorrectsToLastAcceptedPositionAndKeepsFallDamage() throws Exception {
        final PlayerLocation from = position(5, 70, 8);
        final PlayerLocation to = position(9, 72, 12);
        acceptedFall(from);
        data.noFallMaxY = 100;
        data.noFallFallDistance = 30;
        final Location correction = reject(from, to);
        assertEquals(new Location(world, 5, 70, 8, 90, 20), correction);
        assertEquals(70, data.getSetBackY(), 0);
        assertEquals(100, data.noFallMaxY, 0);
        assertEquals(30, data.noFallFallDistance, 0);
        assertTrue(data.survivalFlyVL > 0);

        data.prepareSetBack(correction);
        assertEquals(correction, reject(from, to));
    }

    @Test
    public void rejectedClimbsCannotRaiseTheCorrectionPosition() throws Exception {
        final PlayerLocation from = position(5, 70, 8);
        acceptedFall(from);
        reject(from, position(5, 75, 8));
        data.playerMoves.invalidate();
        assertEquals(70, reject(position(5, 75, 8), position(5, 80, 8)).getY(), 0);
    }

    @Test
    public void ascendingMovesKeepTheJumpAnchor() throws Exception {
        final PlayerLocation from = position(5, 70, 8);
        acceptedFall(from);
        data.playerMoves.getFirstPastMove().yDistance = 0.42;
        assertEquals(100, reject(from, position(5, 71, 8)).getY(), 0);
    }

    @Test
    public void uncheckedOrDifferentWorldPositionsCannotBecomeAnchors() throws Exception {
        final PlayerLocation from = position(5, 70, 8);
        acceptedFall(from);
        assertEquals(100, reject(position(6, 70, 8), position(6, 71, 8)).getY(), 0);
        when(from.getWorldName()).thenReturn("other_world");
        assertEquals(100, reject(from, position(5, 71, 8)).getY(), 0);
        data.playerMoves.invalidate();
        assertEquals(100, reject(from, position(5, 71, 8)).getY(), 0);
    }

    @Test
    public void nonCancellingActionsKeepTheAnchor() throws Exception {
        final PlayerLocation from = position(5, 70, 8);
        acceptedFall(from);
        when(actions.willCancel()).thenReturn(false);
        assertNull(reject(from, position(5, 71, 8)));
        assertEquals(100, data.getSetBackY(), 0);
    }

    private PlayerLocation position(final double x, final double y, final double z) {
        final PlayerLocation location = mock(PlayerLocation.class);
        when(location.getWorld()).thenReturn(world);
        when(location.getWorldName()).thenReturn("world");
        when(location.getX()).thenReturn(x);
        when(location.getY()).thenReturn(y);
        when(location.getZ()).thenReturn(z);
        when(location.getYaw()).thenReturn(90f);
        when(location.getPitch()).thenReturn(20f);
        when(location.getLocation()).thenReturn(new Location(world, x, y, z, 90, 20));
        return location;
    }

    private void acceptedFall(final PlayerLocation to) {
        final PlayerMoveData last = data.playerMoves.getFirstPastMove();
        last.set(position(to.getX(), to.getY() + 1, to.getZ()), to);
        last.flyCheck = CheckType.MOVING_SURVIVALFLY;
    }

    private Location reject(final PlayerLocation from, final PlayerLocation to) throws Exception {
        return ScopedValue.where(context, contextConstructor.newInstance()).call(() ->
                (Location) handleViolation.invoke(check, 1000L, 20.0, player, from, to, data, config));
    }
}
