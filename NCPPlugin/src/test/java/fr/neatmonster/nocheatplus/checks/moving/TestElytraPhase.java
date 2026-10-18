package fr.neatmonster.nocheatplus.checks.moving;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import fr.neatmonster.nocheatplus.actions.ActionList;
import fr.neatmonster.nocheatplus.checks.ViolationData;
import fr.neatmonster.nocheatplus.checks.moving.player.Passable;
import fr.neatmonster.nocheatplus.players.IPlayerData;
import fr.neatmonster.nocheatplus.logging.StaticLog;
import fr.neatmonster.nocheatplus.test.BlockTests;
import fr.neatmonster.nocheatplus.utilities.collision.PassableAxisTracing;
import fr.neatmonster.nocheatplus.utilities.location.PlayerLocation;
import fr.neatmonster.nocheatplus.utilities.map.FakeBlockCache;

public class TestElytraPhase {
    private final FakeBlockCache blocks = new FakeBlockCache();
    private final Player player = mock(Player.class);
    private final World world = mock(World.class);
    private final MovingData data = mock(MovingData.class);
    private final MovingConfig config = mock(MovingConfig.class);
    private final IPlayerData pData = mock(IPlayerData.class);
    private Passable check;

    @BeforeClass
    public static void blockProperties() {
        StaticLog.setUseLogManager(false);
        BlockTests.initBlockProperties();
        StaticLog.setUseLogManager(true);
    }

    private static void field(final Object object, final String name, final Object value) throws Exception {
        final Field field = object.getClass().getField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    @Before
    public void setup() throws Exception {
        when(world.getName()).thenReturn("phase-test");
        when(player.isGliding()).thenReturn(true);
        check = mock(Passable.class, CALLS_REAL_METHODS);
        final Field tracing = Passable.class.getDeclaredField("rayTracing");
        tracing.setAccessible(true);
        final PassableAxisTracing ray = new PassableAxisTracing();
        ray.setMaxSteps(60);
        tracing.set(check, ray);
        field(config, "passableHorizontalMargins", 0.999999);
        field(config, "passableVerticalMargins", 0.999999);
        field(config, "passableActions", new ActionList(null));
        doAnswer(call -> {
            final ViolationData violation = call.getArgument(0);
            violation.forceCancel();
            return violation;
        }).when(check).executeActions(any(ViolationData.class));
        blocks.set(1, 64, 0, Material.STONE, new double[] {0, 0, 0, 1, 1, 1});
    }

    private PlayerLocation location(final double x, final double y, final double z) {
        final PlayerLocation result = mock(PlayerLocation.class);
        when(result.getX()).thenReturn(x);
        when(result.getY()).thenReturn(y);
        when(result.getZ()).thenReturn(z);
        when(result.getWidth()).thenReturn(0.6);
        when(result.getBoxMarginVertical()).thenReturn(0.6);
        when(result.getBlockCache()).thenReturn(blocks);
        when(result.getWorld()).thenReturn(world);
        when(result.getLocation()).thenReturn(new Location(world, x, y, z));
        return result;
    }

    private Location move(final double x0, final double y0, final double z0,
            final double x1, final double y1, final double z1) {
        return check.check(player, location(x0, y0, z0), location(x1, y1, z1), data, config, pData, 100, false);
    }

    @Test
    public void freshQuarterBlockEntryIsAlreadyBlocked() {
        assertNotNull(move(0.69999999, 64.2, 0.5, 0.93999999, 64.2, 0.5));
    }

    @Test
    public void tinyInitialOverlapMustNotAuthorizeADeeperPhaseStep() {
        assertNotNull(move(0.71, 64.2, 0.5, 0.95, 64.2, 0.5));
    }

    @Test
    public void eachWallFaceFloorAndCeilingRejectDeeperGliding() {
        final double[][] dives = {
                {0.71, 64.2, 0.5, 0.95, 64.2, 0.5},
                {2.29, 64.2, 0.5, 2.05, 64.2, 0.5},
                {1.5, 64.2, -0.29, 1.5, 64.2, -0.05},
                {1.5, 64.2, 1.29, 1.5, 64.2, 1.05},
                {1.5, 64.99, 0.5, 1.5, 64.75, 0.5},
                {1.5, 63.41, 0.5, 1.5, 63.65, 0.5}
        };
        for (final double[] dive : dives) {
            assertNotNull(java.util.Arrays.toString(dive), move(dive[0], dive[1], dive[2], dive[3], dive[4], dive[5]));
        }
    }

    @Test
    public void aLongStepCannotExitThroughTheFarSideOfAnInitiallyOverlappedWall() {
        assertNotNull(move(0.71, 64.2, 0.5, 2.71, 64.2, 0.5));
        assertNotNull(move(1.5, 64.99, 0.5, 1.5, 62.99, 0.5));
    }

    @Test
    public void exactFaceAndCornerContactCannotBecomeAnEntry() throws Exception {
        field(config, "passableHorizontalMargins", 1.0);
        field(config, "passableVerticalMargins", 1.0);
        assertNotNull(move(0.7, 64.2, 0.5, 0.94, 64.2, 0.5));
        assertNotNull(move(0.7, 64.2, -0.3, 0.9, 64.2, -0.1));
        assertNull(move(0.7, 64.2, 0.5, 0.7, 64.3, 0.6));
    }

    @Test
    public void openAirLandingAndFlyingOverASurfaceKeepNormalClearance() {
        assertNull(move(-2, 64.2, 0.5, 0.69, 64.2, 0.5));
        assertNull(move(1.5, 66, 0.5, 1.5, 65, 0.5));
        assertNull(move(1.4, 65, 0.5, 1.6, 65, 0.5));
        assertNull(move(0.69, 64.2, 0.5, 0.69, 64.3, 0.6));
    }

    @Test
    public void diagonalEscapeAndAnAlreadyEmbeddedPlayerCanStillGetOut() {
        assertNull(move(0.71, 64.99, 0.5, 0.72, 65.2, 0.5));
        assertNull(move(1.5, 64.2, 0.5, 2.4, 64.2, 0.5));
    }

    @Test
    public void walkingRetainsItsExistingCollisionRecovery() {
        when(player.isGliding()).thenReturn(false);
        assertNull(move(0.71, 64.2, 0.5, 0.95, 64.2, 0.5));
        when(player.isGliding()).thenReturn(true);
        assertNotNull(move(0.71, 64.2, 0.5, 0.95, 64.2, 0.5));
        when(player.isGliding()).thenReturn(false);
        assertNull(move(0.71, 64.2, 0.5, 0.95, 64.2, 0.5));
    }

    @Test
    public void escapingOrSlidingAnInitialOverlapMustRemainPossible() {
        assertNull(move(0.71, 64.2, 0.5, 0.65, 64.2, 0.5));
        assertNull(move(0.71, 64.2, 0.5, 0.71, 64.3, 0.6));
    }
}
