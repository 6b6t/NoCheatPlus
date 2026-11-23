/*
 * This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package fr.neatmonster.nocheatplus.compat.bukkit;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.World;
import org.junit.Before;
import org.junit.Test;

import fr.neatmonster.nocheatplus.checks.moving.magic.InBlockJumpGuard;
import fr.neatmonster.nocheatplus.test.BlockTests;
import fr.neatmonster.nocheatplus.utilities.location.PlayerLocation;
import fr.neatmonster.nocheatplus.utilities.map.FakeBlockCache;

/** Synthetic block-footprint tests for the opt-in phase guard predicate. */
public class TestPhaseGuardSequence {
    private FakeBlockCache blocks;
    private World world;

    @Before
    public void setup() {
        BlockTests.initBlockProperties();
        blocks = new FakeBlockCache();
        world = mock(World.class);
        when(world.getName()).thenReturn("phaseguard-test");
        // The player's 0.6-wide footprint occupies exactly the centre column.
        blocks.set(0, 64, 0, Material.STONE);
        blocks.set(0, 65, 0, Material.STONE);
    }

    @Test
    public void holdsOnlyInsideFullFootprintWithBlockedHead() {
        final List<String> tags = new ArrayList<>();
        assertTrue(InBlockJumpGuard.holdStaleGroundJump(position(64.0, 65.8), tags));
        assertEquals(List.of("phaseguard_hold"), tags);
    }

    @Test
    public void doesNotHoldWhenAnyFootColumnIsAirOrPartial() {
        blocks.set(-1, 64, 0, Material.AIR);
        assertFalse(InBlockJumpGuard.holdStaleGroundJump(position(64.0, 65.8, -0.2, 0.4), new ArrayList<>()));
        blocks.set(-1, 64, 0, Material.STONE_SLAB, new double[] {0, 0, 0, 1, 0.5, 1});
        assertFalse(InBlockJumpGuard.holdStaleGroundJump(position(64.0, 65.8, -0.2, 0.4), new ArrayList<>()));
    }

    @Test
    public void doesNotHoldWithClearOrCrawlingHead() {
        blocks.set(0, 65, 0, Material.AIR);
        assertFalse(InBlockJumpGuard.holdStaleGroundJump(position(64.0, 65.8), new ArrayList<>()));
        blocks.set(0, 65, 0, Material.STONE);
        assertFalse(InBlockJumpGuard.holdStaleGroundJump(position(64.0, 64.9), new ArrayList<>()));
    }

    @Test
    public void handlesFeetLayerEpsilonAndPartialHeadCollision() {
        blocks.set(0, 65, 0, Material.STONE);
        blocks.set(0, 66, 0, Material.STONE_SLAB, new double[] {0, 0, 0, 1, 0.5, 1});
        assertTrue(InBlockJumpGuard.holdStaleGroundJump(position(64.99999995, 66.8), new ArrayList<>()));
    }

    private PlayerLocation position(final double minY, final double maxY) {
        return position(minY, maxY, 0.2, 0.8);
    }

    private PlayerLocation position(final double minY, final double maxY, final double minX, final double maxX) {
        final PlayerLocation loc = mock(PlayerLocation.class);
        when(loc.getWorld()).thenReturn(world);
        when(loc.getBlockCache()).thenReturn(blocks);
        when(loc.getMinY()).thenReturn(minY);
        when(loc.getMaxY()).thenReturn(maxY);
        when(loc.getMinX()).thenReturn(minX);
        when(loc.getMaxX()).thenReturn(maxX);
        when(loc.getMinZ()).thenReturn(0.2);
        when(loc.getMaxZ()).thenReturn(0.8);
        return loc;
    }
}
