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

import fr.neatmonster.nocheatplus.PluginTests;
import fr.neatmonster.nocheatplus.NCPAPIProvider;
import fr.neatmonster.nocheatplus.checks.ViolationData;
import fr.neatmonster.nocheatplus.checks.CheckType;
import fr.neatmonster.nocheatplus.checks.moving.MovingConfig;
import fr.neatmonster.nocheatplus.config.ConfPaths;
import fr.neatmonster.nocheatplus.worlds.IWorldData;
import fr.neatmonster.nocheatplus.checks.moving.MovingData;
import fr.neatmonster.nocheatplus.checks.moving.location.tracking.LocationTrace.TraceEntryPool;
import fr.neatmonster.nocheatplus.checks.moving.magic.LostGround;
import fr.neatmonster.nocheatplus.checks.moving.magic.InBlockJumpGuard;
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
import fr.neatmonster.nocheatplus.telemetry.Telemetry;
import fr.neatmonster.nocheatplus.players.DataManager;
import fr.neatmonster.nocheatplus.players.IPlayerData;
import fr.neatmonster.nocheatplus.test.BlockTests;
import fr.neatmonster.nocheatplus.utilities.location.PlayerLocation;
import fr.neatmonster.nocheatplus.utilities.map.BlockFlags;
import fr.neatmonster.nocheatplus.utilities.map.BlockProperties;
import fr.neatmonster.nocheatplus.utilities.map.FakeBlockCache;

/** Real SurvivalFly and block/entity support predicates, with synthetic environment and packet history.
 * Run unchanged on base with -Dlandingstep.base=true. This is not an in-world client replay. */
public class TestPhaseGuardSequence {
    private final boolean base = Boolean.getBoolean("landingstep.base");
    private SurvivalFly check;
    private MovingData data;
    private MovingConfig config;
    private IWorldData worldData;
    private World world;
    private Player player;
    private IPlayerData playerData;
    private MCAccess access;
    private FakeBlockCache blocks;
    private BlockCacheBukkitModern entities;
    private final List<Entity> nearby = new ArrayList<>();
    private final List<ViolationData> violations = new ArrayList<>();
    private MockedStatic<Folia> folia;
    private MockedStatic<NativeLandingAccess> nativeAccess;
    private String oldVersion;

    @Before
    public void setup() throws Exception {
        StaticLog.setUseLogManager(false);
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
            worldData = api.getWorldDataManager().getWorldData("landingstep-test");
            config = new MovingConfig(worldData);
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
        Telemetry.install(mock(Telemetry.Sink.class));
        blocks = new FakeBlockCache();
        entities = new BlockCacheBukkitModern(Collections.emptyMap());
        try (MockedStatic<DataManager> ignored = mockStatic(DataManager.class)) { check = spy(new SurvivalFly()); }
        final ViolationData actions = mock(ViolationData.class);
        when(actions.willCancel()).thenReturn(true);
        doAnswer(inv -> { violations.add(inv.getArgument(0)); return actions; })
                .when(check).executeActions(any(ViolationData.class));
        resetData(10.0);
    }

    @After
    public void cleanup() {
        nativeAccess.close();
        folia.close();
        ServerVersion.setMinecraftVersion(oldVersion);
        blocks.cleanup();
        entities.cleanup();
        Telemetry.install(null);
    }

    @Test public void recordedBoatLandingWithSmallHorizontalMove() { reportLanding(0.04); }
    @Test public void recordedBoatLandingWithLargerHorizontalMove() { reportLanding(0.20); }

    @Test
    public void recordedShelfBounceAfterNearGroundMove() {
        shelfFixture();
        for (int cycle = 0; cycle < 5; cycle++) {
            resetData(64.6875);
            final PlayerLocation falling = position(0.5, 64.69775088439046, true);
            acceptedHistory(falling, -0.1552320045166016, 0.0);
            final PlayerLocation top = position(0.5, 64.6875, true);
            assertNull("Actual landing after tolerant ground detection, cycle " + cycle, move(falling, top));
            assertEquals(0.09349927047421, data.sfBedBounceRise, 1.0E-10);
            data.playerMoves.finishCurrentMove();
            PlayerLocation from = top;
            int bounceTick = 0;
            for (final double rise : new double[]{0.09349927047421, 0.0132292853222, -0.0654353016578}) {
                // The client can still report ground on the first near-ground rise. That generic reset must not
                // discard the one validated shelf-bounce exemption before the following descending phase.
                final PlayerLocation to = position(0.5, from.getY() + rise, bounceTick++ == 0);
                assertNull("Recorded short bounce must not trigger lowjump, cycle " + cycle, move(from, to));
                data.playerMoves.finishCurrentMove();
                from = to;
            }
        }
    }

    @Test
    public void tenJumpCyclesOnShelfDoNotTriggerLowJump() {
        shelfFixture();
        final double topY = 64.6875;
        resetData(topY);
        PlayerLocation from = position(0.5, topY, true);
        for (int jump = 0; jump < 10; jump++) {
            double velocity = (double) 0.42F;
            int tick = 0;
            while (true) {
                final double predictedY = from.getY() + velocity;
                final boolean landed = predictedY <= topY;
                final PlayerLocation to = position(0.5, landed ? topY : predictedY, landed);
                assertNull("Vanilla shelf jump " + jump + " tick " + tick, move(from, to));
                data.playerMoves.finishCurrentMove();
                from = to;
                if (landed) break;
                velocity = (velocity - 0.08) * 0.98;
                assertTrue("Jump must return to the shelf", ++tick < 20);
            }
        }
    }

    @Test
    public void recordedShelfBounceAfterFourBlockFallUsesBounceVelocityBeforeRelativeCheck() {
        shelfFixture();
        final double topY = 64.6875;
        resetData(topY + 4.0);
        PlayerLocation falling = position(0.5, topY + 4.0, false);
        acceptedHistory(falling, 0.0, 0.0);
        double velocity = 0.0;
        int fallTicks = 0;
        while (falling.getY() > topY) {
            velocity = (velocity - 0.08) * 0.98;
            final double nextY = Math.max(topY, falling.getY() + velocity);
            final PlayerLocation next = position(0.5, nextY, nextY == topY);
            assertNull("Four-block fall tick " + fallTicks, move(falling, next));
            data.playerMoves.finishCurrentMove();
            falling = next;
            fallTicks++;
        }
        assertTrue("The fixture must cover a 3-5 block fall", fallTicks >= 8 && fallTicks <= 12);
        final double rise = data.sfBedBounceRise;
        assertTrue("A high-speed shelf landing should bounce above an ordinary jump: " + rise, rise > 0.42);

        final PlayerLocation bounced = position(0.5, topY + rise, false);
        assertNull("Register the shelf's bounded fall-bounce velocity before vdistrel", move(falling, bounced));
        assertNotNull("The accepted rise must be accounted for as vertical velocity",
                data.playerMoves.getCurrentMove().verVelUsed);
    }

    @Test
    public void forgedShelfBounceCannotBecomeAnOversizedStep() {
        shelfFixture();
        final double topY = 64.6875;
        resetData(topY);
        data.sfBedBounceRise = 0.50;
        data.sfBedBounceMove = data.getPlayerMoveCount();
        final PlayerLocation top = position(0.5, topY, true);
        final PlayerLocation forged = position(0.5, topY + 1.20, false);
        assertNotNull("An over-model shelf lift-off still fails the ordinary vertical envelope", move(top, forged));
    }

    private void shelfFixture() {
        final Material shelf = Material.matchMaterial("SHELF_MUSHROOM");
        org.junit.Assume.assumeNotNull(shelf);
        ServerVersion.setMinecraftVersion("26.3");
        blocks.set(0, 64, 0, shelf, new double[]{
                0.25, 0.375, 0.625, 0.75, 0.6875, 1.0,
                0.0625, 0.5, 0.375, 0.25, 0.6875, 1.0,
                0.25, 0.5, 0.375, 0.9375, 0.6875, 0.625,
                0.75, 0.5, 0.625, 0.9375, 0.6875, 1.0});
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
        return position(x, y, 0.5, ground);
    }

    private PlayerLocation position(double x, double y, double z, boolean ground) {
        final PlayerLocation loc = mock(PlayerLocation.class);
        when(loc.getWorld()).thenReturn(world);
        when(loc.getWorldName()).thenReturn("world");
        when(loc.getX()).thenReturn(x);
        when(loc.getY()).thenReturn(y);
        when(loc.getZ()).thenReturn(z);
        when(loc.getMinX()).thenReturn(x - 0.3);
        when(loc.getMaxX()).thenReturn(x + 0.3);
        when(loc.getMinZ()).thenReturn(z - 0.3);
        when(loc.getMaxZ()).thenReturn(z + 0.3);
        when(loc.getMinY()).thenReturn(y);
        when(loc.getMaxY()).thenReturn(y + 1.8);
        when(loc.getLocation()).thenReturn(new Location(world, x, y, z));
        when(loc.isOnGround()).thenReturn(ground);
        when(loc.getMCAccess()).thenReturn(access);
        when(loc.getBlockCache()).thenReturn(blocks);
        when(loc.isOnGround(anyDouble(), anyDouble(), anyDouble())).thenAnswer(inv -> {
            double margin = inv.getArgument(0), horizontal = inv.getArgument(1), vertical = inv.getArgument(2);
            return BlockProperties.isOnGround(blocks, x - 0.3 - horizontal, y - margin - vertical, z - 0.3 - horizontal,
                    x + 0.3 + horizontal, y + vertical, z + 0.3 + horizontal);
        });
        when(loc.isOnGround(anyDouble())).thenAnswer(inv -> {
            double margin = inv.getArgument(0);
            return BlockProperties.isOnGround(blocks, x - 0.3, y - margin, z - 0.3, x + 0.3, y, z + 0.3);
        });
        when(loc.standsOnEntityContact(anyDouble(), anyDouble(), anyDouble())).thenAnswer(inv -> {
            double margin = inv.getArgument(0), horizontal = inv.getArgument(1), vertical = inv.getArgument(2);
            return entities.standsOnEntityContact(player, x - 0.3 - horizontal, y - margin - vertical, z - 0.3 - horizontal,
                    x + 0.3 + horizontal, y + vertical, z + 0.3 + horizontal);
        });
        when(loc.stepsOnEntityTop(any(), anyDouble())).thenAnswer(inv -> {
            PlayerLocation source = inv.getArgument(0);
            double epsilon = inv.getArgument(1);
            return entities.stepsOnEntityTop(player, x - 0.3, y - epsilon, z - 0.3, x + 0.3, y + epsilon, z + 0.3,
                    source.getMinX(), source.getMinZ(), source.getMaxX(), source.getMaxZ());
        });
        when(loc.standsOnEntityTop(anyDouble())).thenAnswer(inv -> {
            double epsilon = inv.getArgument(0);
            return entities.standsOnEntityTop(player, x - 0.3, y - epsilon, z - 0.3,
                    x + 0.3, y + epsilon, z + 0.3);
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
    private void phaseStack() {
        blocks.fill(-2, 9, -2, 2, 9, 2, Material.GLASS);
        blocks.set(0, 10, 0, Material.STONE);
        blocks.set(0, 11, 0, Material.STONE);
    }

    private void phaseStackHole() {
        blocks.fill(-2, 9, -2, 2, 9, 2, Material.GLASS);
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                if (x == 0 && z == 0) continue;
                blocks.set(x, 10, z, Material.STONE);
                blocks.set(x, 11, z, Material.STONE);
            }
        }
        blocks.set(0, 10, 0, Material.STONE);
        blocks.set(0, 11, 0, Material.STONE);
    }

    private void phaseGuard(boolean enabled) throws Exception {
        worldData.getRawConfiguration().set(ConfPaths.MOVING_SURVIVALFLY_PHASEGUARD_ACTIVE, enabled, 1154);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getVersion).thenReturn("Folia (MC: 26.2)");
            config = new MovingConfig(worldData);
        }
    }

    private void teleportWithStaleGround(PlayerLocation target) {
        // A real teleport installs its target as the setback and the sole stale last-move record.
        data.setSetBack(target.getLocation());
        data.resetPlayerPositions(target);
        final PlayerMoveData last = data.playerMoves.getFirstPastMove();
        // The reproduction trace's stale ground bit sits on to, while resetPlayerPositions sets from.
        last.to.setExtraProperties(target);
        // The accepted live trace has the normal jump envelope (vAD 0.425, jumpPhase 0).
        data.liftOffEnvelope = LiftOffEnvelope.NORMAL;
        assertTrue(last.valid);
        assertFalse(last.toIsValid);
        assertTrue(last.to.onGround);
        assertEquals(target.getX(), data.getSetBackX(), 0.0);
        assertEquals(target.getY(), data.getSetBackY(), 0.0);
    }

    private Location phaseJump(double x, double y, boolean enabled) throws Exception {
        phaseGuard(enabled);
        final PlayerLocation target = position(x, y, true);
        teleportWithStaleGround(target);
        return move(position(x, y, false), position(x, y + (double) 0.42F, false));
    }

    private void assertStaleState(PlayerLocation target) {
        final PlayerMoveData last = data.playerMoves.getFirstPastMove();
        assertTrue(last.valid);
        assertFalse(last.toIsValid);
        assertTrue(last.to.onGround);
        assertEquals(target.getX(), data.getSetBackX(), 0.0);
        assertEquals(target.getY(), data.getSetBackY(), 0.0);
    }

    private void assertEquivalentStaleJump(double x, double y) throws Exception {
        resetData(y);
        violations.clear();
        Location off = phaseJump(x, y, false);
        double offVl = data.survivalFlyVL;
        resetData(y);
        violations.clear();
        Location on = phaseJump(x, y, true);
        assertEquals("stale branch must be behaviorally equivalent", off != null, on != null);
        assertEquals("stale branch VL", offVl, data.survivalFlyVL, 1e-9);
        assertTrue("no phaseguard_hold tag", violations.isEmpty() || !String.valueOf(violations.get(0).getTelemetryTags()).contains("phaseguard_hold"));
    }

    private boolean enableT1241IfPresent() throws Exception {
        return setT1241IfPresent(true);
    }

    private boolean setT1241IfPresent(boolean enabled) throws Exception {
        try {
            final var path = ConfPaths.class.getField("MOVING_SURVIVALFLY_INBLOCKGROUND_ACTIVE");
            worldData.getRawConfiguration().set((String) path.get(null), enabled, 1154);
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(Bukkit::getVersion).thenReturn("Folia (MC: 26.2)");
                config = new MovingConfig(worldData);
            }
            return true;
        } catch (NoSuchFieldException absentOnMaster) {
            return false;
        }
    }

    private PlayerLocation shortPose(double x, double y, boolean ground) {
        final PlayerLocation loc = position(x, y, ground);
        when(loc.getMaxY()).thenReturn(y + 0.6);
        return loc;
    }

    @Test public void s1HoleCentreIsHeldOnlyWhenEnabled() throws Exception {
        phaseStack();
        violations.clear();
        Location off = phaseJump(0.5, 10.0, false);
        assertNull("OFF move correction=" + off + ", vAD=" + data.playerMoves.getCurrentMove().verVelUsed
                + ", VL=" + data.survivalFlyVL + ", tags=" + (violations.isEmpty() ? "none" : violations.get(0).getTelemetryTags()), off);
        resetData(10.0);
        assertNotNull(phaseJump(0.5, 10.0, true));
        assertEquals(42.0, data.survivalFlyVL, 0.01);
        assertFalse("guard tag is attached to the held violation", violations.isEmpty());
        assertTrue(String.valueOf(violations.get(0).getTelemetryTags()).contains("phaseguard_hold"));
    }

    @Test public void s1HoleCentreIsHeldWithT1241KeyAlsoOn() throws Exception {
        phaseStack();
        phaseGuard(true);
        boolean available = enableT1241IfPresent();
        final PlayerLocation target = position(0.5, 10, true);
        teleportWithStaleGround(target);
        assertStaleState(target);
        Location held = move(position(0.5, 10, false), position(0.5, 10 + (double) 0.42F, false));
        assertNotNull(held);
        assertEquals(42.0, data.survivalFlyVL, 0.01);
        if (available) assertTrue(String.valueOf(violations.get(0).getTelemetryTags()).contains("phaseguard_hold"));
    }

    @Test public void s4cOpenCentreIsHeldOnlyWhenEnabled() throws Exception {
        blocks.fill(-2, 9, -2, 2, 9, 2, Material.GLASS);
        blocks.set(0, 10, 0, Material.STONE); blocks.set(0, 11, 0, Material.STONE);
        Location off = phaseJump(0.5, 10.0, false);
        assertNull("OFF move correction=" + off + ", vAD=" + data.playerMoves.getCurrentMove().verVelUsed
                + ", VL=" + data.survivalFlyVL + ", tags=" + (violations.isEmpty() ? "none" : violations.get(0).getTelemetryTags()), off);
        resetData(10.0);
        assertNotNull(phaseJump(0.5, 10.0, true));
    }


    @Test public void c9HeadBlockedColumnWithOpenSideIsHeldOnlyWhenEnabled() throws Exception {
        phaseStackHole();
        blocks.set(1, 10, 0, Material.AIR);
        blocks.set(1, 11, 0, Material.AIR);
        phaseGuard(false); setT1241IfPresent(false);
        Location off = phaseJump(0.5, 10.0, false);
        assertNull("C9 key OFF follows live accepted state", off);
        resetData(10); phaseStackHole();
        blocks.set(1, 10, 0, Material.AIR);
        blocks.set(1, 11, 0, Material.AIR);
        phaseGuard(true); setT1241IfPresent(false);
        Location on = phaseJump(0.5, 10.0, true);
        assertNotNull(on);
        assertTrue(String.valueOf(violations.get(0).getTelemetryTags()).contains("phaseguard_hold"));
        resetData(10); phaseStackHole();
        blocks.set(1, 10, 0, Material.AIR);
        blocks.set(1, 11, 0, Material.AIR);
        phaseGuard(true);
        if (enableT1241IfPresent()) {
            assertNotNull(phaseJump(0.5, 10.0, true));
            assertTrue(String.valueOf(violations.get(0).getTelemetryTags()).contains("phaseguard_hold"));
        }
    }

    @Test public void evsbrSecondSetbackIsHeldAgain() throws Exception {
        for (boolean t1241 : new boolean[] {false, true}) {
            if (t1241 && !setT1241IfPresent(false)) continue;
            for (boolean enabled : new boolean[] {false, true}) {
                resetData(10); phaseStack(); phaseGuard(enabled); setT1241IfPresent(t1241);
                final PlayerLocation target = position(0.5, 10.0, true);
                teleportWithStaleGround(target);
                Location first = move(position(0.5, 10, false), position(0.5, 10 + (double) 0.42F, false));
                assertEquals("first EVsbR jump", enabled, first != null);
                if (enabled) assertTrue(String.valueOf(violations.get(0).getTelemetryTags()).contains("phaseguard_hold"));

                // A real setback resets player positions to its target; the stale ground bit belongs to that target.
                teleportWithStaleGround(target);
                assertStaleState(target);
                violations.clear();
                Location second = move(position(0.5, 10, false), position(0.5, 10 + (double) 0.42F, false));
                assertEquals("second EVsbR jump", enabled, second != null);
                if (enabled) {
                    assertEquals(84.0, data.survivalFlyVL, 0.01);
                    assertTrue(String.valueOf(violations.get(0).getTelemetryTags()).contains("phaseguard_hold"));
                }
            }
        }
    }

    @Test public void freshGroundFlagFalseDoesNotAddGuardBehavior() throws Exception {
        phaseStack();
        phaseGuard(false);
        final PlayerLocation target = position(0.5, 10.0, true);
        teleportWithStaleGround(target);
        data.playerMoves.getFirstPastMove().to.onGround = false;
        final Location off = move(position(0.5, 10, false), position(0.5, 10 + (double) 0.42F, false));
        resetData(10.0); phaseGuard(true); teleportWithStaleGround(target);
        data.playerMoves.getFirstPastMove().to.onGround = false;
        final Location on = move(position(0.5, 10, false), position(0.5, 10 + (double) 0.42F, false));
        assertEquals(off != null, on != null);
        assertEquals(42.0, data.survivalFlyVL, 0.01);
        assertTrue(violations.isEmpty() || !String.valueOf(violations.get(violations.size() - 1).getTelemetryTags()).contains("phaseguard_hold"));
    }

    @Test public void s2CornerRouteRemainsAllowed() throws Exception {
        final boolean t1241Available = enableT1241IfPresent();
        for (boolean t1241 : new boolean[] {false, true}) {
            if (t1241 && !t1241Available) continue;
            for (boolean afterTeleport : new boolean[] {false, true}) {
                double offVl = runS2Corner(false, t1241, afterTeleport);
                double onVl = runS2Corner(true, t1241, afterTeleport);
                assertEquals("corner route VL is unchanged (t1241=" + t1241 + ", teleport=" + afterTeleport + ")",
                        offVl, onVl, 0.0);
                assertEquals("corner route has no violations", 0.0, onVl, 0.0);
            }
        }
    }

    private double runS2Corner(boolean enabled, boolean t1241, boolean afterTeleport) throws Exception {
        resetData(10);
        phaseStackHole();
        phaseGuard(enabled);
        final boolean t1241Available = setT1241IfPresent(t1241);
        if (t1241) assertTrue(t1241Available);
        final PlayerLocation corner = position(0.3, 10.0, 0.3, !afterTeleport);
        // The live trace reports touched-ground at this exact corner. The production location caches that
        // one-argument probe from its movement history; our synthetic location supplies the observed result.
        when(corner.isOnGround(0.03)).thenReturn(true);
        assertFalse(InBlockJumpGuard.holdStaleGroundJump(corner, new ArrayList<>()));
        if (afterTeleport) {
            teleportWithStaleGround(position(0.3, 10.0, 0.3, true));
            assertFalse(data.playerMoves.getFirstPastMove().toIsValid);
        } else {
            // Model the S2 forward/back/left/right key sequence in the corner of the stack cell.
            PlayerLocation at = corner;
            final PlayerLocation[] keys = {
                    position(0.35, 10.0, 0.3, true), position(0.3, 10.0, 0.3, true),
                    position(0.3, 10.0, 0.35, true), position(0.3, 10.0, 0.3, true)
            };
            for (PlayerLocation next : keys) {
                assertNull("corner key move is allowed", move(at, next));
                data.playerMoves.finishCurrentMove();
                at = next;
            }
            assertTrue(data.playerMoves.getFirstPastMove().valid);
            assertTrue(data.playerMoves.getFirstPastMove().toIsValid);
            data.playerMoves.getFirstPastMove().toIsValid = true;
        }
        violations.clear();
        final PlayerLocation jumpFrom = position(0.3, 10.0, 0.3, false);
        when(jumpFrom.isOnGround(0.03)).thenReturn(true);
        try (MockedStatic<InBlockJumpGuard> guard = mockStatic(InBlockJumpGuard.class, CALLS_REAL_METHODS)) {
            Location correction = move(jumpFrom, position(0.3, 10.42, 0.3, false));
            final PlayerMoveData prior = data.playerMoves.getFirstPastMove();
            assertNull("correction=" + correction + ", onGround003=" + jumpFrom.isOnGround(0.03)
                    + ", vAD=" + data.playerMoves.getCurrentMove().verVelUsed + ", VL=" + data.survivalFlyVL
                    + ", prior=" + prior.valid + "/" + prior.toIsValid + ", priorGround=" + prior.to.onGround
                    + ", tags=" + (violations.isEmpty() ? "none" : violations.get(0).getTelemetryTags()), correction);
            if (!afterTeleport) guard.verifyNoInteractions();
        }
        assertEquals(0.0, data.survivalFlyVL, 0.0);
        assertTrue("no held violation", violations.isEmpty());
        return data.survivalFlyVL;
    }

    @Test public void groundedJumpAfterTeleportRemainsAllowed() throws Exception {
        blocks.fill(-2, 9, -2, 2, 9, 2, Material.GLASS);
        phaseGuard(true);
        final PlayerLocation target = position(0.5, 10.0, true);
        teleportWithStaleGround(target);
        assertStaleState(target);
        assertNull(move(position(0.5, 10.0, true), position(0.5, 10 + (double) 0.42F, false)));
        assertEquals(0.0, data.survivalFlyVL, 0.0);
    }

    @Test public void staleGroundFlagAboveAirRemainsUnchanged() throws Exception {
        blocks.fill(-2, 9, -2, 2, 9, 2, Material.GLASS);
        assertEquivalentStaleJump(0.5, 10.25);
    }

    @Test public void headClearBurrowRemainsUnchanged() throws Exception {
        blocks.fill(-2, 9, -2, 2, 9, 2, Material.GLASS);
        blocks.set(0, 10, 0, Material.STONE);
        assertEquivalentStaleJump(0.5, 10.0);
    }

    @Test public void headClearBurrowZeroDyKeyMoveThenJumpMatchesT1241Alone() throws Exception {
        blocks.fill(-2, 9, -2, 2, 9, 2, Material.GLASS);
        blocks.set(0, 10, 0, Material.STONE);
        resetData(10); phaseGuard(false); enableT1241IfPresent();
        PlayerLocation target = position(0.5, 10, true); teleportWithStaleGround(target);
        Location t1241Key = move(position(0.5, 10, false), position(0.6, 10, false));
        data.playerMoves.finishCurrentMove();
        Location t1241Jump = move(position(0.6, 10, false), position(0.6, 10 + (double) 0.42F, false));
        resetData(10); phaseGuard(true);
        target = position(0.5, 10, true); teleportWithStaleGround(target);
        enableT1241IfPresent();
        Location onKey = move(position(0.5, 10, false), position(0.6, 10, false));
        data.playerMoves.finishCurrentMove();
        Location onJump = move(position(0.6, 10, false), position(0.6, 10 + (double) 0.42F, false));
        assertEquals("zero-dy burrow key path matches t1241 alone", t1241Key != null, onKey != null);
        assertEquals("following jump matches t1241 alone", t1241Jump != null, onJump != null);
    }

    @Test public void pearlFaceStraddleWithAirFootColumnRemainsUnchanged() throws Exception {
        blocks.fill(-2, 9, -2, 2, 9, 2, Material.GLASS);
        blocks.set(0, 10, 0, Material.STONE); blocks.set(0, 11, 0, Material.STONE);
        assertEquivalentStaleJump(0.8, 10.0);
    }

    @Test public void horizontalAndStillMovesRemainUnchanged() throws Exception {
        phaseStack();
        Location off = staleHorizontal(false, 0.5, 0.5);
        resetData(10); phaseStack();
        Location on = staleHorizontal(true, 0.5, 0.5);
        assertEquals(off != null, on != null);
        resetData(10); phaseStack(); off = staleHorizontal(false, 0.5, 0.6);
        resetData(10); phaseStack(); on = staleHorizontal(true, 0.5, 0.6);
        assertEquals(off != null, on != null);
    }

    private Location staleHorizontal(boolean enabled, double fromX, double toX) throws Exception {
        phaseGuard(enabled);
        PlayerLocation target = position(fromX, 10, true);
        teleportWithStaleGround(target);
        assertStaleState(target);
        return move(position(fromX, 10, false), position(toX, 10, false));
    }

    @Test public void crawlPoseRemainsUnchanged() throws Exception {
        phaseStack();
        phaseGuard(false);
        PlayerLocation target = shortPose(0.5, 10, true);
        teleportWithStaleGround(target);
        assertTrue(shortPose(0.5, 10, false).getMaxY() < 11.0);
        Location off = move(shortPose(0.5, 10, false), shortPose(0.5, 10 + (double) 0.42F, false));
        resetData(10); phaseStack(); phaseGuard(true); target = shortPose(0.5, 10, true); teleportWithStaleGround(target);
        Location on = move(shortPose(0.5, 10, false), shortPose(0.5, 10 + (double) 0.42F, false));
        assertEquals(off != null, on != null);
    }

    @Test public void netherRoofGuardIsNeverMoreLenient() throws Exception {
        blocks.fill(-2, 126, -2, 2, 126, 2, Material.GLASS);
        blocks.set(0, 127, 0, Material.STONE); blocks.set(0, 128, 0, Material.STONE);
        resetData(127); phaseGuard(false);
        PlayerLocation target = position(0.5, 127.0, true); teleportWithStaleGround(target);
        Location off = move(position(0.5, 127, false), position(0.5, 127 + (double) 0.42F, false));
        resetData(127); blocks.fill(-2, 126, -2, 2, 126, 2, Material.GLASS);
        blocks.set(0, 127, 0, Material.STONE); blocks.set(0, 128, 0, Material.STONE);
        phaseGuard(true); target = position(0.5, 127, true); teleportWithStaleGround(target);
        Location on = move(position(0.5, 127, false), position(0.5, 127 + (double) 0.42F, false));
        assertFalse(off != null && on == null);
    }

    @Test public void predicateRejectsAnAirColumnAndPartialFeetButChecksHeadGeometry() {
        phaseStack();
        List<String> tags = new ArrayList<>();
        assertTrue(InBlockJumpGuard.holdStaleGroundJump(position(0.5, 10, false), tags));
        assertTrue(tags.contains("phaseguard_hold"));

        tags.clear(); blocks.set(1, 10, 0, Material.AIR);
        assertFalse(InBlockJumpGuard.holdStaleGroundJump(position(0.8, 10, false), tags));
        blocks.cleanup(); phaseStack();
        blocks.set(0, 10, 0, Material.STONE_SLAB, new double[] {0, 0, 0, 1, 0.5, 1});
        assertFalse("slab bounds must remain partial", blocks.isFullBounds(0, 10, 0));
        assertFalse(InBlockJumpGuard.holdStaleGroundJump(position(0.5, 10, false), tags));

        blocks.set(0, 10, 0, Material.STONE);
        blocks.set(0, 11, 0, Material.AIR);
        assertFalse(InBlockJumpGuard.holdStaleGroundJump(position(0.5, 10, false), tags));
        blocks.cleanup(); phaseStack();
        blocks.set(0, 11, 0, Material.STONE_SLAB, new double[] {0, 0, 0, 1, 0.5, 1});
        assertTrue(InBlockJumpGuard.holdStaleGroundJump(position(0.5, 10, false), tags));
        blocks.set(0, 11, 0, Material.STONE);
        assertTrue(InBlockJumpGuard.holdStaleGroundJump(position(0.5, 10, false), tags));
    }

    @Test public void predicateAcceptsFeetMinYJustBelowBlockBoundaryWithinEpsilon() {
        phaseStack();
        PlayerLocation loc = position(0.5, 10, false);
        when(loc.getMinY()).thenReturn(10.0 - 5.0E-8);
        assertTrue(InBlockJumpGuard.holdStaleGroundJump(loc, new ArrayList<>()));
    }

    @Test public void phaseGuardDefaultsOffAndCanBeEnabled() throws Exception {
        assertFalse(config.sfPhaseGuard);
        phaseGuard(true); assertTrue(config.sfPhaseGuard);
        phaseGuard(false); assertFalse(config.sfPhaseGuard);
    }

}
