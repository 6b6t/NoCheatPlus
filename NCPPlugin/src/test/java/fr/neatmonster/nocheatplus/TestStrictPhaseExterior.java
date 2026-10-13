package fr.neatmonster.nocheatplus;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.lang.reflect.Method;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.util.*;
import org.junit.*;
import fr.neatmonster.nocheatplus.compat.Folia;
import fr.neatmonster.nocheatplus.compat.bukkit.BlockCacheBukkitModern;
import fr.neatmonster.nocheatplus.utilities.location.RichBoundsLocation;
import fr.neatmonster.nocheatplus.utilities.map.*;

public class TestStrictPhaseExterior {
 static final double[] FULL={0,0,0,1,1,1};
 static class NativeCache extends FakeBlockCache {
  final Map<String,double[]> nativeBoxes=new HashMap<>();
  int queries;
  NativeCache(){put(8,64,8,FULL);put(8,63,8,FULL);}
  void put(int x,int y,int z,double... boxes){nativeBoxes.put(x+":"+y+":"+z,boxes);}
  @Override public double[] getCollisionBounds(int x,int y,int z){queries++;return nativeBoxes.getOrDefault(x+":"+y+":"+z,new double[0]);}
 }
 static boolean select(NativeCache cache,World world,double x,double z)throws Exception {
  RichBoundsLocation l=new RichBoundsLocation(cache);
  l.set(new Location(world,x,64,z),.6,1.8,.001);
  Method gate=RichBoundsLocation.class.getDeclaredMethod("hasClearNegativeExterior",int.class);
  gate.setAccessible(true);return (Boolean)gate.invoke(l,63);
 }
 @BeforeClass public static void setup()throws Exception{fr.neatmonster.nocheatplus.test.BlockTests.initBlockProperties();}
 World world(){World w=mock(World.class);when(w.getMaxHeight()).thenReturn(320);when(w.isChunkLoaded(anyInt(),anyInt())).thenReturn(true);return w;}
 @Test public void singleFaceRequiresFullWallContinuationAndEmptyOpenExterior()throws Exception {
  World w=world();try(var f=mockStatic(Folia.class)) {
   f.when(Folia::isFoliaServer).thenReturn(true);f.when(()->Folia.isOwnedByCurrentRegion(any(Location.class))).thenReturn(true);
   NativeCache c=new NativeCache();assertTrue(select(c,w,8,8.5));
   c.put(8,64,8,0,0,0,1,.5,1);assertFalse(select(c,w,8,8.5));
   c.put(8,64,8,FULL);c.put(8,63,8,0,0,0,1,.5,1);assertFalse(select(c,w,8,8.5));
   c.put(8,63,8,FULL);c.put(7,64,8,0,0,0,1,.0625,1);assertFalse(select(c,w,8,8.5));
   c.put(7,64,8);c.put(7,65,8,FULL);assertFalse(select(c,w,8,8.5));
   c.put(7,65,8);assertTrue(select(c,w,8,8.5));
  }
 }
 @Test public void emptyCollisionAboveExteriorStillRequiresAirLikeAEF3B()throws Exception {
  World w=world();try(var f=mockStatic(Folia.class)) {
   f.when(Folia::isFoliaServer).thenReturn(true);f.when(()->Folia.isOwnedByCurrentRegion(any(Location.class))).thenReturn(true);
   NativeCache c=new NativeCache();c.set(7,65,8,Material.TALL_GRASS);
   assertFalse(select(c,w,8,8.5));assertTrue(select(new NativeCache(),w,8,8.5));
  }
 }
 @Test public void verticesAndFullyOffFacePositionsQueryNoNativeGeometry()throws Exception {
  World w=world();NativeCache c=new NativeCache();
  assertFalse(select(c,w,8,8));assertFalse(select(c,w,Math.nextUp(8.0),8.5));
  assertFalse(select(c,w,Math.nextDown(8.0),8.5));assertEquals(0,c.queries);
 }
 @Test public void unavailableCentreAndExteriorKeepLiveAndNeverReadNativeGeometry()throws Exception {
  World w=world();NativeCache c=new NativeCache();
  when(w.isChunkLoaded(anyInt(),anyInt())).thenReturn(false);assertFalse(select(c,w,8,8.5));assertEquals(0,c.queries);
  when(w.isChunkLoaded(anyInt(),anyInt())).thenReturn(true);
  try(var f=mockStatic(Folia.class)) {
   f.when(Folia::isFoliaServer).thenReturn(true);f.when(()->Folia.isOwnedByCurrentRegion(any(Location.class))).thenReturn(false);
   assertFalse(select(c,w,8,8.5));assertEquals(0,c.queries);
  }
 }
 @Test public void protrudingLowerShapesThinWallAndHazardsRejectTheExterior()throws Exception {
  World w=world();try(var f=mockStatic(Folia.class)) {
   f.when(Folia::isFoliaServer).thenReturn(true);f.when(()->Folia.isOwnedByCurrentRegion(any(Location.class))).thenReturn(true);
   NativeCache c=new NativeCache();c.put(7,63,8,0,0,0,1,1.5,1);assertFalse(select(c,w,8,8.5));
   c.put(7,63,8);c.put(6,64,8,1.9999999,0,0,2,1,1);assertFalse(select(c,w,8,8.5));
   c.put(6,64,8);c.set(7,64,8,Material.LAVA);assertFalse(select(c,w,8,8.5));
  }
 }
 @Test public void paddedUnavailableOriginsAndUnknownShapesKeepLive()throws Exception {
  World w=world();try(var f=mockStatic(Folia.class)) {
   f.when(Folia::isFoliaServer).thenReturn(true);f.when(()->Folia.isOwnedByCurrentRegion(any(Location.class))).thenReturn(true);
   NativeCache c=new NativeCache();c.nativeBoxes.put("7:64:8",null);assertFalse(select(c,w,8,8.5));
   c.put(7,64,8);c.put(16,64,8,FULL);c.put(16,63,8,FULL);
   when(w.isChunkLoaded(1,0)).thenReturn(false);assertFalse(select(c,w,16,8.5));
  }
 }
 @Test public void nativeCacheIsSeparateGuardedAndExpiresOnCleanup() {
  World w=world();Block b=mock(Block.class);when(w.getBlockAt(7,65,8)).thenReturn(b);when(b.getType()).thenReturn(Material.WHITE_CARPET);
  VoxelShape empty=mock(VoxelShape.class),base=mock(VoxelShape.class);
  when(empty.getBoundingBoxes()).thenReturn(List.of());when(base.getBoundingBoxes()).thenReturn(List.of(new BoundingBox(0,0,0,1,.0625,1)));
  when(b.getCollisionShape()).thenReturn(empty);
  BlockCacheBukkitModern cache=new BlockCacheBukkitModern(new HashMap<>());cache.setAccess(w);
  try(var f=mockStatic(Folia.class)) {
   f.when(Folia::isFoliaServer).thenReturn(true);f.when(()->Folia.isOwnedByCurrentRegion(any(Location.class))).thenReturn(true);
   double[] movement=cache.getBounds(7,65,8);clearInvocations(w,b);
   double[] collision=cache.getCollisionBounds(7,65,8);assertEquals(0,collision.length);
   assertSame(collision,cache.getCollisionBounds(7,65,8));assertSame(movement,cache.getBounds(7,65,8));verify(b,times(1)).getCollisionShape();
   f.when(()->Folia.isOwnedByCurrentRegion(any(Location.class))).thenReturn(false);assertNull(cache.getCollisionBounds(7,65,8));verify(b,times(1)).getCollisionShape();
   f.when(()->Folia.isOwnedByCurrentRegion(any(Location.class))).thenReturn(true);
   cache.cleanup();cache.setAccess(w);when(b.getCollisionShape()).thenReturn(base);
   assertArrayEquals(new double[]{0,0,0,1,.0625,1},cache.getCollisionBounds(7,65,8),0);
   when(w.isChunkLoaded(0,0)).thenReturn(false);assertNull(cache.getCollisionBounds(7,65,8));
  }
 }
 @Test public void legacyCacheSuppliesNoClaimOfExactNativeGeometry(){assertNull(new FakeBlockCache().getCollisionBounds(0,64,0));}
}
