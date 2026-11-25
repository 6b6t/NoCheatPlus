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
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
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
import fr.neatmonster.nocheatplus.checks.moving.MovingData;
import fr.neatmonster.nocheatplus.checks.moving.location.tracking.LocationTrace.TraceEntryPool;
import fr.neatmonster.nocheatplus.checks.moving.magic.LostGround;
import fr.neatmonster.nocheatplus.checks.moving.magic.InBlockStand;
import fr.neatmonster.nocheatplus.checks.moving.model.LiftOffEnvelope;
import fr.neatmonster.nocheatplus.checks.moving.model.ModelFlying;
import fr.neatmonster.nocheatplus.checks.moving.model.PlayerMoveData;
import fr.neatmonster.nocheatplus.checks.moving.player.SurvivalFly;
import fr.neatmonster.nocheatplus.checks.moving.util.MovingUtil;
import fr.neatmonster.nocheatplus.checks.workaround.WRPT;
import fr.neatmonster.nocheatplus.compat.Folia;
import fr.neatmonster.nocheatplus.compat.MCAccess;
import fr.neatmonster.nocheatplus.compat.bukkit.BlockCacheBukkitModern;
import fr.neatmonster.nocheatplus.compat.bukkit.NativeLandingAccess;
import fr.neatmonster.nocheatplus.compat.versions.ServerVersion;
import fr.neatmonster.nocheatplus.config.ConfPaths;
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
    private PluginTests.UnitTestNoCheatPlusAPI api;
    private FakeBlockCache blocks;
    private BlockCacheBukkitModern entities;
    private final List<Entity> nearby = new ArrayList<>();
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
        api = new PluginTests.UnitTestNoCheatPlusAPI() {
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
        blocks = new FakeBlockCache();
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
    }

    @Test
    public void stoppingGlidePreservesDecayingMomentum() {
        final PlayerLocation from = position(1.65, 90.0, false);
        acceptedGlide(from, -0.18, 1.65);

        final PlayerLocation coast = position(3.31, 89.82, false);
        assertNull(move(from, coast));
        assertFalse(data.hasQueuedHorVel());
        assertFalse(data.hasQueuedVerVel());
        data.playerMoves.finishCurrentMove();
        final PlayerLocation falling = position(4.8206, 89.5652, false);
        assertNull(move(coast, falling));
        assertEquals(38, data.playerMoves.getCurrentMove().elytraCoastMoves);
        data.playerMoves.finishCurrentMove();

        final PlayerLocation slowed = position(5.02, 89.237096, false);
        assertNull(move(falling, slowed));
        assertEquals(0, data.playerMoves.getCurrentMove().elytraCoastMoves);
        assertFalse(data.hasQueuedHorVel());
        assertFalse(data.hasQueuedVerVel());
        data.playerMoves.finishCurrentMove();
        assertNotNull(move(slowed, position(6.2, 90.036, false)));
    }

    @Test
    public void rocketClimbExitCarriesOneTrackedBoost() {
        final PlayerLocation from = position(21.8589623, 138.9656231, false);
        when(from.getYaw()).thenReturn(-90f);
        when(from.getPitch()).thenReturn(-45f);
        final PlayerLocation exit = position(23.0200528, 140.1970322, false);
        final PlayerLocation next = position(24.0766452, 141.3254131, false);
        final PlayerLocation third = position(25.0381443, 142.3528264, false);

        acceptedGlide(from, 1.2607366, 1.1202147);
        data.fireworksBoostTickExpire = data.getPlayerMoveCount() + 1;
        assertNull(move(from, exit));
        assertEquals(39, data.playerMoves.getCurrentMove().elytraCoastMoves);
        data.playerMoves.finishCurrentMove();
        assertNull(move(exit, next));
        assertEquals(38, data.playerMoves.getCurrentMove().elytraCoastMoves);
        data.playerMoves.finishCurrentMove();
        assertNull(move(next, third));
        assertEquals(37, data.playerMoves.getCurrentMove().elytraCoastMoves);

        resetData(10.0);
        data.increasePlayerMoveCount();
        data.fireworksBoostTickExpire = data.getPlayerMoveCount();
        acceptedGlide(from, 1.2607366, 1.1202147);
        assertNotNull(move(from, exit));
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
    public void inBlockGroundSequencesWithSurvivalFlyLeniencyExhausted() throws Exception {
        assertFalse("new config key stays off by default", config.sfInBlockGround);
        configureInBlockGround(true);

        blocks.cleanup();
        blocks.fill(-2, 63, -2, 2, 64, 2, Material.OBSIDIAN);
        final PlayerLocation burrow = position(0.5, 64.0, false);

        // (i) Look-only packets are accepted with the workaround and remain live behaviour with it disabled.
        resetData(64.0);
        data.survivalFlyVL = 36.0;
        int onViolations = 0;
        for (int i = 0; i < 20; i++) {
            assertTrue("VL precondition", data.survivalFlyVL > 35.0);
            if (move(burrow, burrow) != null) onViolations++;
            data.playerMoves.finishCurrentMove();
        }
        assertEquals(0, onViolations);
        configureInBlockGround(false);
        resetData(64.0);
        data.survivalFlyVL = 36.0;
        int offViolations = 0;
        for (int i = 0; i < 20; i++) {
            assertTrue("VL precondition", data.survivalFlyVL > 35.0);
            if (move(burrow, burrow) != null) offViolations++;
            data.playerMoves.finishCurrentMove();
        }
        assertTrue("disabled config matches the live cancellation", offViolations > 0);

        // (ii) A vanilla jump arc from inside the block is accepted through its landing on the block top.
        configureInBlockGround(true);
        resetData(64.0);
        data.survivalFlyVL = 36.0;
        assertTrue("source geometry recognized", InBlockStand.isStanding(burrow, config.yOnGround, true, true, null));
        assertTrue("config enabled", config.sfInBlockGround);
        assertTrue("head-blocked option parses", config.sfInBlockGroundHeadBlocked);
        PlayerLocation from = burrow;
        double velocity = (double) 0.42F;
        boolean landed = false;
        for (int tick = 0; tick < 20 && !landed; tick++) {
            final double predicted = from.getY() + velocity;
            landed = predicted >= 65.0;
            final PlayerLocation to = position(0.5, landed ? 65.0 : predicted, landed);
            assertTrue("VL precondition", data.survivalFlyVL > 35.0);
            final Location correction = move(from, to);
            assertNull("vanilla jump tick " + tick, correction);
            data.playerMoves.finishCurrentMove();
            from = to;
            velocity = (velocity - 0.08) * 0.98;
        }
        assertTrue("jump lands above burrow block", landed);

        // (iii-a) An ascending destination at the bottom of the second block is not a reset-to.
        blocks.cleanup();
        blocks.fill(-2, 63, -2, 2, 64, 2, Material.OBSIDIAN);
        blocks.set(0, 65, 0, Material.OBSIDIAN);
        final int[] stackClimbOn = ascendingIntoTwoBlockStack(true);
        final int[] stackClimbOff = ascendingIntoTwoBlockStack(false);
        assertArrayEquals("ascending into a block bottom preserves live result", stackClimbOff, stackClimbOn);

        // (iii-b) A vanilla client caps an in-block jump under a ceiling two blocks above its feet.
        blocks.cleanup();
        blocks.fill(-2, 63, -2, 2, 64, 2, Material.OBSIDIAN);
        blocks.set(0, 66, 0, Material.OBSIDIAN);
        final int ceilingOn = cappedCeilingJump(true);
        final int ceilingOff = cappedCeilingJump(false);
        assertEquals("ceiling-limited vanilla jump is accepted only by the workaround", 0, ceilingOn);
        assertEquals("the live in-air path cancels this jump after VL-up", 1, ceilingOff);

        // (iii-c) Use NCP's potion helper and the normal envelope for Jump Boost I's initial gain.
        blocks.cleanup();
        blocks.fill(-2, 63, -2, 2, 64, 2, Material.OBSIDIAN);
        final int jumpBoostOn = jumpBoostJump(true);
        final int jumpBoostOff = jumpBoostJump(false);
        assertEquals("jump-boost vanilla impulse is accepted", 0, jumpBoostOn);
        assertEquals("jump-boost move is cancelled by live after VL-up", 1, jumpBoostOff);

        // The initial vanilla jump gain is allowed; smaller in-block rises without a ceiling match live.
        inBlockRiseParity("C6a +0.40 probe", 0.40);
        inBlockRiseParity("C6a +0.20 probe", 0.20);
        inBlockRiseParity("C6a +0.0625 probe", 0.0625);
        inBlockRiseParity("C3b centre +0.20 probe without ceiling", 0.20);
        c3bHeadOccupiedCentreRiseParity();

        // (iii-d) Sprint-jump horizontal boost remains governed by the live horizontal envelope.
        assertEquals("vanilla-sized sprint-jump impulse is accepted with the workaround", 0, sprintJump());

        // (iii) A blocked head still rejects a jump, identically with the config on and off.
        blocks.set(0, 65, 0, Material.OBSIDIAN);
        final PlayerLocation headBlocked = position(0.5, 64.0, false);
        final PlayerLocation jump = position(0.5, 64.42, false);
        resetData(64.0);
        data.survivalFlyVL = 36.0;
        assertNotNull("head-blocked jump with workaround", move(headBlocked, jump));
        final double blockedVlOn = data.survivalFlyVL;
        configureInBlockGround(false);
        resetData(64.0);
        data.survivalFlyVL = 36.0;
        assertNotNull("head-blocked jump on live path", move(headBlocked, jump));
        assertEquals(blockedVlOn, data.survivalFlyVL, 0.0);

        // (iv) A tolerated stationary move does not arm a later head-blocked jump.
        configureInBlockGround(true);
        resetData(64.0);
        data.survivalFlyVL = 36.0;
        assertNull(move(headBlocked, headBlocked));
        data.playerMoves.finishCurrentMove();
        final PlayerLocation slightStep = position(0.7, 64.0, false);
        data.survivalFlyVL = 36.0;
        assertTrue("VL precondition", data.survivalFlyVL > 35.0);
        assertNull("head-blocked horizontal 0.2 move", move(headBlocked, slightStep));
        data.playerMoves.finishCurrentMove();
        data.survivalFlyVL = 36.0;
        assertNotNull(move(slightStep, position(0.7, 64.42, false)));
        assertFalse("no inherited touched-ground allowance", data.playerMoves.getCurrentMove().touchedGroundWorkaround);

        // (v) Small and repeated non-vanilla vertical rises keep exactly the live result.
        blocks.set(0, 65, 0, Material.AIR);
        final int[] liveRiseViolations = riseSequence(false);
        final int[] candidateRiseViolations = riseSequence(true);
        assertArrayEquals(liveRiseViolations, candidateRiseViolations);

        // (vi) A 0.6 horizontal move remains over the live horizontal limit.
        final int liveHorizontal = horizontalSequence(false);
        final int candidateHorizontal = horizontalSequence(true);
        assertEquals(liveHorizontal, candidateHorizontal);
        assertTrue("horizontal cheat still violates", candidateHorizontal > 0);
    }

    private int[] ascendingIntoTwoBlockStack(final boolean enabled) throws Exception {
        configureInBlockGround(enabled);
        resetData(64.0);
        data.survivalFlyVL = 36.0;
        final PlayerLocation from = position(0.5, 64.0, false);
        final Location correction = move(from, position(0.5, 65.0, false));
        return new int[] {correction == null ? 0 : 1, (int) data.survivalFlyVL};
    }

    private int cappedCeilingJump(final boolean enabled) throws Exception {
        configureInBlockGround(enabled);
        resetData(64.0);
        data.survivalFlyVL = 36.0;
        final PlayerLocation from = position(0.5, 64.0, false);
        return move(from, position(0.5, 64.2, false)) == null ? 0 : 1;
    }

    private int jumpBoostJump(final boolean enabled) throws Exception {
        configureInBlockGround(enabled);
        when(access.getJumpAmplifier(player)).thenReturn(0.0); // Bukkit amplifier 0 = Jump Boost I.
        resetData(64.0);
        data.jumpAmplifier = MovingUtil.getJumpAmplifier(player, access);
        data.survivalFlyVL = 36.0;
        final double gain = data.liftOffEnvelope.getMinJumpGain(data.jumpAmplifier);
        assertEquals("jump gain comes from NCP's envelope", 0.52, gain, 1.0e-9);
        final PlayerLocation from = position(0.5, 64.0, false);
        return move(from, position(0.5, 64.0 + gain, false)) == null ? 0 : 1;
    }

    private int sprintJump() throws Exception {
        configureInBlockGround(true);
        when(access.getJumpAmplifier(player)).thenReturn(Double.NEGATIVE_INFINITY);
        resetData(64.0);
        data.survivalFlyVL = 36.0;
        data.timeSprinting = 1000L;
        data.multSprinting = 1.3;
        final PlayerLocation from = position(0.5, 64.0, false);
        // NCP's ordinary sprint-jump initial horizontal envelope from the live helper.
        final double horizontal = 0.20;
        return move(from, position(0.5 + horizontal, 64.42, false)) == null ? 0 : 1;
    }

    @Test
    public void inBlockWalkOutFinishesFullyOutsideTheBlock() throws Exception {
        configureInBlockGround(true);
        blocks.cleanup();
        blocks.fill(-2, 63, -2, 2, 64, 2, Material.OBSIDIAN);
        blocks.fill(1, 64, -2, 2, 64, 2, Material.AIR);
        resetData(64.0);
        data.survivalFlyVL = 36.0;
        PlayerLocation from = position(0.5, 64.0, false);
        int accepted = 0;
        for (int step = 1; step <= 8; step++) {
            final double x = 0.5 + step * 0.2;
            final PlayerLocation to = position(x, 64.0, x >= 0.9);
            assertTrue("VL remains exhausted", data.survivalFlyVL > 35.0);
            final Location correction = move(from, to);
            assertNull("walk-out step " + step, correction);
            if (correction == null) accepted++;
            data.playerMoves.finishCurrentMove();
            from = to;
        }
        assertEquals("all vanilla walk-out steps accepted", 8, accepted);
        assertTrue("final player footprint is fully beyond the starting block",
                from.getMinX() >= 1.0 && from.getMaxX() > 1.0);
    }

    private void inBlockRiseParity(final String name, final double rise) throws Exception {
        final int[] result = new int[2];
        for (int enabled = 0; enabled <= 1; enabled++) {
            configureInBlockGround(enabled == 1);
            resetData(64.0);
            data.survivalFlyVL = 36.0;
            final PlayerLocation from = position(0.5, 64.0, false);
            if (rise == 0.20) {
                assertFalse("sub-jump rise without a ceiling is not a jump start",
                        InBlockStand.isVanillaJumpStart(from, 64.0 + rise - 64.0, data.jumpAmplifier,
                                data.liftOffEnvelope));
            }
            final Location correction = move(from, position(0.5, 64.0 + rise, false));
            result[enabled] = correction == null ? 0 : 1;
        }
        assertEquals(name + " equals live (OFF)", result[0], result[1]);
    }

    private void c3bHeadOccupiedCentreRiseParity() throws Exception {
        final double[] rises = {0.20, 0.42};
        for (int riseIndex = 0; riseIndex < rises.length; riseIndex++) {
            for (int priorGroundMove = 0; priorGroundMove <= 1; priorGroundMove++) {
                for (int exhaustedVl = 0; exhaustedVl <= 1; exhaustedVl++) {
                    final int[] result = new int[2];
                    for (int enabled = 0; enabled <= 1; enabled++) {
                        configureInBlockGround(enabled == 1);
                        blocks.cleanup();
                        blocks.fill(-2, 63, -2, 2, 64, 2, Material.OBSIDIAN);
                        blocks.set(0, 66, 0, Material.OBSIDIAN);
                        final PlayerLocation upperOverlap = position(0.5, 64.25, false);
                        assertFalse("feet+2 solid overlaps the head while feet+1 is empty",
                                InBlockStand.isStanding(upperOverlap, 0.3, true, true, null));
                        blocks.set(0, 65, 0, Material.OBSIDIAN);
                        resetData(64.0);
                        data.survivalFlyVL = exhaustedVl == 1 ? 36.0 : 0.0;
                        final PlayerLocation center = position(0.5, 64.0, false);
                        assertFalse("head occupied by the full block in the 3-stack", InBlockStand.isStanding(
                                center, config.yOnGround, true, true, null));
                        if (priorGroundMove == 1) {
                            final PlayerLocation onGround = position(0.5, 64.0, true);
                            assertNull("prior on-ground move", move(onGround, onGround));
                            data.playerMoves.finishCurrentMove();
                            data.survivalFlyVL = exhaustedVl == 1 ? 36.0 : 0.0;
                        }
                        final Location correction = move(center, position(0.5, 64.0 + rises[riseIndex], false));
                        result[enabled] = correction == null ? 0 : 1;
                        result[enabled] = 100 * result[enabled] + (int) Math.round(data.survivalFlyVL);
                    }
                    assertEquals("C3b centre +" + rises[riseIndex] + " equals live; prior ground="
                                    + priorGroundMove + ", exhausted VL=" + exhaustedVl,
                            result[0], result[1]);
                }
            }
        }
        blocks.cleanup();
        blocks.fill(-2, 63, -2, 2, 64, 2, Material.OBSIDIAN);
    }

    private int[] riseSequence(final boolean enabled) throws Exception {
        configureInBlockGround(enabled);
        final int[] violations = new int[2];
        for (int sequence = 0; sequence < 2; sequence++) {
            resetData(64.0);
            data.survivalFlyVL = 36.0;
            PlayerLocation from = position(0.5, 64.0, false);
            final int count = sequence == 0 ? 10 : 3;
            final double rise = sequence == 0 ? 0.0625 : 0.40;
            for (int i = 0; i < count; i++) {
                assertTrue("VL precondition", data.survivalFlyVL > 35.0);
                final PlayerLocation to = position(0.5, from.getY() + rise, false);
                if (move(from, to) != null) violations[sequence]++;
                data.playerMoves.finishCurrentMove();
                from = to;
            }
        }
        return violations;
    }

    private int horizontalSequence(final boolean enabled) throws Exception {
        configureInBlockGround(enabled);
        resetData(64.0);
        data.survivalFlyVL = 36.0;
        int violations = 0;
        for (int i = 0; i < 4; i++) {
            assertTrue("VL precondition", data.survivalFlyVL > 35.0);
            final PlayerLocation from = position(0.5 + i * 0.6, 64.0, false);
            final PlayerLocation to = position(1.1 + i * 0.6, 64.0, false);
            if (move(from, to) != null) violations++;
            data.playerMoves.finishCurrentMove();
        }
        return violations;
    }

    private void configureInBlockGround(final boolean active) {
        final var worldData = api.getWorldDataManager().getWorldData("inblock-ground-" + active);
        worldData.getRawConfiguration().set(ConfPaths.MOVING_SURVIVALFLY_INBLOCKGROUND_ACTIVE, active);
        worldData.getRawConfiguration().set(ConfPaths.MOVING_SURVIVALFLY_INBLOCKGROUND_HEADBLOCKED, true);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getVersion).thenReturn("Folia (MC: 26.2)");
            config = new MovingConfig(worldData);
        }
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

    private void acceptedGlide(PlayerLocation to, double dy, double horizontal) {
        final ItemStack elytra = mock(ItemStack.class);
        final Damageable damage = mock(Damageable.class);
        when(damage.isUnbreakable()).thenReturn(true);
        when(elytra.getType()).thenReturn(Material.ELYTRA);
        when(elytra.getItemMeta()).thenReturn(damage);
        when(player.getInventory().getChestplate()).thenReturn(elytra);
        acceptedHistory(to, dy, horizontal);
        final PlayerMoveData glide = data.playerMoves.getFirstPastMove();
        glide.flyCheck = CheckType.MOVING_CREATIVEFLY;
        glide.modelFlying = mock(ModelFlying.class);
        when(glide.modelFlying.getId()).thenReturn(MovingConfig.ID_JETPACK_ELYTRA);
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
