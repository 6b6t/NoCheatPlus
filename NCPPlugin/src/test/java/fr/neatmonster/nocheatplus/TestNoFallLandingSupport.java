package fr.neatmonster.nocheatplus;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.junit.Test;
import fr.neatmonster.nocheatplus.checks.moving.MovingData;
import fr.neatmonster.nocheatplus.checks.moving.model.MoveTrace;
import fr.neatmonster.nocheatplus.checks.moving.player.NoFall;
import fr.neatmonster.nocheatplus.compat.versions.ServerVersion;
import fr.neatmonster.nocheatplus.utilities.map.BlockFlags;

public class TestNoFallLandingSupport {
    private double landing(Material feet, Material support, double y, double damage) throws Exception {
        Player player = mock(Player.class); World world = mock(World.class);
        MovingData data = mock(MovingData.class);
        var trace = mock(MoveTrace.class);
        var field = MovingData.class.getField("playerMoves"); field.setAccessible(true); field.set(data, trace);
        when(player.getLocation()).thenReturn(new Location(world,16.5,y,16.5));
        when(world.getBlockAt(any(Location.class))).thenAnswer(call -> {
            Location at = call.getArgument(0); Block block = mock(Block.class);
            when(block.getType()).thenReturn(at.getBlockY() < (int)y ? support : feet);
            return block;
        });
        return NoFall.calcReducedDamageByBlock(player,data,damage);
    }
    @Test public void hayAndBedUseSupportBelowFeetAndThe26xFloor() throws Exception {
        String old = ServerVersion.getMinecraftVersion();
        try {
            ServerVersion.setMinecraftVersion("26.2");
            assertEquals(1, landing(Material.AIR,Material.HAY_BLOCK,81,9),0);
            // Partial-height bed occupies the same block as the player's feet.
            assertEquals(4, landing(Material.RED_BED,Material.AIR,80.5625,9),0);
        } finally { ServerVersion.setMinecraftVersion(old); }
    }
    @Test public void realPowderSnowAtFeetOrSupportCancelsFallDamage() throws Exception {
        try (var flags = mockStatic(BlockFlags.class)) {
            flags.when(() -> BlockFlags.getBlockFlags(Material.POWDER_SNOW)).thenReturn(BlockFlags.F_POWDERSNOW);
            assertEquals(0,landing(Material.POWDER_SNOW,Material.STONE,81,9),0);
            assertEquals(0,landing(Material.AIR,Material.POWDER_SNOW,82,9),0);
            assertEquals(9,landing(Material.AIR,Material.STONE,81,9),0);
        }
    }
    @Test public void fractionalFallDamageUsesThe26xNativeFloor() {
        String old = ServerVersion.getMinecraftVersion();
        try { ServerVersion.setMinecraftVersion("26.2"); assertEquals(9,NoFall.getDamage(12.9f),0); }
        finally { ServerVersion.setMinecraftVersion(old); }
    }
}
