package fr.neatmonster.nocheatplus;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.junit.Test;
import fr.neatmonster.nocheatplus.checks.ViolationData;
import fr.neatmonster.nocheatplus.compat.Folia;
import fr.neatmonster.nocheatplus.telemetry.*;
import fr.neatmonster.nocheatplus.utilities.location.RichBoundsLocation;

public class TestCreativeTelemetryBlocks {
    private RichBoundsLocation location(World world, int x) {
        RichBoundsLocation loc = mock(RichBoundsLocation.class);
        when(loc.getWorld()).thenReturn(world); when(loc.getBlockX()).thenReturn(x); when(loc.getBlockY()).thenReturn(64);
        return loc;
    }
    @Test public void creativeRejectionsReadLoadedOwnedCellsWithoutCheckCacheOrShapeQueries() {
        World world = mock(World.class); Block block = mock(Block.class);
        when(world.isChunkLoaded(anyInt(),anyInt())).thenReturn(true);
        when(world.getMinHeight()).thenReturn(-64); when(world.getMaxHeight()).thenReturn(320);
        when(world.getBlockAt(anyInt(),anyInt(),anyInt())).thenReturn(block); when(block.getType()).thenReturn(Material.OBSIDIAN);
        try(var folia=mockStatic(Folia.class)) {
            folia.when(()->Folia.isOwnedByCurrentRegion(any(Location.class))).thenReturn(true);
            Map<String,Object> snapshot=LoadedBlockSnapshot.capture(location(world,0));
            assertEquals(List.of(-1,63,-1),snapshot.get("origin")); assertEquals(List.of(3,4,3),snapshot.get("size"));
            List<?> cells=(List<?>)snapshot.get("cells"); assertEquals(36,cells.size());
            for(Object cell:cells){assertEquals("minecraft:obsidian",((List<?>)cell).get(0)); assertEquals(0,((List<?>)cell).get(2));}
            verify(world,times(36)).getBlockAt(anyInt(),anyInt(),anyInt());
            verify(block,never()).getBoundingBox(); verify(block,never()).getCollisionShape();
        }
    }
    @Test public void regionAndUnloadedChunkEdgesStayUnknownWithoutLoadingOrReadingForeignBlocks() {
        World world=mock(World.class); Block block=mock(Block.class);
        when(world.isChunkLoaded(0,0)).thenReturn(true); when(world.getMinHeight()).thenReturn(-64); when(world.getMaxHeight()).thenReturn(320);
        when(world.getBlockAt(anyInt(),anyInt(),anyInt())).thenReturn(block); when(block.getType()).thenReturn(Material.AIR);
        try(var folia=mockStatic(Folia.class)) {
            folia.when(()->Folia.isOwnedByCurrentRegion(any(Location.class))).thenAnswer(inv->((Location)inv.getArgument(0)).getBlockX()<16);
            Map<String,Object> snapshot=LoadedBlockSnapshot.capture(location(world,15));
            assertNotNull(snapshot); List<?> cells=(List<?>)snapshot.get("cells"); assertEquals(36,cells.size());
            verify(world,never()).getBlockAt(eq(16),anyInt(),anyInt());
            verify(world,never()).getBlockAt(anyInt(),anyInt(),eq(-1));
            verify(world,never()).isChunkLoaded(eq(1),anyInt());
            verify(world,never()).getChunkAt(anyInt(),anyInt());
            folia.when(()->Folia.isOwnedByCurrentRegion(any(Location.class))).thenReturn(false);
            clearInvocations(world); assertNull(LoadedBlockSnapshot.capture(location(world,15))); verifyNoInteractions(world);
        }
    }
    @Test public void disabledAndNonCancellingCreativeHooksNeverReadLocations() {
        RichBoundsLocation from=mock(RichBoundsLocation.class),to=mock(RichBoundsLocation.class);
        ViolationData vd=mock(ViolationData.class);
        Telemetry.install(null); Telemetry.creativeBlocks(vd,from,to); verifyNoInteractions(vd,from,to);
        Telemetry.install(new Telemetry.Sink(){public void begin(org.bukkit.event.player.PlayerMoveEvent e){} public void end(org.bukkit.event.player.PlayerMoveEvent e){} public void cancel(ViolationData d){} public void setback(org.bukkit.entity.Player p,String r){}});
        try{Telemetry.creativeBlocks(vd,from,to);verify(vd).willCancel();verifyNoInteractions(from,to);}finally{Telemetry.install(null);}
    }
}
