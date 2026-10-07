package fr.neatmonster.nocheatplus.compat.bukkit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

import fr.neatmonster.nocheatplus.compat.Folia;
import fr.neatmonster.nocheatplus.compat.bukkit.model.BukkitShapeModel;
import fr.neatmonster.nocheatplus.compat.versions.ServerVersion;
import fr.neatmonster.nocheatplus.utilities.map.MaterialUtil;

/**
 * Standing on a boat or bamboo raft (PR #3 parts 10, 11), round 2. The live rule around the current position stays
 * exactly as live (boats and shulkers only). New: real support at the given bounds (the move's target too), only with
 * the player's real footprint over the entity's box, the feet at its top (yOnGround), an owned, valid entity the
 * server lets the player collide with. This is the path below 26.1; from 26.1 on the NoFall v4 native path decides.
 */
public class TestStandOnBoat {

    /** Boat/raft box x 0..1.375, z 0..1.375, y 64..64.5625 (REVIEW-CODEX finding 1). */
    private static final double TOP = 64.5625;
    private static final double YONGROUND = 0.025;
    private static final double MID = 0.6875;

    private MockedStatic<Folia> folia;
    private MockedStatic<NativeEntityCollision> collision;
    private String oldVersion;
    private final Map<Entity, BoundingBox> worldBoxes = new IdentityHashMap<Entity, BoundingBox>();
    private final Map<Entity, Boolean> owned = new IdentityHashMap<Entity, Boolean>();
    private final Map<Entity, Boolean> collides = new IdentityHashMap<Entity, Boolean>();

    @Before
    public void setUp() {
        oldVersion = ServerVersion.getMinecraftVersion();
        ServerVersion.setMinecraftVersion("1.21.4");
        folia = mockStatic(Folia.class);
        folia.when(() -> Folia.isOwnedByCurrentRegion(any(Location.class))).thenReturn(true);
        folia.when(() -> Folia.isOwnedByCurrentRegion(any(Entity.class))).thenAnswer(inv -> {
            final Boolean own = owned.get(inv.getArgument(0));
            return own == null || own;
        });
        collision = mockStatic(NativeEntityCollision.class);
        collision.when(() -> NativeEntityCollision.canCollide(any(Entity.class), any(Entity.class))).thenAnswer(inv -> {
            final Boolean c = collides.get(inv.getArgument(1));
            return c == null || c;
        });
    }

    @After
    public void tearDown() {
        collision.close();
        folia.close();
        ServerVersion.setMinecraftVersion(oldVersion);
    }

    private static EntityType raftType(final String name) {
        final EntityType type = mock(EntityType.class);
        when(type.name()).thenReturn(name);
        return type;
    }

    /** A boat or raft entity with the given box; the world lookup knows its box without asking the entity. */
    private Entity vehicle(final EntityType type, final BoundingBox box) {
        final Entity e = mock(Entity.class);
        when(e.getType()).thenReturn(type);
        when(e.isValid()).thenReturn(true);
        when(e.getBoundingBox()).thenReturn(box);
        when(e.getHeight()).thenReturn(box.getHeight());
        when(e.getLocation(any(Location.class))).thenAnswer(inv -> {
            final Location l = inv.getArgument(0);
            l.setX(box.getCenterX()); l.setY(box.getMinY()); l.setZ(box.getCenterZ());
            return l;
        });
        worldBoxes.put(e, box);
        return e;
    }

    private Entity raft() {
        return vehicle(raftType("BAMBOO_RAFT"), new BoundingBox(0, 64, 0, 1.375, TOP, 1.375));
    }

    private Entity boat() {
        return vehicle(EntityType.BOAT, new BoundingBox(0, 64, 0, 1.375, TOP, 1.375));
    }

    /**
     * The player stands at (curX, curY, curZ) and moves to (toX, toY, toZ). Returns standsOnEntity for the target
     * bounds as RichEntityLocation.isOnGround passes them (0.25 xz margin, yOnGround below the feet).
     */
    private boolean stands(final List<Entity> entities, final double curX, final double curY, final double curZ,
            final double toX, final double toY, final double toZ) {
        final World world = mock(World.class);
        final Player player = mock(Player.class);
        when(player.getWorld()).thenReturn(world);
        when(player.getWidth()).thenReturn(0.6);
        when(player.getLocation(any(Location.class))).thenAnswer(inv -> {
            final Location l = inv.getArgument(0);
            l.setX(curX); l.setY(curY); l.setZ(curZ);
            return l;
        });
        // Live: entities within 0.1 (xz) / 2.0 (y) of the player's current box.
        final BoundingBox current = new BoundingBox(curX - 0.3, curY, curZ - 0.3, curX + 0.3, curY + 1.8, curZ + 0.3).expand(0.1, 2.0, 0.1);
        final List<Entity> near = new ArrayList<Entity>();
        for (final Entity e : entities) {
            if (current.overlaps(worldBoxes.get(e))) near.add(e);
        }
        when(player.getNearbyEntities(anyDouble(), anyDouble(), anyDouble())).thenReturn(near);
        when(world.getNearbyEntities(any(BoundingBox.class), any())).thenAnswer(inv -> {
            final BoundingBox box = inv.getArgument(0);
            @SuppressWarnings("unchecked")
            final Predicate<Entity> filter = inv.getArgument(1);
            final List<Entity> res = new ArrayList<Entity>();
            for (final Entity e : entities) {
                if (worldBoxes.get(e).overlaps(box) && (filter == null || filter.test(e))) res.add(e);
            }
            return (Collection<Entity>) res;
        });
        final BlockCacheBukkitModern cache = new BlockCacheBukkitModern(Collections.<org.bukkit.Material, BukkitShapeModel>emptyMap());
        final double d1 = 0.25;
        return cache.standsOnEntity(player, toX - 0.3 - d1, toY - YONGROUND, toZ - 0.3 - d1, toX + 0.3 + d1, toY, toZ + 0.3 + d1);
    }

    /** Start far away (no live rule), target at (toX, toY, MID). */
    private boolean target(final Entity e, final double toX, final double toY) {
        return stands(Collections.singletonList(e), -5.0, 64.0, MID, toX, toY, MID);
    }

    @Test
    public void reviewRaftCounterexample() {
        // REVIEW-CODEX finding 1: player center x -0.35, width 0.6: the footprint ends at x -0.05, a 0.05 gap.
        for (final double above : new double[] {0.0, 0.005, 0.035}) {
            assertFalse("Raft, 0.05 gap, feet " + above + " above the top (target).", target(raft(), -0.35, TOP + above));
            // Rafts aren't in the live rule either.
            assertFalse("Raft, 0.05 gap, feet " + above + " above the top (current).",
                    stands(Collections.singletonList(raft()), -0.35, TOP + above, MID, -0.35, TOP + above, MID));
        }
    }

    @Test
    public void raftLedgeRow() {
        // Hovering at raft-top height along a row of rafts, 0.05 / 0.01 / 0.001 beside them: never ground.
        final List<Entity> row = new ArrayList<Entity>();
        for (int i = 0; i < 5; i++) {
            row.add(vehicle(raftType("BAMBOO_RAFT"), new BoundingBox(0, 64, 1.4 * i, 1.375, TOP, 1.4 * i + 1.375)));
        }
        for (final double gap : new double[] {0.05, 0.01, 0.001}) {
            for (double z = 0.3; z < 6.5; z += 0.25) {
                assertFalse("Gap " + gap + " at z " + z, stands(row, -0.3 - gap, TOP, z, -0.3 - gap, TOP, z));
                assertFalse("Gap " + gap + " at z " + z + ", feet 0.005 up", stands(row, -0.3 - gap, TOP + 0.005, z, -0.3 - gap, TOP + 0.005, z));
            }
        }
    }

    @Test
    public void onARaftAndSteppingOntoIt() {
        assertTrue("Middle of the raft.", target(raft(), MID, TOP));
        assertTrue("Within yOnGround above the top.", target(raft(), MID, TOP + 0.02));
        assertTrue("Stepping onto the raft's edge (footprint 0.05 over it).", target(raft(), -0.25, TOP));
        assertTrue("Chest raft.", target(vehicle(raftType("BAMBOO_CHEST_RAFT"), new BoundingBox(0, 64, 0, 1.375, TOP, 1.375)), MID, TOP));
        assertTrue("Boat, stepping onto its edge.", target(boat(), -0.25, TOP));
    }

    @Test
    public void noMarginsAnywhere() {
        // Edge to edge (no overlap), tiny gaps, above yOnGround, inside the box.
        assertFalse("Footprint edge exactly at the raft's edge.", target(raft(), -0.3, TOP));
        assertFalse("0.005 gap.", target(raft(), -0.305, TOP));
        assertFalse("0.03 above the top (yOnGround 0.025).", target(raft(), MID, TOP + 0.03));
        assertFalse("0.0101 above the top past yOnGround + 0.01 (round 1's margin).", target(raft(), MID, TOP + YONGROUND + 0.0101));
        assertFalse("Feet 0.01 inside the raft.", target(raft(), MID, TOP - 0.01));
        assertFalse("Feet 0.086 below the top (water level).", target(raft(), MID, TOP - 0.086));
        assertFalse("Boat, 0.05 gap at its top.", target(boat(), -0.35, TOP));
    }

    @Test
    public void boatMotionOnlyTheCurrentBox() {
        // The boat bobbed down 0.03 since the last tick: its old top is no support any more.
        final Entity bobbing = vehicle(EntityType.BOAT, new BoundingBox(0, 63.97, 0, 1.375, TOP - 0.03, 1.375));
        assertFalse("Feet at the old top.", target(bobbing, MID, TOP));
        assertTrue("Feet at the current top.", target(bobbing, MID, TOP - 0.03));
    }

    @Test
    public void uncollidableInvalidOrForeignEntities() {
        final Entity noCollide = raft();
        collides.put(noCollide, false);
        assertFalse("The server says no collision (e.g. spectator).", target(noCollide, MID, TOP));

        final Entity dead = raft();
        when(dead.isValid()).thenReturn(false);
        assertFalse("Invalid (dead or removed).", target(dead, MID, TOP));

        final Entity foreign = raft();
        owned.put(foreign, false);
        assertFalse("Owned by another region.", target(foreign, MID, TOP));
        verify(foreign, never()).isValid();
        verify(foreign, never()).getType();
        verify(foreign, never()).getBoundingBox();

        final Entity pig = vehicle(EntityType.PIG, new BoundingBox(0, 64, 0, 1.375, TOP, 1.375));
        assertFalse("Other entity types.", target(pig, MID, TOP));
    }

    @Test
    public void playerNotOwnedNoNewSupport() {
        final Entity r = raft();
        folia.when(() -> Folia.isOwnedByCurrentRegion(any(Location.class))).thenReturn(false);
        assertFalse("Target location in another region.", target(r, MID, TOP));
    }

    @Test
    public void liveRuleKeptExactly() {
        // Boats, current position: as live, also its leniency (0.5 above, water level 0.05 beside: the live jesus hole).
        assertTrue("Standing on a boat.", stands(Collections.singletonList(boat()), MID, TOP, MID, MID, TOP, MID));
        assertTrue("0.5 above a boat (live leniency).", stands(Collections.singletonList(boat()), MID, TOP + 0.5, MID, MID, TOP + 0.5, MID));
        assertTrue("Beside a boat at water level (live rule).", stands(Collections.singletonList(boat()), -0.35, TOP - 0.086, MID, -0.35, TOP - 0.086, MID));
        // Rafts: not in the live rule (as live).
        assertFalse("0.5 above a raft.", stands(Collections.singletonList(raft()), MID, TOP + 0.5, MID, MID, TOP + 0.5, MID));
        assertFalse("Beside a raft at water level.", stands(Collections.singletonList(raft()), -0.35, TOP - 0.086, MID, -0.35, TOP - 0.086, MID));
        // The new rule alone at water level beside a boat: no ground.
        assertFalse("Target rule, beside a boat at water level.", target(boat(), -0.35, TOP - 0.086));
    }

    @Test
    public void onlyPlayersGetTheNewRule() {
        final Entity r = raft();
        final World world = mock(World.class);
        final Entity horse = mock(Entity.class);
        when(horse.getWorld()).thenReturn(world);
        when(horse.getWidth()).thenReturn(1.4);
        when(horse.getNearbyEntities(anyDouble(), anyDouble(), anyDouble())).thenReturn(Collections.<Entity>emptyList());
        when(world.getNearbyEntities(any(BoundingBox.class), any())).thenReturn(Collections.singletonList(r));
        final BlockCacheBukkitModern cache = new BlockCacheBukkitModern(Collections.<org.bukkit.Material, BukkitShapeModel>emptyMap());
        assertFalse(cache.standsOnEntity(horse, 0, TOP - YONGROUND, 0, 1.375, TOP, 1.375));
    }

    @Test
    public void raftsCountAsBoatsForStanding() {
        assertTrue(MaterialUtil.isRaftName("BAMBOO_RAFT"));
        assertTrue(MaterialUtil.isRaftName("BAMBOO_CHEST_RAFT"));
        assertFalse(MaterialUtil.isRaftName("OAK_BOAT"));
        assertTrue(MaterialUtil.isBoatOrRaft(EntityType.BOAT));
        assertTrue(MaterialUtil.isBoatOrRaft(EntityType.CHEST_BOAT));
        assertTrue(MaterialUtil.isBoatOrRaft(raftType("BAMBOO_RAFT")));
        assertFalse(MaterialUtil.isBoatOrRaft(EntityType.PIG));
        assertFalse(MaterialUtil.isBoatOrRaft(null));
        // Vehicle checks keep isBoat (no rafts by name).
        assertFalse(MaterialUtil.isBoat(raftType("BAMBOO_RAFT")));
    }
}
