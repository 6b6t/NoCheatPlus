package fr.neatmonster.nocheatplus;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.UUID;
import java.util.function.Predicate;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.VoxelShape;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

import fr.neatmonster.nocheatplus.checks.CheckType;
import fr.neatmonster.nocheatplus.checks.combined.CombinedData;
import fr.neatmonster.nocheatplus.checks.moving.MovingConfig;
import fr.neatmonster.nocheatplus.checks.moving.MovingData;
import fr.neatmonster.nocheatplus.checks.moving.MovingListener;
import fr.neatmonster.nocheatplus.checks.moving.location.tracking.LocationTrace.TraceEntryPool;
import fr.neatmonster.nocheatplus.checks.moving.util.AuxMoving;
import fr.neatmonster.nocheatplus.checks.moving.util.MovingUtil;
import fr.neatmonster.nocheatplus.checks.moving.util.ShulkerLidRide;
import fr.neatmonster.nocheatplus.checks.workaround.WRPT;
import fr.neatmonster.nocheatplus.compat.Folia;
import fr.neatmonster.nocheatplus.logging.LogManager;
import fr.neatmonster.nocheatplus.players.DataManager;
import fr.neatmonster.nocheatplus.players.IPlayerData;

/**
 * Untracked-move poll vs the server's own push by an opening upward shulker lid. The box is at block (0, 0, 0)
 * (top face y = 1), the player stands on it (feet 1.0, centre 0.5 / 0.5) unless a test says otherwise.
 */
public class TestShulkerLidRide {
    private Player player;
    private World world;
    private MovingData data;
    private IPlayerData pData;
    private MovingListener listener;
    private Method poll;
    private Location lastTarget;
    private Block box;
    private Predicate<Location> owned = l -> true;

    @Before
    public void setup() throws Exception {
        final LogManager logs = mock(LogManager.class);
        final PluginTests.UnitTestNoCheatPlusAPI api = new PluginTests.UnitTestNoCheatPlusAPI() {
            @Override public LogManager getLogManager() { return logs; }
        };
        NCPAPIProvider.setNoCheatPlusAPI(api);
        api.registerGenericInstance(WRPT.class, new WRPT());
        api.registerGenericInstance(TraceEntryPool.class, new TraceEntryPool(100));
        api.registerGenericInstance(AuxMoving.class, mock(AuxMoving.class));
        final MovingConfig config;
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getVersion).thenReturn("Folia (MC: 26.2)");
            config = new MovingConfig(api.getWorldDataManager().getWorldData(UUID.randomUUID().toString()));
        }
        pData = mock(IPlayerData.class);
        when(pData.getGenericInstance(MovingConfig.class)).thenReturn(config);
        when(pData.getGenericInstance(CombinedData.class)).thenReturn(new CombinedData());
        data = new MovingData(config, pData);
        when(pData.getGenericInstance(MovingData.class)).thenReturn(data);
        player = mock(Player.class);
        when(player.isOnline()).thenReturn(true);
        world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(world.getName()).thenReturn("world");
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        final Block air = mock(Block.class);
        when(air.getType()).thenReturn(Material.AIR);
        final VoxelShape empty = mock(VoxelShape.class);
        when(empty.getBoundingBoxes()).thenReturn(Collections.emptyList());
        when(air.getCollisionShape()).thenReturn(empty);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(air);
        when(player.getWorld()).thenReturn(world);
        when(pData.isCheckActive(CheckType.MOVING, player)).thenReturn(true);
        listener = mock(MovingListener.class, CALLS_REAL_METHODS);
        final Field supported = MovingListener.class.getDeclaredField("teleportCompletionSupported");
        supported.setAccessible(true);
        supported.setBoolean(listener, true);
        poll = MovingListener.class.getDeclaredMethod("checkUntrackedMove", Player.class);
        poll.setAccessible(true);
        box = mock(Block.class);
        final Directional up = mock(Directional.class);
        when(up.getFacing()).thenReturn(BlockFace.UP);
        when(box.getType()).thenReturn(Material.SHULKER_BOX);
        when(box.getBlockData()).thenReturn(up);
        when(world.getBlockAt(0, 0, 0)).thenReturn(box);
        lid(0.0F);
    }

    /** The box's server shape for lid progress p: out by 0.5 * p (float, as vanilla). */
    private void lid(final float progress) {
        lidFor(BlockFace.UP, progress);
    }

    /** The server shape of the box facing {@code face}: the block expanded towards the facing by 0.5 * p. */
    private void lidFor(final BlockFace face, final float progress) {
        final BoundingBox shape = new BoundingBox(0, 0, 0, 1, 1, 1)
                .expand(face.getModX(), face.getModY(), face.getModZ(), (double) (0.5F * progress));
        when(box.getBoundingBox()).thenReturn(shape.clone());
        // Its collision shape (relative to the block, as Bukkit's): the same box plus lid.
        final VoxelShape collision = mock(VoxelShape.class);
        when(collision.getBoundingBoxes()).thenReturn(Collections.singletonList(shape.clone()));
        when(box.getCollisionShape()).thenReturn(collision);
    }

    private void facing(final BlockFace face) {
        final Directional directional = mock(Directional.class);
        when(directional.getFacing()).thenReturn(face);
        when(box.getBlockData()).thenReturn(directional);
    }

    /** A block with a collision box (relative to its cell) at x, y, z. */
    private void solid(final int x, final int y, final int z, final BoundingBox shape) {
        final Block block = mock(Block.class);
        final VoxelShape voxel = mock(VoxelShape.class);
        when(voxel.getBoundingBoxes()).thenReturn(Collections.singletonList(shape));
        when(block.getCollisionShape()).thenReturn(voxel);
        when(block.getType()).thenReturn(Material.STONE);
        when(world.getBlockAt(x, y, z)).thenReturn(block);
    }

    /** Lid progress after n opening ticks from 0, with vanilla's float steps. */
    private static float progressAfter(final int ticks) {
        float p = 0.0F;
        for (int i = 0; i < ticks; i++) {
            p += 0.1F;
            if (p >= 1.0F) p = 1.0F;
        }
        return p;
    }

    private <T> T withFolia(final java.util.concurrent.Callable<T> run) throws Exception {
        try (MockedStatic<Folia> folia = mockStatic(Folia.class)) {
            folia.when(Folia::isFoliaServer).thenReturn(true);
            folia.when(() -> Folia.isOwnedByCurrentRegion(any(Location.class)))
                 .thenAnswer(call -> owned.test(call.getArgument(0)));
            return run.call();
        }
    }

    /** The server opens the box for the player (InventoryOpenEvent, MONITOR). */
    private void open(final Location at) throws Exception {
        final Inventory inventory = mock(Inventory.class);
        when(inventory.getType()).thenReturn(InventoryType.SHULKER_BOX);
        when(inventory.getLocation()).thenReturn(at);
        final InventoryOpenEvent event = mock(InventoryOpenEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getInventory()).thenReturn(inventory);
        withFolia(() -> {
            try (MockedStatic<DataManager> manager = mockStatic(DataManager.class)) {
                manager.when(() -> DataManager.getGenericInstance(player, MovingData.class)).thenReturn(data);
                listener.onInventoryOpenShulkerLid(event);
            }
            return null;
        });
    }

    private void openBox() throws Exception {
        open(new Location(world, 0, 0, 0));
    }

    private void trustedAt(final double x, final double y, final double z) {
        data.resetUntrackedPosition(new Location(world, x, y, z));
    }

    private void serverAt(final double x, final double y, final double z) {
        when(player.getLocation()).thenReturn(new Location(world, x, y, z, 12f, 70f));
        when(player.getBoundingBox()).thenReturn(new BoundingBox(x - 0.3, y, z - 0.3, x + 0.3, y + 1.8, z + 0.3));
    }

    /** Run the poll, return how many set backs it requested. */
    private int pollSetBacks() throws Exception {
        return withFolia(() -> {
            try (MockedStatic<DataManager> manager = mockStatic(DataManager.class);
                 MockedStatic<MovingUtil> moving = mockStatic(MovingUtil.class, CALLS_REAL_METHODS)) {
                manager.when(() -> DataManager.getPlayerData(player)).thenReturn(pData);
                final int[] count = {0};
                moving.when(() -> MovingUtil.teleportSetBack(any(), any(), any())).thenAnswer(call -> {
                    count[0]++;
                    lastTarget = call.getArgument(1);
                    return null;
                });
                poll.invoke(listener, player);
                return count[0];
            }
        });
    }

    private void assertCorrectedTo(final double y) throws Exception {
        assertEquals(1, pollSetBacks());
        assertEquals(y, lastTarget.getY(), 0.0);
        assertEquals(y, data.untrackedTrustedY, 0.0);
    }

    private void assertNotCorrected(final double trustedY) throws Exception {
        assertEquals(0, pollSetBacks());
        assertEquals(trustedY, data.untrackedTrustedY, 0.0);
    }

    // ---------------------------------------------------------------- pure vanilla model

    @Test
    public void rideReplaysVanillaSweepForAPlayerOnTheBox() {
        float progress = 0.0F;
        double y = 0.0;
        for (int tick = 1; tick <= 10; tick++) {
            final float old = progress;
            progress = Math.min(1.0F, progress + 0.1F);
            if (y < progress && y + 1.8 > old) y += (double) progress - old + 0.01;
            assertEquals(y, ShulkerLidRide.vanillaRide(0.0F, tick, 0.0, 1.8), 1e-12);
            assertEquals(0.11 * tick, y, 1e-6);
        }
        // More ticks than an opening has change nothing.
        assertEquals(1.10, ShulkerLidRide.vanillaRide(0.0F, 25, 0.0, 1.8), 1e-6);
    }

    @Test
    public void ridePushesOnlyOnceTheSlabReachesTheFeet() {
        assertEquals(0.55, ShulkerLidRide.vanillaRide(0.0F, 10, 0.5, 1.8), 1e-6);
        assertEquals(0.0, ShulkerLidRide.vanillaRide(0.0F, 5, 0.5, 1.8), 0.0);
        assertEquals(0.0, ShulkerLidRide.vanillaRide(0.0F, 10, 1.05, 1.8), 0.0);
        // Reopening a half closed lid: only the remaining ticks push.
        assertEquals(0.55, ShulkerLidRide.vanillaRide(0.5F, 10, 0.5, 1.8), 1e-6);
    }

    @Test
    public void openingTicksFollowVanillaFloatSteps() {
        for (int n = 1; n <= 10; n++) {
            assertEquals(n, ShulkerLidRide.openingTicks(0.0F, progressAfter(n)));
        }
        assertEquals(7, ShulkerLidRide.openingTicks(0.3F, 1.0F));
        assertEquals(0, ShulkerLidRide.openingTicks(0.0F, 0.0F));
        assertEquals(0, ShulkerLidRide.openingTicks(1.0F, 1.0F));
        assertEquals(0, ShulkerLidRide.openingTicks(0.6F, 0.4F)); // closing
        assertEquals(0, ShulkerLidRide.openingTicks(0.0F, 0.15F)); // not an opening step
        assertEquals(0, ShulkerLidRide.openingTicks(Float.NaN, 0.5F));
        assertEquals(0, ShulkerLidRide.openingTicks(0.0F, Float.NaN));
    }

    @Test
    public void pushPathIsASegmentWithThePollMarginAndAHardCap() {
        assertTrue(ShulkerLidRide.onPushPath(0.5, 1.0, 0.5, 0.5, 1.33, 0.5, 0.33, 0.3F, 1.0));
        assertTrue(ShulkerLidRide.onPushPath(0.5, 1.0, 0.5, 0.55, 1.40, 0.5, 0.33, 0.3F, 1.0));
        assertFalse(ShulkerLidRide.onPushPath(0.5, 1.0, 0.5, 0.5, 1.44, 0.5, 0.33, 0.3F, 1.0));
        assertFalse(ShulkerLidRide.onPushPath(0.5, 1.0, 0.5, 0.62, 1.33, 0.5, 0.33, 0.3F, 1.0));
        // Hard cap: never above the slab's top plus one push (lid 0.3 -> 0.41 above the face).
        assertFalse(ShulkerLidRide.onPushPath(0.5, 1.2, 0.5, 0.5, 1.45, 0.5, 0.33, 0.3F, 1.0));
        // Downward: only the poll's own margin.
        assertTrue(ShulkerLidRide.onPushPath(0.5, 1.0, 0.5, 0.5, 0.91, 0.5, 0.33, 0.3F, 1.0));
        assertFalse(ShulkerLidRide.onPushPath(0.5, 1.0, 0.5, 0.5, 0.89, 0.5, 0.33, 0.3F, 1.0));
    }

    // ---------------------------------------------------------------- legit: the poll leaves vanilla's push alone

    @Test
    public void standingOnTheBoxWhileItOpensIsNotPulledBack() throws Exception {
        trustedAt(0.5, 1.0, 0.5);
        openBox();
        assertTrue(data.shulkerLidOpenValid);
        for (int tick = 1; tick <= 10; tick++) {
            lid(progressAfter(tick));
            // Server pushes since the client's last packet: 1..5 (the poll runs every 5 ticks).
            for (int pushes = 1; pushes <= Math.min(tick, 5); pushes++) {
                serverAt(0.5, 1.0 + ShulkerLidRide.vanillaRide(0.0F, pushes, 0.0, 1.8), 0.5);
                assertNotCorrected(1.0);
            }
        }
        // All ten pushes without a client packet.
        serverAt(0.5, 1.0 + ShulkerLidRide.vanillaRide(0.0F, 10, 0.0, 1.8), 0.5);
        assertNotCorrected(1.0);
    }

    @Test
    public void clientRidingTheLidPlusServerPushesIsNotPulledBack() throws Exception {
        trustedAt(0.5, 1.0, 0.5);
        openBox();
        // Client stands on the physical lid (out 0.25 at tick 5), checked by a move event; then two server pushes.
        lid(progressAfter(5));
        trustedAt(0.5, 1.25, 0.5);
        serverAt(0.5, 1.25 + 0.22, 0.5);
        assertNotCorrected(1.25);
        // A sub-1/16 client offset from the trusted position on top of three pushes.
        lid(progressAfter(8));
        serverAt(0.53, 1.25 + 0.05 + 0.33, 0.5);
        assertNotCorrected(1.25);
    }

    @Test
    public void standingOnTheOpenLidAfterThePushTakesTheLidTopHeightOnly() throws Exception {
        trustedAt(0.5, 1.0, 0.5);
        openBox();
        lid(1.0F);
        // The server's move-rejection teleports left no move event: the player rests on the open lid (+0.5).
        serverAt(0.53, 1.5, 0.48);
        assertEquals(0, pollSetBacks());
        assertEquals(1.5, data.untrackedTrustedY, 0.0);
        assertEquals(0.5, data.untrackedTrustedX, 0.0);
        assertEquals(0.5, data.untrackedTrustedZ, 0.0);
        // Later, with the opening long over, standing there stays fine; leaving it unchecked does not.
        data.shulkerLidOpenTick -= ShulkerLidRide.WINDOW_TICKS + 1;
        assertNotCorrected(1.5);
        serverAt(0.5, 1.62, 0.5);
        assertCorrectedTo(1.5);
    }

    @Test
    public void onlyExactlyTheLidTopIsTaken() throws Exception {
        trustedAt(0.5, 1.0, 0.5);
        openBox();
        lid(1.0F);
        serverAt(0.5, 1.52, 0.5); // pushed above the lid: allowed, but not taken
        assertNotCorrected(1.0);
        lid(progressAfter(6));
        serverAt(0.5, 1.3, 0.5); // on a partly open lid (0.3 out): taken
        assertEquals(0, pollSetBacks());
        assertEquals(1.3, data.untrackedTrustedY, 1e-6);
    }

    // ---------------------------------------------------------------- nothing changes without the server's evidence

    @Test
    public void withoutAnOpeningNoLidShapeAuthorizesMovement() throws Exception {
        // REVIEW HIGH 1 shapes: static open lid, inside the lid, above its face, closing geometry.
        for (float progress : new float[] {1.0F, 0.6F}) {
            lid(progress);
            for (double rise : new double[] {0.33, 0.20, 0.59, 0.11}) {
                trustedAt(0.5, 1.0, 0.5);
                serverAt(0.5, 1.0 + rise, 0.5);
                assertCorrectedTo(1.0);
            }
        }
    }

    @Test
    public void repeatedDriftsBesideAStaticOpenLidNeverAdvanceTheTrustedPosition() throws Exception {
        lid(1.0F);
        trustedAt(0.5, 1.0, 0.5);
        openBox(); // Already fully open (someone else holds it): no push follows, nothing recorded.
        assertFalse(data.shulkerLidOpenValid);
        for (int i = 1; i <= 5; i++) {
            serverAt(0.5, 1.0 + i * 0.11, 0.5);
            assertCorrectedTo(1.0);
        }
    }

    @Test
    public void moreThanVanillasPushIsCorrected() throws Exception {
        trustedAt(0.5, 1.0, 0.5);
        openBox();
        lid(progressAfter(3));
        serverAt(0.5, 1.33, 0.5);
        assertNotCorrected(1.0);
        serverAt(0.5, 1.45, 0.5); // 0.12 above three pushes
        assertCorrectedTo(1.0);
        serverAt(0.62, 1.33, 0.5); // sideways off the push axis
        assertCorrectedTo(1.0);
        serverAt(0.5, 1.33, 0.5);
        lid(progressAfter(0));
        assertCorrectedTo(1.0); // lid not out: no opening tick ran
    }

    @Test
    public void lidThatStartedClosingGivesNothing() throws Exception {
        trustedAt(0.5, 1.0, 0.5);
        lid(0.6F);
        openBox();
        assertTrue(data.shulkerLidOpenValid);
        lid(0.4F);
        serverAt(0.5, 1.22, 0.5);
        assertCorrectedTo(1.0);
    }

    @Test
    public void besideTheBoxOrAboveTheSweepGetsNothing() throws Exception {
        openBox();
        lid(progressAfter(5));
        // Beside the box: the body doesn't overlap its footprint (half width 0.3).
        trustedAt(1.31, 1.0, 0.5);
        serverAt(1.31, 1.33, 0.5);
        assertCorrectedTo(1.0);
        // Above everything the slab reached so far.
        trustedAt(0.5, 1.6, 0.5);
        serverAt(0.5, 1.82, 0.5);
        assertCorrectedTo(1.6);
    }

    @Test
    public void openingExpiresAfterTheWindow() throws Exception {
        trustedAt(0.5, 1.0, 0.5);
        openBox();
        lid(1.0F);
        data.shulkerLidOpenTick -= ShulkerLidRide.WINDOW_TICKS + 1;
        serverAt(0.5, 1.33, 0.5);
        assertCorrectedTo(1.0);
    }

    @Test
    public void otherBoxesWorldsAndFacingsGetNothing() throws Exception {
        trustedAt(0.5, 1.0, 0.5);
        // A virtual shulker inventory (no block).
        open(null);
        assertFalse(data.shulkerLidOpenValid);
        // A sideways box is recorded, but standing on top of it gets nothing: its lid face is the north side, and a
        // body above the top face doesn't overlap it.
        facing(BlockFace.NORTH);
        lidFor(BlockFace.NORTH, 0.0F);
        openBox();
        assertTrue(data.shulkerLidOpenValid);
        assertEquals(BlockFace.NORTH, data.shulkerLidOpenFace);
        lidFor(BlockFace.NORTH, progressAfter(3));
        serverAt(0.5, 1.33, 0.5);
        assertCorrectedTo(1.0);
        serverAt(0.5, 1.0, 0.17);
        assertCorrectedTo(1.0);
        assertEquals(0.5, lastTarget.getZ(), 0.0);
        // A north box whose shape reaches up (not its lid): no opening.
        lidFor(BlockFace.UP, 0.4F);
        openBox();
        assertFalse(data.shulkerLidOpenValid);
        // The upward box, then it turns into something else.
        facing(BlockFace.UP);
        lid(0.0F);
        openBox();
        assertTrue(data.shulkerLidOpenValid);
        lid(progressAfter(3));
        when(box.getType()).thenReturn(Material.STONE);
        serverAt(0.5, 1.33, 0.5);
        assertCorrectedTo(1.0);
    }

    @Test
    public void aBlockAboveTheHeadStopsTheAllowance() throws Exception {
        trustedAt(0.5, 1.0, 0.5);
        openBox();
        lid(progressAfter(3));
        final Block ceiling = mock(Block.class);
        final VoxelShape full = mock(VoxelShape.class);
        when(full.getBoundingBoxes()).thenReturn(Collections.singletonList(new BoundingBox(0, 0, 0, 1, 1, 1)));
        when(ceiling.getCollisionShape()).thenReturn(full);
        when(world.getBlockAt(0, 3, 0)).thenReturn(ceiling);
        // Head 2.8 -> 3.13 rises into the block at y 3: vanilla's push would have stopped.
        serverAt(0.5, 1.33, 0.5);
        assertCorrectedTo(1.0);
        // Up to its face is fine.
        serverAt(0.5, 1.2, 0.5);
        assertNotCorrected(1.0);
    }

    @Test
    public void foreignOrUnloadedChunksAreNeverRead() throws Exception {
        // Scale-16 sized player over the box: its head region crosses into chunk x = -1.
        trustedAt(0.5, 1.0, 0.5);
        openBox();
        lid(progressAfter(3));
        when(player.getLocation()).thenReturn(new Location(world, 0.5, 1.33, 0.5, 0f, 70f));
        when(player.getBoundingBox()).thenReturn(new BoundingBox(-4.3, 1.33, -4.3, 5.3, 30.13, 5.3));
        for (boolean loaded : new boolean[] {false, true}) {
            for (boolean own : new boolean[] {false, true}) {
                if (loaded && own) continue;
                when(world.isChunkLoaded(anyInt(), anyInt())).thenAnswer(call -> (int) call.getArgument(0) == 0 || loaded);
                owned = l -> (l.getBlockX() >> 4) == 0 || own;
                clearInvocations(world);
                assertCorrectedTo(1.0);
                verify(world, never()).getBlockAt(intThat(x -> x < 0), anyInt(), anyInt());
            }
        }
        // Box chunk itself foreign: no read of the box either.
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        owned = l -> false;
        clearInvocations(world);
        assertCorrectedTo(1.0);
        verify(world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
    }

    @Test
    public void untouchedPollToleranceAndCheckedPositionsStayAsLive() throws Exception {
        trustedAt(0.5, 1.0, 0.5);
        serverAt(0.5, 1.09, 0.5);
        assertNotCorrected(1.0);
        serverAt(0.5, 1.11, 0.5);
        assertCorrectedTo(1.0);
    }

    // ---------------------------------------------------------------- sideways and downward lids

    /** Box facing north (lid face z = 0), the player stands north of it, body touching the face, feet at y 0. */
    private void northBoxWithPlayerInFront() throws Exception {
        facing(BlockFace.NORTH);
        lidFor(BlockFace.NORTH, 0.0F);
        trustedAt(0.5, 0.0, -0.301);
        openBox();
        assertTrue(data.shulkerLidOpenValid);
        assertEquals(BlockFace.NORTH, data.shulkerLidOpenFace);
    }

    @Test
    public void sidewaysRideReplaysVanillaSweep() {
        // Body 0.6 deep, near side 0.001 from the lid face: pushed every tick, 0.11 each.
        for (int tick = 1; tick <= 10; tick++) {
            assertEquals(0.11 * tick, ShulkerLidRide.vanillaRide(0.0F, tick, 0.001, 0.6), 1e-6);
        }
        // Standing 0.5 away: the slab reaches the body at the sixth tick.
        assertEquals(0.0, ShulkerLidRide.vanillaRide(0.0F, 5, 0.5, 0.6), 0.0);
        assertEquals(0.55, ShulkerLidRide.vanillaRide(0.0F, 10, 0.5, 0.6), 1e-6);
        // More than the lid's reach away: nothing.
        assertEquals(0.0, ShulkerLidRide.vanillaRide(0.0F, 10, 1.0, 0.6), 0.0);
        assertEquals(0, ShulkerLidRide.axis(BlockFace.EAST));
        assertEquals(1, ShulkerLidRide.axis(BlockFace.DOWN));
        assertEquals(2, ShulkerLidRide.axis(BlockFace.NORTH));
        assertEquals(-1, ShulkerLidRide.sign(BlockFace.NORTH));
        assertEquals(1, ShulkerLidRide.sign(BlockFace.SOUTH));
        assertEquals(-1, ShulkerLidRide.sign(BlockFace.DOWN));
    }

    @Test
    public void standingInFrontOfASidewaysBoxWhileItOpensIsNotPulledBack() throws Exception {
        northBoxWithPlayerInFront();
        for (int tick = 1; tick <= 10; tick++) {
            lidFor(BlockFace.NORTH, progressAfter(tick));
            for (int pushes = 1; pushes <= Math.min(tick, 5); pushes++) {
                trustedAt(0.5, 0.0, -0.301);
                serverAt(0.5, 0.0, -0.301 - ShulkerLidRide.vanillaRide(0.0F, pushes, 0.001, 0.6));
                assertEquals("tick " + tick + " pushes " + pushes, 0, pollSetBacks());
            }
        }
    }

    @Test
    public void sidewaysPushTakesThePushedPositionAlongTheFacingOnly() throws Exception {
        northBoxWithPlayerInFront();
        lidFor(BlockFace.NORTH, 1.0F);
        // All ten pushes, no move event (the player is in the inventory).
        serverAt(0.5, 0.0, -1.401);
        assertEquals(0, pollSetBacks());
        assertEquals(-1.401, data.untrackedTrustedZ, 1e-9);
        assertEquals(0.5, data.untrackedTrustedX, 0.0);
        assertEquals(0.0, data.untrackedTrustedY, 0.0);
        // Long after the opening, standing there stays fine; moving on unchecked does not.
        data.shulkerLidOpenTick -= ShulkerLidRide.WINDOW_TICKS + 1;
        assertEquals(0, pollSetBacks());
        serverAt(0.5, 0.0, -1.52);
        assertEquals(1, pollSetBacks());
        assertEquals(-1.401, lastTarget.getZ(), 1e-9);
    }

    @Test
    public void sidewaysOffAxisIsLeftAloneButNeverTaken() throws Exception {
        northBoxWithPlayerInFront();
        lidFor(BlockFace.NORTH, 1.0F);
        // A client offset within the poll's margin on top of the push: not corrected during the opening ...
        serverAt(0.56, 0.0, -1.401);
        assertEquals(0, pollSetBacks());
        assertEquals(-0.301, data.untrackedTrustedZ, 0.0);
        serverAt(0.5, 0.05, -1.401);
        assertEquals(0, pollSetBacks());
        assertEquals(-0.301, data.untrackedTrustedZ, 0.0);
        // ... and corrected to the old trusted position afterwards, as live.
        data.shulkerLidOpenTick -= ShulkerLidRide.WINDOW_TICKS + 1;
        assertEquals(1, pollSetBacks());
        assertEquals(-0.301, lastTarget.getZ(), 0.0);
    }

    @Test
    public void sidewaysMoreThanVanillaOrOffTheFacingIsCorrected() throws Exception {
        northBoxWithPlayerInFront();
        lidFor(BlockFace.NORTH, progressAfter(3));
        serverAt(0.5, 0.0, -0.301 - 0.45); // 0.12 past three pushes
        assertEquals(1, pollSetBacks());
        serverAt(0.5, 0.0, -0.301 + 0.15); // into the box
        assertEquals(1, pollSetBacks());
        serverAt(0.5 + 0.33, 0.0, -0.301); // east instead of north
        assertEquals(1, pollSetBacks());
        serverAt(0.5, 0.33, -0.301); // up instead of north
        assertEquals(1, pollSetBacks());
        assertEquals(-0.301, data.untrackedTrustedZ, 0.0);
        // Cap: the near side never further out than the slab's top plus one push (lid 0.3 -> 0.41 out), even
        // within the margin of the replayed path (its end is 0.331 out).
        serverAt(0.5, 0.0, -0.72);
        assertEquals(1, pollSetBacks());
        serverAt(0.5, 0.0, -0.70);
        assertEquals(0, pollSetBacks());
        assertEquals(-0.301, data.untrackedTrustedZ, 0.0);
    }

    @Test
    public void sidewaysWithoutTheServersOpeningGetsNothing() throws Exception {
        facing(BlockFace.NORTH);
        trustedAt(0.5, 0.0, -0.301);
        // Static open lid (someone else holds it open): not recorded, every drift corrected, trusted never moves.
        lidFor(BlockFace.NORTH, 1.0F);
        openBox();
        assertFalse(data.shulkerLidOpenValid);
        for (int i = 1; i <= 5; i++) {
            serverAt(0.5, 0.0, -0.301 - i * 0.11);
            assertEquals(1, pollSetBacks());
            assertEquals(-0.301, data.untrackedTrustedZ, 0.0);
        }
        // Opened, but the lid then closes again or stays shut: nothing.
        lidFor(BlockFace.NORTH, 0.6F);
        openBox();
        assertTrue(data.shulkerLidOpenValid);
        lidFor(BlockFace.NORTH, 0.4F);
        serverAt(0.5, 0.0, -0.301 - 0.22);
        assertEquals(1, pollSetBacks());
        lidFor(BlockFace.NORTH, 0.0F);
        openBox();
        serverAt(0.5, 0.0, -0.301 - 0.11);
        assertEquals(1, pollSetBacks());
        // Expired window.
        lidFor(BlockFace.NORTH, progressAfter(1));
        data.shulkerLidOpenTick -= ShulkerLidRide.WINDOW_TICKS + 1;
        assertEquals(1, pollSetBacks());
    }

    @Test
    public void besideOrAboveTheLidFaceGetsNothing() throws Exception {
        northBoxWithPlayerInFront();
        lidFor(BlockFace.NORTH, progressAfter(3));
        // Beside the face (body x 1.01 .. 1.61).
        trustedAt(1.31, 0.0, -0.301);
        serverAt(1.31, 0.0, -0.631);
        assertEquals(1, pollSetBacks());
        // Feet above the box's top (body y 1.0 .. 2.8 doesn't overlap 0 .. 1).
        trustedAt(0.5, 1.0, -0.301);
        serverAt(0.5, 1.0, -0.631);
        assertEquals(1, pollSetBacks());
        // Head below its bottom.
        trustedAt(0.5, -1.8, -0.301);
        serverAt(0.5, -1.8, -0.631);
        assertEquals(1, pollSetBacks());
        // Further out than the lid ever reaches.
        trustedAt(0.5, 0.0, -1.31);
        serverAt(0.5, 0.0, -1.42);
        assertEquals(1, pollSetBacks());
    }

    @Test
    public void aWallOrPaneAheadStopsTheSidewaysAllowance() throws Exception {
        northBoxWithPlayerInFront();
        lidFor(BlockFace.NORTH, 1.0F);
        // A pane two cells north (its collision box z 0.4375 .. 0.5625 of cell z -2): the body would pass it.
        solid(0, 1, -2, new BoundingBox(0, 0, 0.4375, 1, 1, 0.5625));
        serverAt(0.5, 0.0, -1.401);
        assertEquals(1, pollSetBacks());
        assertEquals(-0.301, data.untrackedTrustedZ, 0.0);
        // Stopped by the pane, as vanilla's collision would (leading side exactly at its face): fine.
        serverAt(0.5, 0.0, -1.4375 + 0.3);
        assertEquals(0, pollSetBacks());
        // A fence post in the cell below, two cells north: 1.5 tall, it reaches 0.5 into the path of the feet.
        trustedAt(0.5, 0.0, -0.301);
        solid(0, 1, -2, new BoundingBox(0, 0, 0, 0, 0, 0));
        solid(0, -1, -2, new BoundingBox(0.375, 0.0, 0.375, 0.625, 1.5, 0.625));
        serverAt(0.5, 0.0, -0.301 - 0.55);
        assertEquals(0, pollSetBacks());
        trustedAt(0.5, 0.0, -0.301);
        serverAt(0.5, 0.0, -1.401);
        assertEquals(1, pollSetBacks());
        assertEquals(-0.301, data.untrackedTrustedZ, 0.0);
    }

    @Test
    public void aBodyAlreadyInsideAPaneIsNotPushedOutThroughIt() throws Exception {
        // Lab s1 probe p-pane-through: a move event NCP accepted (passable is off) left the trusted body inside the
        // pane; one more push-sized step beyond it must not count as the lid's push.
        northBoxWithPlayerInFront();
        lidFor(BlockFace.NORTH, 1.0F);
        solid(0, 0, -2, new BoundingBox(0, 0, 0.4375, 1, 1, 0.5625)); // pane z -1.5625 .. -1.4375
        trustedAt(0.5, 0.0, -1.248); // body z -1.548 .. -0.948: inside the pane
        serverAt(0.5, 0.0, -1.357);
        assertEquals(1, pollSetBacks());
        assertEquals(-1.248, data.untrackedTrustedZ, 0.0);
        trustedAt(0.5, 0.0, -1.357); // body already straddling the pane, the step ahead is free
        serverAt(0.5, 0.0, -1.467);
        assertEquals(1, pollSetBacks());
        // Touching its face from the box's side (where vanilla's push stops) and drifting into it: corrected.
        trustedAt(0.5, 0.0, -1.1375);
        serverAt(0.5, 0.0, -1.248);
        assertEquals(1, pollSetBacks());
        // And a body touching a wall beside it (not inside) keeps its push.
        solid(0, 0, -2, new BoundingBox(0, 0, 0, 0, 0, 0));
        solid(1, 0, -1, new BoundingBox(0, 0, 0, 1, 1, 1)); // x 1 .. 2, body x 0.2 .. 0.8
        trustedAt(0.5, 0.0, -0.301);
        serverAt(0.5, 0.0, -1.401);
        assertEquals(0, pollSetBacks());
        // A wall the body is in at the start: nothing.
        trustedAt(0.5, 0.0, -0.301);
        solid(1, 0, -1, new BoundingBox(-0.25, 0, 0, 1, 1, 1)); // reaches x 0.75
        serverAt(0.5, 0.0, -0.411);
        assertEquals(1, pollSetBacks());
    }

    private double moveStart(final double fx, final double fy, final double fz, final double lx, final double ly, final double lz)
            throws Exception {
        serverAt(lx, ly, lz);
        return withFolia(() -> ShulkerLidRide.explainsMoveStart(player, data, new Location(world, fx, fy, fz),
                new Location(world, lx, ly, lz)));
    }

    @Test
    public void theStartOfASplitMoveIsLeftUncheckedOnlyForAnExactPush() throws Exception {
        // A move event whose start lags the server's pushes (high ping): from = NCP's last checked position, the
        // server position before the client's move = from + the pushes.
        northBoxWithPlayerInFront();
        lidFor(BlockFace.NORTH, progressAfter(4));
        assertEquals(0.44, moveStart(0.5, 0.0, -0.301, 0.5, 0.0, -0.741), 1e-6);
        assertEquals(0.44, moveStart(0.5, 0.0, -0.301, 0.5, 0.0, -0.411), 1e-6);
        // Not exactly along the facing, backwards, beyond the replayed push: checked as before.
        assertTrue(Double.isNaN(moveStart(0.5, 0.0, -0.301, 0.51, 0.0, -0.741)));
        assertTrue(Double.isNaN(moveStart(0.5, 0.0, -0.301, 0.5, 0.01, -0.741)));
        assertTrue(Double.isNaN(moveStart(0.5, 0.0, -0.301, 0.5, 0.0, -0.2)));
        assertTrue(Double.isNaN(moveStart(0.5, 0.0, -0.301, 0.5, 0.0, -0.80)));
        // Through a pane: checked.
        lidFor(BlockFace.NORTH, 1.0F);
        solid(0, 0, -2, new BoundingBox(0, 0, 0.4375, 1, 1, 0.5625));
        assertTrue(Double.isNaN(moveStart(0.5, 0.0, -0.301, 0.5, 0.0, -1.401)));
        assertEquals(1.10, moveStart(0.5, 0.0, -0.301, 0.5, 0.0, -1.1375), 1e-6);
        // Upward box: straight up, exactly.
        facing(BlockFace.UP);
        lid(0.0F);
        trustedAt(0.5, 1.0, 0.5);
        openBox();
        lid(progressAfter(5));
        assertEquals(0.55, moveStart(0.5, 1.0, 0.5, 0.5, 1.55, 0.5), 1e-6);
        assertTrue(Double.isNaN(moveStart(0.5, 1.0, 0.5, 0.53, 1.55, 0.5)));
        // No opening on record, or the window is over: checked.
        data.shulkerLidOpenTick -= ShulkerLidRide.WINDOW_TICKS + 1;
        assertTrue(Double.isNaN(moveStart(0.5, 1.0, 0.5, 0.5, 1.55, 0.5)));
        data.shulkerLidOpenValid = false;
        data.shulkerLidOpenTick += ShulkerLidRide.WINDOW_TICKS + 1;
        assertTrue(Double.isNaN(moveStart(0.5, 1.0, 0.5, 0.5, 1.55, 0.5)));
    }

    @Test
    public void downwardLidPushesAPlayerBelowItDown() throws Exception {
        facing(BlockFace.DOWN);
        lidFor(BlockFace.DOWN, 0.0F);
        // Head touching the box's bottom face (y 0), in the air.
        trustedAt(0.5, -1.8, 0.5);
        openBox();
        assertTrue(data.shulkerLidOpenValid);
        lidFor(BlockFace.DOWN, progressAfter(3));
        serverAt(0.5, -1.8 - 0.33, 0.5);
        assertEquals(0, pollSetBacks());
        assertEquals(-2.13, data.untrackedTrustedY, 1e-9);
        // Up instead of down, or more than vanilla: corrected.
        trustedAt(0.5, -1.8, 0.5);
        serverAt(0.5, -1.8 + 0.2, 0.5);
        assertEquals(1, pollSetBacks());
        serverAt(0.5, -1.8 - 0.45, 0.5);
        assertEquals(1, pollSetBacks());
        // Through a floor: corrected; down to it (vanilla's collision): fine.
        solid(0, -3, 0, new BoundingBox(0, 0, 0, 1, 1, 1));
        serverAt(0.5, -2.13, 0.5);
        assertEquals(1, pollSetBacks());
        serverAt(0.5, -2.0, 0.5);
        assertEquals(0, pollSetBacks());
        assertEquals(-2.0, data.untrackedTrustedY, 1e-9);
    }

    @Test
    public void foreignChunksAreNeverReadForASidewaysLid() throws Exception {
        // Box at the chunk border: its lid pushes north into chunk z = -1.
        northBoxWithPlayerInFront();
        lidFor(BlockFace.NORTH, progressAfter(3));
        serverAt(0.5, 0.0, -0.631);
        owned = l -> (l.getBlockZ() >> 4) == 0;
        clearInvocations(world);
        assertEquals(1, pollSetBacks());
        verify(world, never()).getBlockAt(anyInt(), anyInt(), intThat(z -> z < 0));
        owned = l -> true;
        when(world.isChunkLoaded(anyInt(), anyInt())).thenAnswer(call -> (int) call.getArgument(1) == 0);
        clearInvocations(world);
        assertEquals(1, pollSetBacks());
        verify(world, never()).getBlockAt(anyInt(), anyInt(), intThat(z -> z < 0));
    }
}
