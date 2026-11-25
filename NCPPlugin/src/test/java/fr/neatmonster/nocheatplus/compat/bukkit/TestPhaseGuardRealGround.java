package fr.neatmonster.nocheatplus.compat.bukkit;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import fr.neatmonster.nocheatplus.checks.moving.magic.InBlockJumpGuard;
import fr.neatmonster.nocheatplus.checks.moving.magic.Magic;
import fr.neatmonster.nocheatplus.compat.MCAccess;
import fr.neatmonster.nocheatplus.components.registry.event.IHandle;
import fr.neatmonster.nocheatplus.logging.StaticLog;
import fr.neatmonster.nocheatplus.test.BlockTests;
import fr.neatmonster.nocheatplus.utilities.location.PlayerLocation;
import fr.neatmonster.nocheatplus.utilities.map.BlockFlags;
import fr.neatmonster.nocheatplus.utilities.map.BlockProperties;
import fr.neatmonster.nocheatplus.utilities.map.FakeBlockCache;

/**
 * InBlockJumpGuard on a real PlayerLocation over a FakeBlockCache: no mocked ground probe.
 * Geometry is the lab S1 fixture moved up by 125 blocks (FakeBlockCache is air below y=0): obsidian floor at y=64, a 2-deep 3x3 ring at y=65..66 and a 2-stack in the
 * centre cell, feet teleported to the stack bottom (y=65.0).
 */
public class TestPhaseGuardRealGround {
    private static final double[] FULL = {0.0, 0.0, 0.0, 1.0, 1.0, 1.0};
    /** Vanilla half width (0.6F / 2), as the lab's corner teleport uses. */
    private static final double HW = 0.30000001192092896;
    private FakeBlockCache blocks;
    private World world;
    private Player player;
    private IHandle<MCAccess> handle;
    private final List<PlayerLocation> made = new ArrayList<>();

    @Before
    @SuppressWarnings("unchecked")
    public void setup() {
        StaticLog.setUseLogManager(false);
        BlockTests.initBlockProperties();
        StaticLog.setUseLogManager(true);
        world = mock(World.class);
        when(world.getName()).thenReturn("world");
        when(world.getMaxHeight()).thenReturn(320);
        when(world.getMinHeight()).thenReturn(-64);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        player = mock(Player.class);
        when(player.getWorld()).thenReturn(world);
        final MCAccess access = mock(MCAccess.class);
        when(access.getWidth(any(Entity.class))).thenReturn(0.6);
        when(access.getHeight(any(Entity.class))).thenReturn(1.8);
        handle = mock(IHandle.class);
        when(handle.getHandle()).thenReturn(access);
        blocks = new FakeBlockCache();
    }

    @After
    public void cleanup() {
        for (final PlayerLocation loc : made) loc.cleanup();
        blocks.cleanup();
    }

    private void floor() {
        blocks.fill(-2, 64, -2, 2, 64, 2, Material.OBSIDIAN, 0, FULL);
    }

    /** S1: 2-deep 3x3 ring plus the 2-stack in the centre cell (0, 0). */
    private void s1Hole() {
        floor();
        blocks.fill(-1, 65, -1, 1, 66, 1, Material.OBSIDIAN, 0, FULL);
    }

    /** S4c: the 2-stack in the open. */
    private void s4cOpen() {
        floor();
        blocks.fill(0, 65, 0, 0, 66, 0, Material.OBSIDIAN, 0, FULL);
    }

    /** C9 (R4): S1 with the two-block side at x=+1 cleared. */
    private void c9Column() {
        s1Hole();
        blocks.set(1, 65, 0, Material.AIR);
        blocks.set(1, 66, 0, Material.AIR);
    }

    /**
     * As MovingListener builds a move's from: cc.yOnGround, the location's on-ground read (the "stuck" shortcut says
     * true inside a block standing on a full block), then the 1.17+ powder-snow re-check, which flips it to false
     * with the full box query. The shortcut's cached onGroundMinY stays behind.
     */
    private PlayerLocation at(final double x, final double y, final double z) {
        final PlayerLocation loc = new PlayerLocation(handle, blocks);
        loc.set(new Location(world, x, y, z), player, 0.6, 1.62, 1.8, 1.8, Magic.Y_ON_GROUND_DEFAULT);
        made.add(loc);
        if (loc.isOnGround()) {
            loc.adjustOnGround(!loc.isOnGround(Magic.Y_ON_GROUND_DEFAULT, BlockFlags.F_POWDERSNOW));
        }
        return loc;
    }

    /** The plain 0.03 box query below the feet, without any location cache. */
    private boolean box003(final double x, final double y, final double z) {
        return BlockProperties.isOnGround(blocks, x - 0.3, y - 0.03, z - 0.3, x + 0.3, y, z + 0.3, 0L);
    }

    private boolean probe003(final double x, final double y, final double z) {
        return at(x, y, z).isOnGround(0.03);
    }

    private boolean held(final double x, final double y, final double z) {
        final List<String> tags = new ArrayList<>();
        final boolean result = InBlockJumpGuard.holdStaleGroundJump(at(x, y, z), tags);
        assertEquals(result, tags.contains("phaseguard_hold"));
        return result;
    }

    private String report(final String row, final PlayerLocation loc, final boolean probe, final boolean held) {
        return row + ": onGround=" + loc.isOnGround() + " probe0.03=" + probe
                + " box0.03=" + box003(loc.getX(), loc.getY(), loc.getZ())
                + " feetFull=" + feetFull(loc) + " headBlocked=" + headBlocked(loc) + " held=" + held;
    }

    private static boolean feetFull(final PlayerLocation loc) {
        final int fy = Location.locToBlock(loc.getMinY() + 1.0E-7);
        for (int x = Location.locToBlock(loc.getMinX()); x <= Location.locToBlock(loc.getMaxX() - 1.0E-7); x++) {
            for (int z = Location.locToBlock(loc.getMinZ()); z <= Location.locToBlock(loc.getMaxZ() - 1.0E-7); z++) {
                if (!loc.getBlockCache().isFullBounds(x, fy, z)) return false;
            }
        }
        return true;
    }

    private static boolean headBlocked(final PlayerLocation loc) {
        final int hy = Location.locToBlock(loc.getMinY() + 1.0E-7) + 1;
        for (int x = Location.locToBlock(loc.getMinX()); x <= Location.locToBlock(loc.getMaxX() - 1.0E-7); x++) {
            for (int z = Location.locToBlock(loc.getMinZ()); z <= Location.locToBlock(loc.getMaxZ() - 1.0E-7); z++) {
                if (loc.getBlockCache().getBounds(x, hy, z) != null) return true;
            }
        }
        return false;
    }

    private String row(final String name, final double x, final double y, final double z) {
        final boolean probe = probe003(x, y, z);
        final boolean result = held(x, y, z);
        final String line = report(name, at(x, y, z), probe, result);
        System.out.println("[phaseguard-real] " + line);
        return line;
    }

    @Test
    public void s1CentreIsHeldWithRealGroundProbing() {
        s1Hole();
        final String line = row("S1 centre", 0.5, 65.0, 0.5);
        assertTrue(line, feetFull(at(0.5, 65.0, 0.5)));
        assertTrue(line, headBlocked(at(0.5, 65.0, 0.5)));
        assertTrue(line, held(0.5, 65.0, 0.5));
    }

    @Test
    public void s2CornerTeleportFirstIsHeldWithRealGroundProbing() {
        // S2t / S2sb: corner jump as the first move after a teleport or set-back = the S1 data_reset path.
        s1Hole();
        final String line = row("S2t corner", HW, 65.0, HW);
        assertTrue(line, held(HW, 65.0, HW));
    }

    @Test
    public void s4cOpenCentreIsHeldWithRealGroundProbing() {
        s4cOpen();
        final String line = row("S4c centre", 0.5, 65.0, 0.5);
        assertTrue(line, held(0.5, 65.0, 0.5));
    }

    @Test
    public void c9ColumnIsHeldWithRealGroundProbing() {
        c9Column();
        final String line = row("C9 centre", 0.5, 65.0, 0.5);
        assertTrue(line, held(0.5, 65.0, 0.5));
    }

    @Test
    public void standingOnTheStackTopIsNotHeld() {
        s1Hole();
        final String line = row("on top", 0.5, 67.0, 0.5);
        assertFalse(line, held(0.5, 67.0, 0.5));
        assertTrue(line, at(0.5, 67.0, 0.5).isOnGround());
    }

    @Test
    public void straddlingAnAirColumnIsNotHeld() {
        // Pearl/face straddle: 0.3 inside the open stack's west face, 0.3 over air.
        s4cOpen();
        final String line = row("straddle", 0.0, 65.0, 0.5);
        assertFalse(line, held(0.0, 65.0, 0.5));
    }

    @Test
    public void embeddedFromKeepsAStaleShortProbeAfterTheListenerAdjustment() {
        // Why the guard must not use from.isOnGround(0.03): in the S1 stack it answers from the shortcut's cache, while
        // the 0.03 box below the feet touches no ground and the move's from is not on ground.
        s1Hole();
        final PlayerLocation loc = at(0.5, 65.0, 0.5);
        assertFalse(loc.isOnGround());
        assertFalse(box003(0.5, 65.0, 0.5));
        // The cached answer (true at a48322383's early exit) is printed, not pinned: it is NCP's cache, not our contract.
        System.out.println("[phaseguard-real] S1 centre after listener: onGround=false box0.03=false cached isOnGround(0.03)="
                + loc.isOnGround(0.03));
    }
}
