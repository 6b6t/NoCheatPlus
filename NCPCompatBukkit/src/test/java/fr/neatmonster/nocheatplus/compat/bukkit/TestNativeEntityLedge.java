package fr.neatmonster.nocheatplus.compat.bukkit;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.Test;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.*;
import org.bukkit.util.BoundingBox;
import fr.neatmonster.nocheatplus.compat.Folia;
import fr.neatmonster.nocheatplus.compat.versions.ServerVersion;

/**
 * Setbacks round 2 finding for NoFall v4 (ProbeV4RaftLedge): movement ground
 * (RichEntityLocation#isOnGround: the player's box widened by 0.25) let a
 * player up to 0.24 beside a bamboo raft or boat, feet at its top, stand on
 * air (Meteor hover over a raft row: 0 set-backs vs live's 10). Only the real
 * footprint stands on an entity; live's own 0.1 boat rule stays as on live;
 * NoFall's strict contact (margin 0) is unchanged.
 */
public class TestNativeEntityLedge {
    static final double TOP = 64.5625;

    @Test public void noLedgeBesideRaftsOrBoats() {
        String old = ServerVersion.getMinecraftVersion(); ServerVersion.setMinecraftVersion("26.2");
        try (var folia = mockStatic(Folia.class); var nat = mockStatic(NativeLandingAccess.class)) {
            folia.when(() -> Folia.isOwnedByCurrentRegion(any(Entity.class))).thenReturn(true);
            nat.when(() -> NativeLandingAccess.canCollide(any(Player.class), any(Entity.class))).thenReturn(true);
            for (boolean raft : new boolean[] {true, false}) {
                Entity e = raft ? mock(Entity.class) : mock(Boat.class);
                when(e.isValid()).thenReturn(true); when(e.getUniqueId()).thenReturn(UUID.randomUUID());
                BoundingBox box = new BoundingBox(0, 64, 0, 1.375, TOP, 1.375);
                when(e.getBoundingBox()).thenReturn(box);
                for (double gap : new double[] {0.12, 0.2, 0.24, 0.0, -0.2}) {
                    for (double above : new double[] {0.0, 0.005, 0.02}) {
                        final double cx = -0.3 - gap, feet = TOP + above;
                        Player p = player(cx, feet, e, box);
                        BlockCacheBukkitModern cache = mock(BlockCacheBukkitModern.class, CALLS_REAL_METHODS);
                        // RichEntityLocation#isOnGround: real box +0.25 sideways, yOnGround 0.025 below.
                        boolean ground = cache.standsOnEntity(p, cx - 0.55, feet - 0.025, 0.6875 - 0.55, cx + 0.55, feet, 0.6875 + 0.55);
                        boolean contact = cache.standsOnEntityContact(p, cx - 0.3, feet - 0.00001, 0.6875 - 0.3, cx + 0.3, feet, 0.6875 + 0.3);
                        String c = (raft ? "raft" : "boat") + " gap " + gap + " above " + above;
                        if (gap > 0.0) {
                            assertFalse("movement ground beside: " + c, ground);
                            assertFalse("contact beside: " + c, contact);
                        } else {
                            // Real overlap (touching edge excluded: gap 0.0 touches, -0.2 overlaps).
                            assertEquals("real support: " + c, gap < 0.0, ground);
                            assertEquals("strict contact: " + c, gap < 0.0 && above == 0.0, contact);
                        }
                    }
                }
            }
        } finally { ServerVersion.setMinecraftVersion(old); }
    }

    /** A player whose nearby-entity queries answer by real distance (as the server does). */
    static Player player(final double cx, final double feet, final Entity e, final BoundingBox box) {
        Player p = mock(Player.class); World w = mock(World.class);
        when(p.getUniqueId()).thenReturn(UUID.randomUUID()); when(p.getWidth()).thenReturn(0.6);
        when(p.getLocation()).thenReturn(new Location(w, cx, feet, 0.6875));
        when(p.getLocation(any(Location.class))).thenAnswer(inv -> { Location l = inv.getArgument(0); l.setX(cx); l.setY(feet); l.setZ(0.6875); return l; });
        when(p.getNearbyEntities(anyDouble(), anyDouble(), anyDouble())).thenAnswer(inv -> {
            double x = inv.getArgument(0), y = inv.getArgument(1), z = inv.getArgument(2);
            BoundingBox q = new BoundingBox(cx - 0.3 - x, feet - y, 0.6875 - 0.3 - z, cx + 0.3 + x, feet + 1.8 + y, 0.6875 + 0.3 + z);
            return q.overlaps(box) ? List.of(e) : List.of();
        });
        return p;
    }
}
