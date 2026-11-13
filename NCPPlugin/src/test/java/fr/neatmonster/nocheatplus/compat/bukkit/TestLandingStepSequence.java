package fr.neatmonster.nocheatplus.compat.bukkit;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

import fr.neatmonster.nocheatplus.checks.combined.Improbable;
import fr.neatmonster.nocheatplus.PluginTests;
import fr.neatmonster.nocheatplus.NCPAPIProvider;
import fr.neatmonster.nocheatplus.checks.ViolationData;
import fr.neatmonster.nocheatplus.checks.CheckType;
import fr.neatmonster.nocheatplus.checks.moving.MovingConfig;
import fr.neatmonster.nocheatplus.checks.moving.MovingData;
import fr.neatmonster.nocheatplus.checks.moving.location.tracking.LocationTrace.TraceEntryPool;
import fr.neatmonster.nocheatplus.checks.moving.magic.LostGround;
import fr.neatmonster.nocheatplus.checks.moving.model.LiftOffEnvelope;
import fr.neatmonster.nocheatplus.checks.moving.model.PlayerMoveData;
import fr.neatmonster.nocheatplus.checks.moving.player.SurvivalFly;
import fr.neatmonster.nocheatplus.checks.workaround.WRPT;
import fr.neatmonster.nocheatplus.compat.Folia;
import fr.neatmonster.nocheatplus.compat.MCAccess;
import fr.neatmonster.nocheatplus.compat.bukkit.BlockCacheBukkitModern;
import fr.neatmonster.nocheatplus.compat.bukkit.NativeLandingAccess;
import fr.neatmonster.nocheatplus.compat.versions.ServerVersion;
import fr.neatmonster.nocheatplus.components.modifier.DummyAttributeAccess;
import fr.neatmonster.nocheatplus.components.modifier.IAttributeAccess;
import fr.neatmonster.nocheatplus.logging.StaticLog;
import fr.neatmonster.nocheatplus.players.DataManager;
import fr.neatmonster.nocheatplus.players.IPlayerData;
import fr.neatmonster.nocheatplus.test.BlockTests;
import fr.neatmonster.nocheatplus.utilities.location.PlayerLocation;
import fr.neatmonster.nocheatplus.utilities.map.BlockFlags;
import fr.neatmonster.nocheatplus.utilities.map.BlockProperties;
import fr.neatmonster.nocheatplus.utilities.map.FakeBlockCache;

/** Real SurvivalFly and block/entity support predicates, with synthetic environment and packet history.
 * Run unchanged on base with -Dlandingstep.base=true. This is not an in-world client replay. */
public class TestLandingStepSequence {
    private final boolean base = Boolean.getBoolean("landingstep.base");
    private SurvivalFly check;
    private MovingData data;
    private MovingConfig config;
    private World world;
    private Player player;
    private IPlayerData playerData;
    private MCAccess access;
    private FakeBlockCache blocks;
    private BlockCacheBukkitModern entities;
    private final List<Entity> nearby = new ArrayList<>();
    private MockedStatic<Folia> folia;
    private MockedStatic<NativeLandingAccess> nativeAccess;
    private String oldVersion;

    @Before
    public void setup() throws Exception {
        StaticLog.setUseLogManager(false);
        PluginTests.setUnitTestNoCheatPlusAPI(true);
        BlockTests.initBlockProperties();
        StaticLog.setUseLogManager(true);
        // Match production carpet flags, which the minimal block test bootstrap does not install.
        BlockFlags.setBlockFlags(Material.WHITE_CARPET, BlockFlags.F_GROUND | BlockFlags.F_CARPET);
        final PluginTests.UnitTestNoCheatPlusAPI api = new PluginTests.UnitTestNoCheatPlusAPI() {
            @Override public fr.neatmonster.nocheatplus.compat.blocks.changetracker.BlockChangeTracker getBlockChangeTracker() { return null; }
        };
        final var install = NCPAPIProvider.class.getDeclaredMethod("setNoCheatPlusAPI",
                fr.neatmonster.nocheatplus.components.NoCheatPlusAPI.class);
        install.setAccessible(true);
        install.invoke(null, api);
        api.registerGenericInstance(WRPT.class, new WRPT());
        api.registerGenericInstance(TraceEntryPool.class, new TraceEntryPool(100));
        api.registerGenericInstance(IAttributeAccess.class, new DummyAttributeAccess());
        access = mock(MCAccess.class);
        when(access.getJumpAmplifier(any(Player.class))).thenReturn(Double.NEGATIVE_INFINITY);
        when(access.getFasterMovementAmplifier(any(Player.class))).thenReturn(Double.NEGATIVE_INFINITY);
        api.registerGenericInstance(MCAccess.class, access);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getVersion).thenReturn("Folia (MC: 26.2)");
            config = new MovingConfig(api.getWorldDataManager().getWorldData("landingstep-test"));
        }
        final var height = MovingConfig.class.getField("sfStepHeight");
        height.setAccessible(true);
        height.setDouble(config, 1.1);
        playerData = mock(IPlayerData.class);
        world = mock(World.class);
        when(world.getName()).thenReturn("world");
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getWidth()).thenReturn(0.6);
        when(player.getWorld()).thenReturn(world);
        when(world.getNearbyEntities(any(Location.class), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(Collections.emptyList());
        when(player.getInventory()).thenReturn(mock(PlayerInventory.class));
        when(player.getVelocity()).thenReturn(new Vector());
        when(player.getName()).thenReturn("synthetic-fixture");
        when(player.getNearbyEntities(anyDouble(), anyDouble(), anyDouble())).thenAnswer(inv -> nearby);
        oldVersion = ServerVersion.getMinecraftVersion();
        ServerVersion.setMinecraftVersion("26.2");
        folia = mockStatic(Folia.class);
        folia.when(() -> Folia.isOwnedByCurrentRegion(any(Entity.class))).thenReturn(true);
        nativeAccess = mockStatic(NativeLandingAccess.class);
        nativeAccess.when(() -> NativeLandingAccess.canCollide(any(Player.class), any(Entity.class))).thenReturn(true);
        blocks = new FakeBlockCache() { { minBlockY = -64; maxBlockY = 319; } };
        entities = new BlockCacheBukkitModern(Collections.emptyMap());
        try (MockedStatic<DataManager> ignored = mockStatic(DataManager.class)) { check = spy(new SurvivalFly()); }
        final ViolationData actions = mock(ViolationData.class);
        when(actions.willCancel()).thenReturn(true);
        doReturn(actions).when(check).executeActions(any(ViolationData.class));
        resetData(10.0);
    }

    @After
    public void cleanup() {
        nativeAccess.close();
        folia.close();
        ServerVersion.setMinecraftVersion(oldVersion);
        blocks.cleanup();
        entities.cleanup();
        PluginTests.setUnitTestNoCheatPlusAPI(true);
    }

    @Test public void recordedBoatLandingWithSmallHorizontalMove() { reportLanding(0.04); }
    @Test public void recordedBoatLandingWithLargerHorizontalMove() { reportLanding(0.20); }

    @Test
    public void recordedBedrockShortBedBounceDoesNotCountAsLowJump() {
        when(playerData.isBedrockPlayer()).thenReturn(true);
        final long flags = BlockFlags.getBlockFlags(Material.BLUE_BED);
        BlockFlags.setBlockFlags(Material.BLUE_BED, BlockFlags.SOLID_GROUND | BlockFlags.F_BED | BlockFlags.F_BOUNCE25);
        try (MockedStatic<Improbable> ignored = mockStatic(Improbable.class)) {
            for (int x = -2; x < 8; x++) blocks.set(x, -60, 0, Material.BLUE_BED,
                    new double[] {0, 0, 0, 1, 0.5625, 1});
            resetData(-59.4375);
            PlayerLocation from = position(0.8, -59.25053, false);
            acceptedHistory(from, -0.12087, 0.1);
            // Bedrock trace, 27 Oct 16:41:40: clipped landing, immediate bounce, apex, descent.
            for (final double y : new double[] {-59.4375, -59.30733, -59.25816, -59.28838}) {
                final PlayerLocation to = position(from.getX() + 0.1, y, y == -59.4375);
                assertNull("Recorded bed bounce at " + y, move(from, to));
                data.playerMoves.finishCurrentMove();
                from = to;
            }
            assertFalse(data.sfLowJump);
        } finally { BlockFlags.setBlockFlags(Material.BLUE_BED, flags); }
    }

    @Test
    public void recordedBedrockChestEdgeCorrectionDoesNotCountAsJump() {
        when(playerData.isBedrockPlayer()).thenReturn(true);
        blocks = new FakeBlockCache() {
            { minBlockY = -64; maxBlockY = 319; }
            @Override public double[] getBedrockBounds(final int x, final int y, final int z) {
                return x == 1 && y == -60 && z == 0 ? new double[] {0.025, 0, 0.025, 0.975, 0.95, 1} : null;
            }
        };
        final long flags = BlockFlags.getBlockFlags(Material.CHEST);
        BlockFlags.setBlockFlags(Material.CHEST, BlockFlags.SOLID_GROUND);
        blocks.set(1, -60, 0, Material.CHEST, new double[] {0.0625, 0, 0.0625, 0.9375, 0.875, 1});
        try (MockedStatic<Improbable> ignored = mockStatic(Improbable.class)) {
            resetData(-59.125);
            PlayerLocation from = position(1.1876, -58.92871, false);
            acceptedHistory(from, -0.374, 0.1);
            // Bedrock trace, 27 Oct 16:43:17: native landing, delayed client-height correction past the edge, fall.
            final double[][] moves = {{0.89423, -59.125}, {0.5025, -59.05}, {0.16388, -59.1284}};
            for (final double[] point : moves) {
                final PlayerLocation to = position(point[0], point[1], point[1] == -59.125);
                assertNull("Recorded chest edge at " + point[1], move(from, to));
                data.playerMoves.finishCurrentMove();
                from = to;
            }
            assertFalse(data.sfLowJump);
        } finally { BlockFlags.setBlockFlags(Material.CHEST, flags); }
    }

    private void reportLanding(double horizontal) {
        platform(Material.GLASS, 10.0, false);
        PlayerLocation at = position(-0.3, 10.0, true);
        double dy = (double) 0.42F;
        // Vanilla jump from a full block, through the apex and into the recorded fall.
        for (int tick = 0; tick < 11; tick++) {
            double endY = tick == 10 ? 10.1215 : at.getY() + dy;
            PlayerLocation next = position(at.getX() + 0.1, endY, false);
            assertNull("vanilla jump tick " + tick, move(at, next));
            data.playerMoves.finishCurrentMove();
            at = next;
            dy = (dy - 0.08) * 0.98;
        }
        assertTrue(data.playerMoves.getFirstPastMove().yDistance < -0.37);
        assertFalse(data.playerMoves.getFirstPastMove().to.onGround);
        boat(at.getX() + 0.300001, 10.5625, false);
        final PlayerLocation top = position(at.getX() + horizontal, 10.5625, true);
        assertEquals(0.441, top.getY() - at.getY(), 1e-9);
        final Location correction = move(at, top);
        assertEquals("recorded landing accepted only by fix", base, correction != null);
        assertEquals(!base, data.playerMoves.getCurrentMove().touchedGroundWorkaround);
        if (base) assertEquals(10.0, correction.getY(), 0);
        else assertEquals(top.getY(), data.getSetBackY(), 0);
    }

    @Test
    public void boatAndRaftLandingsFromGlassCarpetAndSlabWithSpeedTwo() {
        int cases = 0;
        for (Material material : new Material[] {Material.GLASS, Material.WHITE_CARPET, Material.STONE_SLAB}) {
            for (boolean water : new boolean[] {false, true}) {
                for (boolean raft : new boolean[] {false, true}) {
                    for (int mode = 0; mode < 3; mode++) {
                        for (boolean speedTwo : new boolean[] {false, true}) {
                            blocks.cleanup(); nearby.clear();
                            final double support = material == Material.WHITE_CARPET ? 10.0625
                                    : material == Material.STONE_SLAB ? 9.5 : 10.0;
                            platform(material, support, water);
                            resetData(support);
                            // NCP's MCAccess uses effect level (II=2), rather than zero-based Bukkit amplifier.
                            when(access.getFasterMovementAmplifier(player)).thenReturn(speedTwo ? 2.0 : Double.NEGATIVE_INFINITY);
                            data.multSprinting = 1.3;
                            data.timeSprinting = mode == 0 ? 0 : 100000L;
                            final double horizontal = landingHorizontal(mode, speedTwo);
                            final PlayerLocation from = position(0.8, support + 0.1215, false);
                            acceptedHistory(from, -0.374, mode == 2 ? (horizontal - 0.026) / 0.91 : horizontal);
                            if (mode == 2) data.bunnyhopDelay = 2; // The airborne bunnyfly phase extends until contact.
                            boat(1.100001, support + 0.5625, raft);
                            final Location correction = move(from, position(0.8 + horizontal, support + 0.5625, true));
                            assertEquals("material=" + material + " water=" + water + " raft=" + raft
                                    + " mode=" + mode + " speedII=" + speedTwo, base, correction != null);
                            cases++;
                        }
                    }
                }
            }
        }
        assertEquals(72, cases);
    }

    private double landingHorizontal(int mode, boolean speedTwo) {
        final double groundSpeed = (mode == 0 ? 0.21585 : 0.28061) * (speedTwo ? 1.4 : 1.0);
        if (mode < 2) return groundSpeed;
        // Ground sprint momentum plus vanilla's 0.2 jump impulse; air drag 0.91 and sprint air input 0.026.
        double horizontal = groundSpeed + 0.2;
        // The takeoff tick still uses the source block's drag (0.6 * 0.91), before airborne drag.
        horizontal = horizontal * 0.546 + 0.026;
        for (int tick = 1; tick < 11; tick++) horizontal = horizontal * 0.91 + 0.026;
        assertTrue(horizontal <= 0.221 * 1.82);
        return horizontal;
    }

    @Test public void unsupportedOneBlockRiseIsRejected() { unsupportedRise(1.0); }
    @Test public void unsupportedOnePointOneBlockRiseIsRejected() { unsupportedRise(1.1); }

    private void unsupportedRise(double rise) {
        // Same airborne falling history and supported endpoint as the unsupported-rise fixtures; no source support.
        final PlayerLocation from = position(0, 10.0, false);
        acceptedHistory(from, -0.0784, 0.1);
        final PlayerLocation to = position(0.1, 10.0 + rise, true);
        final Location correction = move(from, to);
        assertNotNull(correction);
        assertEquals(10.0, correction.getY(), 0);
        assertFalse(data.playerMoves.getCurrentMove().touchedGroundWorkaround);
    }

    @Test
    public void earlyOneBlockJumpStepIsRejected() {
        final PlayerLocation ground = position(0, 10.0, true);
        final PlayerLocation jump = position(0.1, 10.42, false);
        assertNull(move(ground, jump));
        data.playerMoves.finishCurrentMove();
        assertNotNull(move(jump, position(0.2, 11.0, true)));
        assertFalse(data.playerMoves.getCurrentMove().touchedGroundWorkaround);
    }

    @Test
    public void fakeTinyFallsCannotClimbAFullBlockStaircase() {
        blocks.fill(-2, 9, -2, 2, 9, 2, Material.GLASS);
        for (int cycle = 0; cycle < 10; cycle++) {
            // A client claims an unsupported one-block top after a tiny fall. Every correction restores the anchor.
            final PlayerLocation from = position(0.5, data.getSetBackY() + 0.02, false);
            acceptedHistory(from, -0.001, 0.1);
            final Location correction = move(from, position(0.6, from.getY() + 1.0, true));
            assertNotNull(correction);
            assertEquals(10.0, correction.getY(), 0);
            final PlayerLocation anchor = position(correction.getX(), correction.getY(), true);
            data.prepareSetBack(correction);
            data.resetPlayerPositions(anchor);
            data.onSetBack(anchor);
        }
        assertEquals(10.0, data.getSetBackY(), 0);
    }

    @Test
    public void blockHalfStepChainFallsBackToBaseGroundSteps() {
        for (int cycle = 0; cycle < 8; cycle++) {
            final double support = 10.0 + cycle * 0.5;
            resetData(support);
            blocks.cleanup();
            stepBlock(0, support);
            stepBlock(1, support + 0.5);
            final PlayerLocation airborne = position(0.65, support + 0.001, false);
            acceptedHistory(airborne, -0.001, 0.1);
            final Location landing = move(airborne, position(0.85, support + 0.5, true));
            assertNotNull(landing);
            // Base accepts the same real step without the fake fall, at least as quickly and with a larger cap.
            resetData(support);
            assertNull(move(position(0.65, support, true), position(0.85, support + 0.5, true)));
            assertEquals(support + 0.5, data.getSetBackY(), 0);
        }
    }

    @Test
    public void baseGroundToGroundConfiguredOnePointOneStepIsUnchanged() {
        assertNull(move(position(0, 10, true), position(0.1, 11.1, true)));
        assertEquals(11.1, data.getSetBackY(), 0);
    }

    @Test
    public void jumpAfterRealLandingHasTheSameCeilingAsBase() {
        platform(Material.GLASS, 10, false);
        PlayerLocation from = position(0.8, 10.1215, false);
        acceptedHistory(from, -0.374, 0.2);
        boat(1.100001, 10.5625, false);
        PlayerLocation top = position(1.0, 10.5625, true);
        assertEquals(base, move(from, top) != null);
        if (base) resetData(top.getY());
        else data.playerMoves.finishCurrentMove();
        PlayerLocation jump = position(1.1, top.getY() + (double) 0.42F, false);
        assertNull(move(top, jump));
        data.playerMoves.finishCurrentMove();
        Location correction = move(jump, position(1.2, top.getY() + 1.5, false));
        assertNotNull(correction);
        assertEquals(top.getY(), correction.getY(), 0);
    }

    @Test
    public void fenceWallAndHighAirborneRisesNeedSupportWithinVanillaStepHeight() {
        platform(Material.GLASS, 10, false);
        for (double[] heights : new double[][] {{11.1, 11.5}, {11.1215, 11.5625}}) {
            resetData(10);
            PlayerLocation from = position(0.8, heights[0], false);
            acceptedHistory(from, -0.0784, 0.1);
            assertNotNull(move(from, position(0.9, heights[1], true)));
            assertFalse(data.playerMoves.getCurrentMove().touchedGroundWorkaround);
        }
    }

    @Test
    public void boatsCannotSupplySourceBlockSupportAndNearbyBoatsCannotSupplyEndpointContact() {
        boat(1.2, 10, false);
        PlayerLocation from = position(0.8, 10.1215, false);
        acceptedHistory(from, -0.374, 0.2);
        assertFalse(lostGround(from, position(1.0, 10.5625, true)));
        platform(Material.GLASS, 10, false);
        nearby.clear();
        // Ordinary ground is intentionally true, but the real footprint misses this boat by 0.01.
        boat(1.31, 10.5625, false);
        assertFalse(lostGround(from, position(1.0, 10.5625, true)));
    }

    private boolean lostGround(PlayerLocation from, PlayerLocation to) {
        final Location dest = to.getLocation();
        when(player.getLocation()).thenReturn(dest);
        return LostGround.lostGround(player, from, to, to.getX() - from.getX(), to.getY() - from.getY(),
                true, data.playerMoves.getFirstPastMove(), data, config, null, new ArrayList<>());
    }

    private void resetData(double anchor) {
        data = new MovingData(config, playerData);
        data.walkSpeed = 0.2f;
        data.liftOffEnvelope = LiftOffEnvelope.NORMAL;
        data.setSetBack(new Location(world, 0, anchor, 0));
    }

    private void platform(Material material, double support, boolean water) {
        blocks.fill(-20, 9, -2, 0, 9, 2, material == Material.GLASS ? material : Material.GLASS);
        if (material == Material.WHITE_CARPET) {
            for (int x = -20; x <= 0; x++) for (int z = -2; z <= 2; z++)
                blocks.set(x, 10, z, material, new double[] {0, 0, 0, 1, 0.0625, 1});
        } else if (material == Material.STONE_SLAB) {
            for (int x = -20; x <= 0; x++) for (int z = -2; z <= 2; z++)
                blocks.set(x, 9, z, material, new double[] {0, 0, 0, 1, 0.5, 1});
        }
        if (water) blocks.fill(1, 9, -2, 20, 9, 2, Material.WATER);
        else blocks.fill(1, 9, -2, 20, 9, 2, Material.GLASS);
    }

    private void stepBlock(int x, double top) {
        int y = (int) Math.floor(top);
        if (top == y) blocks.set(x, y - 1, 0, Material.GLASS);
        else blocks.set(x, y, 0, Material.STONE_SLAB, new double[] {0, 0, 0, 1, 0.5, 1});
    }

    private void boat(double edge, double top, boolean raft) {
        Entity entity = mock(Entity.class);
        EntityType type = raft ? mock(EntityType.class) : EntityType.BOAT;
        if (raft) when(type.name()).thenReturn("BAMBOO_RAFT");
        when(entity.getType()).thenReturn(type);
        when(entity.getUniqueId()).thenReturn(UUID.randomUUID());
        when(entity.isValid()).thenReturn(true);
        when(entity.getBoundingBox()).thenReturn(new BoundingBox(edge, top - 0.5625, -0.1875, edge + 1.375, top, 1.1875));
        nearby.add(entity);
    }

    private Location move(PlayerLocation from, PlayerLocation to) {
        final Location dest = to.getLocation();
        when(player.getLocation()).thenReturn(dest);
        data.increasePlayerMoveCount();
        final PlayerMoveData current = data.playerMoves.getCurrentMove();
        current.set(from, to);
        current.setExtraProperties(from, to);
        current.flyCheck = CheckType.MOVING_SURVIVALFLY;
        return check.check(player, from, to, 0, data, config, playerData,
                data.getPlayerMoveCount(), 1000L + data.getPlayerMoveCount() * 50L, false);
    }

    private PlayerLocation position(double x, double y, boolean ground) {
        final PlayerLocation loc = mock(PlayerLocation.class);
        when(loc.getWorld()).thenReturn(world);
        when(loc.getWorldName()).thenReturn("world");
        when(loc.getX()).thenReturn(x);
        when(loc.getY()).thenReturn(y);
        when(loc.getZ()).thenReturn(0.5);
        when(loc.getMinX()).thenReturn(x - 0.3);
        when(loc.getMaxX()).thenReturn(x + 0.3);
        when(loc.getMinZ()).thenReturn(0.2);
        when(loc.getMaxZ()).thenReturn(0.8);
        when(loc.getMinY()).thenReturn(y);
        when(loc.getMaxY()).thenReturn(y + 1.8);
        when(loc.getLocation()).thenReturn(new Location(world, x, y, 0.5));
        when(loc.isOnGround()).thenReturn(ground);
        when(loc.getMCAccess()).thenReturn(access);
        when(loc.getBlockCache()).thenReturn(blocks);
        when(loc.isOnGround(anyDouble(), anyDouble(), anyDouble())).thenAnswer(inv -> {
            double margin = inv.getArgument(0), horizontal = inv.getArgument(1), vertical = inv.getArgument(2);
            return BlockProperties.isOnGround(blocks, x - 0.3 - horizontal, y - margin - vertical, 0.2 - horizontal,
                    x + 0.3 + horizontal, y + vertical, 0.8 + horizontal);
        });
        when(loc.standsOnEntityContact(anyDouble(), anyDouble(), anyDouble())).thenAnswer(inv -> {
            double margin = inv.getArgument(0), horizontal = inv.getArgument(1), vertical = inv.getArgument(2);
            return entities.standsOnEntityContact(player, x - 0.3 - horizontal, y - margin - vertical, 0.2 - horizontal,
                    x + 0.3 + horizontal, y + vertical, 0.8 + horizontal);
        });
        when(loc.stepsOnEntityTop(any(), anyDouble())).thenAnswer(inv -> {
            PlayerLocation source = inv.getArgument(0);
            double epsilon = inv.getArgument(1);
            return entities.stepsOnEntityTop(player, x - 0.3, y - epsilon, 0.2, x + 0.3, y + epsilon, 0.8,
                    source.getMinX(), source.getMinZ(), source.getMaxX(), source.getMaxZ());
        });
        when(loc.standsOnEntityTop(anyDouble())).thenAnswer(inv -> {
            double epsilon = inv.getArgument(0);
            return entities.standsOnEntityTop(player, x - 0.3, y - epsilon, 0.2,
                    x + 0.3, y + epsilon, 0.8);
        });
        return loc;
    }

    private void acceptedHistory(PlayerLocation to, double dy, double horizontal) {
        final PlayerLocation from = position(to.getX() - horizontal, to.getY() - dy, false);
        final PlayerMoveData last = data.playerMoves.getFirstPastMove();
        last.set(from, to);
        last.setExtraProperties(from, to);
        last.flyCheck = CheckType.MOVING_SURVIVALFLY;
        last.hAllowedDistanceBase = 0.2873;
        last.hAllowedDistance = Math.max(0.2873, horizontal);
        data.lastFrictionHorizontal = 0.91;
        data.lastFrictionVertical = 0.98;
        data.sfJumpPhase = 11;
    }
}
