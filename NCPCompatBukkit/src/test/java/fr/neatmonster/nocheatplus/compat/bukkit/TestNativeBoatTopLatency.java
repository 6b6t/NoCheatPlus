package fr.neatmonster.nocheatplus.compat.bukkit;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import org.junit.Test;
import fr.neatmonster.nocheatplus.compat.Folia;
import fr.neatmonster.nocheatplus.compat.bukkit.TestNativeLandingAccess.Craft;
import fr.neatmonster.nocheatplus.compat.bukkit.TestNativeLandingAccess.Native;

/** FIX-6 still open #1 on the real boat history: the measured falling boat
 * (f6D-lat-mount8-en mount-air-0) passed 83.339 eight ticks before the FALL. */
public class TestNativeBoatTopLatency {
    // Boat feet y by boat tick 15..23 (vy -0.48 .. -0.76), then on the pad at 81. Top = y + 0.5625.
    static final double[] Y = {85.81665495, 85.29665495, 84.73665495, 84.13665495, 83.49665495, 82.81665495,
        82.09665495, 81.33665495, 81.0};

    @Test public void latencyWidensOnlyTheSearchAndNeverPastOneSecond() {
        try (var folia = mockStatic(Folia.class)) {
            org.bukkit.entity.Boat boat = mock(org.bukkit.entity.Boat.class, withSettings().extraInterfaces(Craft.class));
            Native n = new Native();
            var tick = new java.util.concurrent.atomic.AtomicInteger(15);
            var y = new java.util.concurrent.atomic.AtomicReference<Double>(Y[0]);
            when(((Craft) boat).getHandle()).thenReturn(n);
            when(boat.getTicksLived()).thenAnswer(c -> tick.get());
            when(boat.getLocation()).thenAnswer(c -> new org.bukkit.Location(null, 16.5, y.get(), 16.5));
            when(boat.getBoundingBox()).thenAnswer(c -> new org.bukkit.util.BoundingBox(15.8125, y.get(), 15.8125,
                    17.1875, y.get() + 0.5625, 17.1875));
            folia.when(() -> Folia.isOwnedByCurrentRegion(boat)).thenReturn(true);
            n.xOld = 16.5; n.zOld = 16.5; n.yOld = 86.29665495;
            // The player's falling moves query the boat every tick (no Bukkit server here: no owner task).
            for (int t = 15; t <= 28; t++) {
                if (t > 15) { n.yOld = y.get(); tick.set(t); y.set(t - 15 < Y.length ? Y[t - 15] : 81.0); }
                NativeLandingAccess.supportsSweptBoat(boat, 16.2, 95, 16.2, 16.8, 16.8);
            }
            // FALL at boat tick 28, feet on the top between ticks 20 (83.379) and 21 (82.659).
            final double feet = 83.33915494615299;
            assertFalse("vanilla display window only (updateInterval + 3)",
                    NativeLandingAccess.supportsSweptBoat(boat, 16.2, feet, 16.2, 16.8, 16.8));
            assertFalse("one tick of latency is not enough", NativeLandingAccess.supportsSweptBoat(boat, 16.2, feet, 16.2, 16.8, 16.8, 1));
            assertTrue("two ticks reach the tick-20 top", NativeLandingAccess.supportsSweptBoat(boat, 16.2, feet, 16.2, 16.8, 16.8, 2));
            assertTrue("80 +- 40 ms each way (ping 160: 5 ticks)", NativeLandingAccess.supportsSweptBoat(boat, 16.2, feet, 16.2, 16.8, 16.8, 5));
            assertFalse("beside the boat", NativeLandingAccess.supportsSweptBoat(boat, 17.3, feet, 16.2, 17.9, 16.8, 5));
            assertFalse("above every retained top", NativeLandingAccess.supportsSweptBoat(boat, 16.2, 90.5, 16.2, 16.8, 16.8, 20));
            assertFalse("below the settled boat", NativeLandingAccess.supportsSweptBoat(boat, 16.2, 81.2, 16.2, 16.8, 16.8, 20));
            // Retained frames end at updateInterval + 3 + 20 ticks; latency beyond the cap reaches no further.
            for (int t = 29; t <= 46; t++) { tick.set(t); NativeLandingAccess.supportsSweptBoat(boat, 16.2, 95, 16.2, 16.8, 16.8); }
            assertTrue("26 ticks back is the cap", NativeLandingAccess.supportsSweptBoat(boat, 16.2, feet, 16.2, 16.8, 16.8, 20));
            assertFalse("a smaller latency does not", NativeLandingAccess.supportsSweptBoat(boat, 16.2, feet, 16.2, 16.8, 16.8, 19));
            tick.set(47); NativeLandingAccess.supportsSweptBoat(boat, 16.2, 95, 16.2, 16.8, 16.8);
            assertFalse("never past the one-second cap", NativeLandingAccess.supportsSweptBoat(boat, 16.2, feet, 16.2, 16.8, 16.8, 1000));
        }
    }
}
