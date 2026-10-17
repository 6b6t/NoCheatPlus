package fr.neatmonster.nocheatplus.compat.bukkit;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
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
public class TestLandingStepFidelity {
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
    private Location currentFeet;
    private boolean gridPrepared;
    private boolean speedTwoHistory;
    private final Map<String, PlayerLocation> positions = new HashMap<>();

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
        access = mock(MCAccess.class, withSettings().stubOnly());
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
        playerData = mock(IPlayerData.class, withSettings().stubOnly());
        world = mock(World.class, withSettings().stubOnly());
        when(world.getName()).thenReturn("world");
        player = mock(Player.class, withSettings().stubOnly());
        when(player.getLocation()).thenAnswer(inv -> currentFeet);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getWidth()).thenReturn(0.6);
        when(player.getWorld()).thenReturn(world);
        when(world.getNearbyEntities(any(Location.class), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(Collections.emptyList());
        when(player.getInventory()).thenReturn(mock(PlayerInventory.class, withSettings().stubOnly()));
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
        final ViolationData actions = mock(ViolationData.class, withSettings().stubOnly());
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


    private void fall() { platform(Material.GLASS, 10.0, false); }

    private boolean landingStep(PlayerMoveData move) {
        if (base) return false;
        try { return PlayerMoveData.class.getField("landingStep").getBoolean(move); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    /** Falling-history landing probe, with the actual Speed II effect level. */
    @Test public void landingProbe() {
        for (boolean speedTwo : new boolean[] {false, true}) {
            speedTwoHistory = speedTwo;
            when(access.getFasterMovementAmplifier(player)).thenReturn(speedTwo ? 2.0 : Double.NEGATIVE_INFINITY);
            for (int i = 300; i <= 402; i += 6) {
                double h = i / 1000.0;
                int flat = land(false, h, -1, true, 0.3);
                int step = land(true, h, -1, true, 0.3);
                PlayerMoveData m = data.playerMoves.getCurrentMove();
                assertFalse("a landing step cannot be a bunnyhop", m.bunnyHop);
                assertTrue("landing cannot gain horizontal allowance", step == 0 || flat > 0);
                System.out.printf(Locale.ROOT, "landing probe base=%s speedII=%s landH=%.3f flat=%s step=%s bunny=%s%n",
                        base, speedTwo, h, flat > 0, step > 0, m.bunnyHop);
            }
        }
    }

    /** History grid, extended to Speed II, next walking moves, and per-landing comparisons. */
    @Test public void historyGrid() {
        for (boolean speedTwo : new boolean[] {false, true}) {
            speedTwoHistory = speedTwo;
            when(access.getFasterMovementAmplifier(player)).thenReturn(speedTwo ? 2.0 : Double.NEGATIVE_INFINITY);
            for (double history : new double[] {0.25, 0.3, 0.35, 0.4, 0.45, 0.5}) {
                double[] flat = {-1, -1, -1}, step = {-1, -1, -1};
                for (int i = 10; i <= 70; i++) {
                    double h = i / 100.0;
                    boolean flatOK = land(false, h, -1, true, history) > 0;
                    boolean stepOK = land(true, h, -1, true, history) > 0;
                    if (base) assertFalse("base rejects raised landing", stepOK);
                    else assertTrue("step horizontal acceptance must be a subset of flat", !stepOK || flatOK);
                    if (!flatOK) continue;
                    flat[0] = h;
                    if (stepOK) step[0] = h;
                    for (int mode = 1; mode <= 2; mode++) {
                        boolean jump = mode == 1;
                        double flatNext = -1, stepNext = -1;
                        // Do not assume acceptance is monotonic (the hop has disjoint windows).
                        for (int j = 0; j <= 120; j++) {
                            double nextH = j / 100.0;
                            if (land(false, h, nextH, jump, history) == 2) flatNext = nextH;
                            if (stepOK && land(true, h, nextH, jump, history) == 2) stepNext = nextH;
                        }
                        assertTrue("next move gains speed at identical landing h", stepNext <= flatNext);
                        flat[mode] = Math.max(flat[mode], flatNext);
                        step[mode] = Math.max(step[mode], stepNext);
                    }
                }
                for (int i = 0; i < 3; i++) assertTrue(step[i] <= flat[i]);
                if (!base) assertTrue("supported landing is accepted", step[0] >= 0.2);
                System.out.printf(Locale.ROOT,
                        "landing grid base=%s speedII=%s histH=%.2f flatLand=%.2f stepLand=%.2f flatJump=%.2f stepJump=%.2f flatWalk=%.2f stepWalk=%.2f%n",
                        base, speedTwo, history, flat[0], step[0], flat[1], step[1], flat[2], step[2]);
            }
        }
    }

    private int land(boolean step, double landH, double nextH, boolean jump, double history) {
        resetData(10.0);
        clearInvocations(check);
        folia.clearInvocations();
        nativeAccess.clearInvocations();
        if (!gridPrepared) { fall(); boat(1.100001, 10.5625, false); gridPrepared = true; }
        data.multSprinting = 1.3; data.timeSprinting = 100000L;
        PlayerLocation from = step ? position(0.8, 10.1215, false) : position(0.8, 10.445, false);
        acceptedHistory(from, -0.374, history);
        PlayerLocation top = position(0.8 + landH, step ? 10.5625 : 10.0, true);
        Location correction = move(from, top);
        PlayerMoveData landing = data.playerMoves.getCurrentMove();
        if (step) {
            assertFalse("landing step must never count as a hop", landing.bunnyHop);
            assertFalse("landing step must never count as a jump take-off", landing.allowjump);
            if (correction == null) assertTrue("only landingstep supplies acceptance", landingStep(landing));
        }
        if (correction != null) return 0;
        if (nextH < 0) return 1;
        data.playerMoves.finishCurrentMove();
        data.timeSprinting = jump ? 100000L : 0;
        Location next = move(top, position(top.getX() + nextH, top.getY() + (jump ? (double) 0.42F : 0), !jump));
        assertFalse("landing marker resets before the next move", landingStep(data.playerMoves.getCurrentMove()));
        return next == null ? 2 : 1;
    }

    /** PlayerMoveEvent current feet are at `to`; native collisionBelow is not strict top contact. */
    @Test public void collisionBelowInsideBoat() {
        fall();
        nativeAccess.when(() -> NativeLandingAccess.hasCollisionBelow(any(Player.class))).thenReturn(true);
        boat(0.5, 10.9, false);
        PlayerLocation from = position(0.8, 10.1215, false), to = position(1.0, 10.3, true);
        acceptedHistory(from, -0.374, 0.2);
        currentFeet = to.getLocation();
        assertTrue("ordinary native contact takes the current-feet shortcut", to.standsOnEntityContact(0.00001, 0, 0));
        assertNotNull("feet inside a boat are rejected like base", move(from, to));
    }

    @Test public void overlappingSweptHistoryCannotSupplyEndpointTop() {
        fall();
        when(player.isOnGround()).thenReturn(true);
        nativeAccess.when(() -> NativeLandingAccess.supportsSweptBoat(any(Entity.class), anyDouble(), anyDouble(),
                anyDouble(), anyDouble(), anyDouble())).thenReturn(true);
        boat(0.5, 10.9, false);
        PlayerLocation from = position(0.8, 10.1215, false), to = position(1.0, 10.3, true);
        acceptedHistory(from, -0.374, 0.2);
        currentFeet = to.getLocation();
        assertTrue(to.standsOnEntityContact(0.00001, 0, 0));
        assertNotNull("swept history is not the endpoint boat top", move(from, to));
    }

    @Test public void endpointTopRequiresOnlyFloatNoise() {
        for (boolean raft : new boolean[] {false, true}) {
            for (double gap : new double[] {-0.00002, -0.000005, 0, 0.000005, 0.00002}) {
                resetData(10.0); blocks.cleanup(); nearby.clear(); fall();
                boat(1.100001, 10.5625 + gap, raft);
                PlayerLocation from = position(0.8, 10.1215, false);
                acceptedHistory(from, -0.374, 0.2);
                boolean accepted = move(from, position(1.0, 10.5625, true)) == null;
                assertEquals("boat/raft top epsilon", !base && Math.abs(gap) <= 0.00001, accepted);
            }
        }
    }

    @Test public void slowFallGapMargin() {
        when(player.hasPotionEffect(PotionEffectType.SLOW_FALLING)).thenReturn(true);
        when(player.getActivePotionEffects()).thenReturn(Collections.singletonList(
                new PotionEffect(PotionEffectType.SLOW_FALLING, 200, 0)));
        assertEquals(0, fr.neatmonster.nocheatplus.utilities.PotionUtil.getPotionEffectAmplifier(
                player, PotionEffectType.SLOW_FALLING), 0);
        // The test bootstrap has no Bukkit registry for Bridge1_13's legacy getByName lookup.
        // Resolve this bridge through the real active-effect predicate instead.
        try (MockedStatic<fr.neatmonster.nocheatplus.compat.Bridge1_13> bridge = mockStatic(
                fr.neatmonster.nocheatplus.compat.Bridge1_13.class, CALLS_REAL_METHODS)) {
            bridge.when(() -> fr.neatmonster.nocheatplus.compat.Bridge1_13.getSlowfallingAmplifier(player))
                    .thenAnswer(inv -> fr.neatmonster.nocheatplus.utilities.PotionUtil.getPotionEffectAmplifier(
                            player, PotionEffectType.SLOW_FALLING));
            for (boolean sneaking : new boolean[] {false, true}) {
                when(player.isSneaking()).thenReturn(sneaking);
                fall(); boat(1.100001, 10.5625, false);
                PlayerLocation from = position(0.8, 10.13, false);
                acceptedHistory(from, -0.05, 0.1);
                assertNotNull("Slow Falling retains base behavior", move(from, position(1.0, 10.5625, true)));
                resetData(10.0); nearby.clear(); blocks.cleanup();
            }
        }
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
        Entity entity = mock(Entity.class, withSettings().stubOnly());
        EntityType type = raft ? mock(EntityType.class, withSettings().stubOnly()) : EntityType.BOAT;
        if (raft) when(type.name()).thenReturn("BAMBOO_RAFT");
        when(entity.getType()).thenReturn(type);
        when(entity.getUniqueId()).thenReturn(UUID.randomUUID());
        when(entity.isValid()).thenReturn(true);
        when(entity.getBoundingBox()).thenReturn(new BoundingBox(edge, top - 0.5625, -0.1875, edge + 1.375, top, 1.1875));
        nearby.add(entity);
    }

    private Location move(PlayerLocation from, PlayerLocation to) {
        // Paper/Folia: the server has already moved the player to `to` when PlayerMoveEvent fires.
        currentFeet = to.getLocation();
        data.increasePlayerMoveCount();
        final PlayerMoveData current = data.playerMoves.getCurrentMove();
        current.set(from, to);
        current.setExtraProperties(from, to);
        current.flyCheck = CheckType.MOVING_SURVIVALFLY;
        return check.check(player, from, to, 0, data, config, playerData,
                data.getPlayerMoveCount(), 1000L + data.getPlayerMoveCount() * 50L, false);
    }

    private PlayerLocation position(double x, double y, boolean ground) {
        final String key = x + ":" + y + ":" + ground;
        if (positions.containsKey(key)) return positions.get(key);
        final PlayerLocation loc = mock(PlayerLocation.class, withSettings().stubOnly());
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
        if (!base) {
            try {
                when((boolean) PlayerLocation.class.getMethod("standsOnEntityTop", double.class)
                        .invoke(loc, anyDouble())).thenAnswer(inv -> {
                    double epsilon = inv.getArgument(0);
                    return (boolean) BlockCacheBukkitModern.class.getMethod("standsOnEntityTop", Entity.class,
                            double.class, double.class, double.class, double.class, double.class, double.class)
                            .invoke(entities, player, x - 0.3, y - epsilon, 0.2, x + 0.3, y + epsilon, 0.8);
                });
                when((boolean) PlayerLocation.class.getMethod("stepsOnEntityTop",
                        fr.neatmonster.nocheatplus.utilities.location.RichEntityLocation.class, double.class)
                        .invoke(loc, any(), anyDouble())).thenAnswer(inv -> {
                    PlayerLocation source = inv.getArgument(0);
                    double epsilon = inv.getArgument(1);
                    return (boolean) BlockCacheBukkitModern.class.getMethod("stepsOnEntityTop", Entity.class,
                            double.class, double.class, double.class, double.class, double.class, double.class,
                            double.class, double.class, double.class, double.class)
                            .invoke(entities, player, x - 0.3, y - epsilon, 0.2, x + 0.3, y + epsilon, 0.8,
                                    source.getMinX(), source.getMinZ(), source.getMaxX(), source.getMaxZ());
                });
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        }
        positions.put(key, loc);
        return loc;
    }

    private void acceptedHistory(PlayerLocation to, double dy, double horizontal) {
        final PlayerLocation from = position(to.getX() - horizontal, to.getY() - dy, false);
        final PlayerMoveData last = data.playerMoves.getFirstPastMove();
        last.set(from, to);
        last.setExtraProperties(from, to);
        last.flyCheck = CheckType.MOVING_SURVIVALFLY;
        last.hAllowedDistanceBase = 0.2873 * (speedTwoHistory ? 1.4 : 1.0);
        last.hAllowedDistance = Math.max(last.hAllowedDistanceBase, horizontal);
        data.lastFrictionHorizontal = 0.91;
        data.lastFrictionVertical = 0.98;
        data.sfJumpPhase = 11;
    }
}
