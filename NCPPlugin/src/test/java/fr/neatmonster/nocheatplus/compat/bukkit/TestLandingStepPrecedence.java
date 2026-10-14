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
public class TestLandingStepPrecedence {
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
    private double speedFactor = 1.0;
    private String frictionHistory = "normal";
    private final Map<String, PlayerLocation> positions = new HashMap<>();

    @Before
    public void setup() throws Exception {
        StaticLog.setUseLogManager(false);
        BlockTests.initBlockProperties();
        StaticLog.setUseLogManager(true);
        // Match production carpet flags, which the minimal block test bootstrap does not install.
        BlockFlags.setBlockFlags(Material.WHITE_CARPET, BlockFlags.F_GROUND | BlockFlags.F_CARPET);
        tracker = new fr.neatmonster.nocheatplus.compat.blocks.changetracker.BlockChangeTracker();
        final PluginTests.UnitTestNoCheatPlusAPI api = new PluginTests.UnitTestNoCheatPlusAPI() {
            @Override public fr.neatmonster.nocheatplus.compat.blocks.changetracker.BlockChangeTracker getBlockChangeTracker() { return tracker; }
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
        when(world.getUID()).thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000001"));
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

    private boolean blockHistory;
    private fr.neatmonster.nocheatplus.compat.blocks.changetracker.BlockChangeTracker tracker;

    @Test public void pastStatePrecedence() {
        fall(); boat(1.100001, 10.5625, false);
        PlayerLocation from = position(0.8, 10.13, false), to = position(1.14, 10.5625, true);
        FakeBlockCache previous = new FakeBlockCache();
        previous.set(0, 10, 0, Material.SNOW, 2, new double[] {0, 0, 0, 1, 0.125, 1});
        tracker.addBlockChange(world.getUID(), 0, 10, 0, previous.getOrCreateBlockCacheNode(0, 10, 0, true));
        final int[] queries = {0};
        when(from.isOnGroundOpportune(anyDouble(), anyLong(), any(), any(), anyInt())).thenAnswer(inv -> {
                queries[0]++;
                return tracker.isOnGround(blocks, inv.getArgument(3), inv.getArgument(4), world.getUID(),
                        from.getMinX(), from.getY() - (double) inv.getArgument(0), from.getMinZ(),
                        from.getMaxX(), from.getMaxY(), from.getMaxZ(), inv.getArgument(1));
        });
        acceptedHistory(from, -0.374, 0.25);
        data.multSprinting = 1.3; data.timeSprinting = 100000L; blockHistory = true;
        Location result = move(from, to);
        PlayerMoveData m = data.playerMoves.getCurrentMove();
        System.out.printf(Locale.ROOT, "R4 PAST base=%s accept=%s landing=%s bunny=%s allowedH=%.6f setback=%.6f%n",
                base, result == null, landingStep(m), m.bunnyHop, m.hAllowedDistance, data.getSetBackY());
        assertNull("historical ground keeps base acceptance", result);
        assertFalse(landingStep(m));
        assertTrue(m.bunnyHop);
        assertEquals("block history queried once", 1, queries[0]);
    }

    @Test public void queuedVelocityPrecedence() {
        fall(); boat(1.100001, 10.5625, false);
        PlayerLocation from = position(0.8, 10.05, false), to = position(1.0, 10.5625, true);
        acceptedHistory(from, -0.374, 0.2);
        data.addVerticalVelocity(new fr.neatmonster.nocheatplus.checks.moving.velocity.SimpleEntry(0.5125, 20));
        Location result = move(from, to);
        PlayerMoveData m = data.playerMoves.getCurrentMove();
        boolean remaining = data.peekVerticalVelocity(0.5125, 0, 100) != null;
        System.out.printf(Locale.ROOT, "R4 VELOCITY base=%s accept=%s landing=%s used=%s matchingAfter=%s%n",
                base, result == null, landingStep(m), m.verVelUsed != null, remaining);
        assertNull("queued velocity supplies the base landing", result);
        assertFalse(landingStep(m));
        assertNotNull(m.verVelUsed);
        assertFalse(remaining);
        data.playerMoves.finishCurrentMove();
        Location next = move(to, position(1.2, 11.075, false));
        System.out.printf(Locale.ROOT, "R4 VELOCITY NEXT base=%s accept=%s used=%s%n",
                base, next == null, data.playerMoves.getCurrentMove().verVelUsed != null);
        assertNotNull("base rejects the next rise after consuming the velocity", next);
    }

    /** Course-relative samples from the halfstairs probe, including the +0.6 and following jump arc.
     * The trace samples once per tick, so it does not capture every injected position packet. */
    @Test public void labBoatHalfstairsJumpVclip() {
        labCourse(true);
        replayLab("halfstairs", "AARARRRRA", new double[][] {
            // Recorded course sample: boat descent, +0.6 clip, then held-jump ascent.
            {3.71704, 1.85154, 0}, {3.79891, 1.5625, 1}, {3.97142, 1.5625, 1},
            {4.16361, 2.1625, 0}, {4.36655, 2.5825, 0}, {4.49695, 2.9157, 0},
            {4.63522, 3.16384, 0}, {4.78064, 3.32861, 0}, {5.09043, 3.4147, 0},
            {5.25369, 3.33926, 0}
        });
    }

    /** Course-relative +0.1, short fall, slab/stair endpoint and +0.6 samples. */
    @Test public void labSlabBoatStairsTinyFallVclip() {
        labCourse(false);
        replayLab("slabstairs", "RRAAAAARAAARARAAAAAA", new double[][] {
            // Recorded course sample: tiny fall into a block-top step, not a boat landing.
            {2.21546, 1.1, 0}, {2.42910, 1.0216, 0}, {2.78733, 1.5, 1},
            {3.00654, 1.5, 1}, {3.10454, 1.5, 1}, {3.34217, 1.5, 1},
            {3.53000, 1.5625, 1}, {3.73056, 1.5625, 1}, {4.14936, 1.5625, 1},
            {4.36273, 1.6625, 0}, {4.57723, 1.5841, 1}, {4.93636, 1.5625, 1},
            {5.15580, 2.1625, 0}, {5.37361, 2.0841, 0}, {5.65779, 1.69834, 0},
            {5.70000, 1.39402, 0}, {5.70000, 1.01739, 0}, {5.71960, 1.5, 1},
            // Recorded course sample: tiny fall into the upper stair top.
            {5.99668, 1.6, 0}, {6.18272, 1.5216, 1}, {6.30390, 2.0, 1}
        });
    }

    private void labCourse(boolean halfstairs) {
        resetData(1.0);
        blocks.fill(-2, 0, -2, 10, 0, 2, Material.STONE);
        boat(halfstairs ? 2.8125 : 3.8125, 1.5625, false);
        if (halfstairs) {
            for (int x = 5; x <= 8; x++) {
                blocks.fill(x, 1, 0, x, x - 4, 0, Material.STONE);
                blocks.set(x, x - 3, 0, Material.STONE_SLAB, new double[] {0, 0, 0, 1, 0.5, 1});
            }
        } else {
            blocks.set(3, 1, 0, Material.STONE_SLAB, new double[] {0, 0, 0, 1, 0.5, 1});
            blocks.set(6, 1, 0, Material.STONE_STAIRS,
                    new double[] {0, 0, 0, 1, 0.5, 1, 0.5, 0.5, 0, 1, 1, 1});
            blocks.set(7, 1, 0, Material.STONE);
        }
    }

    private void replayLab(String label, String expected, double[][] samples) {
        PlayerLocation from = position(samples[0][0], samples[0][1], samples[0][2] != 0);
        acceptedHistory(from, -0.0784, 0.2);
        final StringBuilder signature = new StringBuilder();
        for (int i = 1; i < samples.length; i++) {
            PlayerLocation to = position(samples[i][0], samples[i][1], samples[i][2] != 0);
            Location correction = move(from, to);
            PlayerMoveData m = data.playerMoves.getCurrentMove();
            assertFalse("sampled probe move " + i + " must stay on the base path", landingStep(m));
            signature.append(correction == null ? 'A' : 'R');
            System.out.printf(Locale.ROOT, "R4 LAB %s move=%d accept=%s landing=%s bunny=%s allowedH=%.6f anchor=%.6f%n",
                    label, i, correction == null, landingStep(m), m.bunnyHop, m.hAllowedDistance, data.getSetBackY());
            if (correction == null) data.playerMoves.finishCurrentMove();
            else {
                PlayerLocation anchor = position(correction.getX(), correction.getY(), true);
                data.prepareSetBack(correction);
                data.resetPlayerPositions(anchor);
                data.onSetBack(anchor);
            }
            from = to;
        }
        assertEquals("same acceptance sequence as the unchanged base jar", expected, signature.toString());
        System.out.println("R4 LAB signature " + label + "=" + signature);
    }

    @Test public void horizontalOverlapCannotBeASecondBoatStep() {
        fall(); boat(0.5, 10.5625, false);
        PlayerLocation from = position(0.8, 10.1215, false), to = position(1.0, 10.5625, true);
        acceptedHistory(from, -0.374, 0.2);
        assertNotNull("inside the footprint cannot trigger a vanilla side step", move(from, to));
        assertFalse(landingStep(data.playerMoves.getCurrentMove()));
    }

    @Test public void boatTopCannotExceedItsHeightAboveSourceSupport() {
        fall(); boat(1.100001, 10.59, false);
        PlayerLocation from = position(0.8, 10.1215, false), to = position(1.0, 10.59, true);
        acceptedHistory(from, -0.374, 0.2);
        assertNotNull("a raised boat does not widen the source-to-top budget", move(from, to));
        assertFalse(landingStep(data.playerMoves.getCurrentMove()));
    }

    @Test public void velocityPeekDoesNotSkipLeadingEntriesOrConsumeBounceCredit() {
        fall(); boat(1.100001, 10.5625, false);
        PlayerLocation from = position(0.8, 10.05, false), to = position(1.0, 10.5625, true);
        acceptedHistory(from, -0.374, 0.2);
        data.addVerticalVelocity(new fr.neatmonster.nocheatplus.checks.moving.velocity.SimpleEntry(0, 20));
        data.addVerticalVelocity(new fr.neatmonster.nocheatplus.checks.moving.velocity.SimpleEntry(
                0, 0.5125, fr.neatmonster.nocheatplus.checks.moving.velocity.VelocityFlags.ORIGIN_BLOCK_BOUNCE, 20));
        assertNull(move(from, to));
        PlayerMoveData m = data.playerMoves.getCurrentMove();
        assertFalse(landingStep(m));
        assertNotNull("base consumes the matching bounce entry", m.verVelUsed);
        assertFalse(data.hasQueuedVerVel());
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
                data.getPlayerMoveCount(), 1000L + data.getPlayerMoveCount() * 50L, blockHistory);
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
        when(loc.getyOnGround()).thenReturn(0.016);
        when(loc.getBoxMarginHorizontal()).thenReturn(0.3);
        when(loc.isOnGround(anyDouble())).thenAnswer(inv -> {
            double margin = inv.getArgument(0);
            return BlockProperties.isOnGround(blocks, x - 0.3, y - margin, 0.2, x + 0.3, y, 0.8);
        });
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
                // The prior candidate jar has only the top query; permit direct regression comparisons.
                if (java.util.Arrays.stream(PlayerLocation.class.getMethods())
                        .anyMatch(method -> method.getName().equals("stepsOnEntityTop"))) {
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
                }
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
        last.hAllowedDistanceBase = 0.2873 * speedFactor;
        last.hAllowedDistance = Math.max(last.hAllowedDistanceBase, horizontal);
        if (!frictionHistory.equals("normal")) {
            PlayerMoveData groundHistory = data.playerMoves.getSecondPastMove();
            groundHistory.set(position(0.2, 10.0, true), position(0.4, 10.0, true));
            groundHistory.setExtraProperties(position(0.2, 10.0, true), position(0.4, 10.0, true));
            groundHistory.from.onIce = groundHistory.to.onIce = frictionHistory.equals("ice");
            groundHistory.from.onSoulSand = groundHistory.to.onSoulSand = frictionHistory.equals("soul");
            groundHistory.hAllowedDistanceBase = 0.2873 * speedFactor;
        }
        data.lastFrictionHorizontal = 0.91;
        data.lastFrictionVertical = 0.98;
        data.sfJumpPhase = 11;
    }
}
