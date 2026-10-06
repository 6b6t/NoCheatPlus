package fr.neatmonster.nocheatplus.compat.bukkit;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import org.bukkit.entity.Player;
import org.bukkit.entity.Entity;
import org.junit.Test;
import fr.neatmonster.nocheatplus.compat.Folia;

public class TestNativeLandingAccess {
 public interface Craft { Object getHandle(); }
 public static class NativeType { public int updateInterval(){return 3;} }
 public static class Native {
  public boolean verticalCollisionBelow;
  public double xOld,yOld,zOld;
  public NativeType getType(){return new NativeType();}
  double x,y,z;boolean ground;int landings;
  boolean solid, impulse;
  public boolean isIgnoringFallDamageFromCurrentImpulse(){return impulse;}
  public void doCheckFallDamage(double x,double y,double z,boolean ground){this.x=x;this.y=y;this.z=z;this.ground=ground;landings++;}
  public boolean canCollideWith(Object other){return ((Native)other).solid;}
 }
 @Test public void missingLandingDispatchHasZeroDisplacementAndDoesNotCalculateDamage(){
  try(var folia=mockStatic(Folia.class)){
   Player p=mock(Player.class,withSettings().extraInterfaces(Craft.class));Native n=new Native();
   when(((Craft)p).getHandle()).thenReturn(n);folia.when(()->Folia.isOwnedByCurrentRegion(p)).thenReturn(true);
   assertTrue(NativeLandingAccess.dispatch(p));assertEquals(1,n.landings);
   assertEquals(0,n.x,0);assertEquals(0,n.y,0);assertEquals(0,n.z,0);assertTrue(n.ground);
   verify(p,never()).damage(anyDouble());verify(p,never()).setFallDistance(anyFloat());
  }
 }
 @Test public void hitBoxesDoNotImplySolidNativeCollisionAndForeignEntitiesAreNotRead(){
  try(var folia=mockStatic(Folia.class)){
   Player p=mock(Player.class,withSettings().extraInterfaces(Craft.class));
   Entity e=mock(Entity.class,withSettings().extraInterfaces(Craft.class));Native player=new Native(),other=new Native();
   when(((Craft)p).getHandle()).thenReturn(player);when(((Craft)e).getHandle()).thenReturn(other);
   folia.when(()->Folia.isOwnedByCurrentRegion(p)).thenReturn(true);
   folia.when(()->Folia.isOwnedByCurrentRegion(e)).thenReturn(false);
   assertFalse(NativeLandingAccess.canCollide(p,e));verify((Craft)e,never()).getHandle();
   folia.when(()->Folia.isOwnedByCurrentRegion(e)).thenReturn(true);
   assertFalse(NativeLandingAccess.canCollide(p,e));other.solid=true;assertTrue(NativeLandingAccess.canCollide(p,e));
   assertFalse(NativeLandingAccess.hasCollisionBelow(p));player.verticalCollisionBelow=true;
   assertTrue(NativeLandingAccess.hasCollisionBelow(p));
   assertFalse(NativeLandingAccess.hasNativeImpulse(p));player.impulse=true;
   assertTrue(NativeLandingAccess.hasNativeImpulse(p));
  }
 }
 @Test public void landingCannotRunOffTheOwningThread(){
  try(var folia=mockStatic(Folia.class)){
   Player p=mock(Player.class,withSettings().extraInterfaces(Craft.class));
   try{NativeLandingAccess.dispatch(p);fail("ownership required");}catch(IllegalStateException expected){}
   verifyNoInteractions(p);
  }
 }
 @Test public void sweptBoatContactUsesNativePositionNotVelocityOrPacketClaims(){
  try(var folia=mockStatic(Folia.class)){
   org.bukkit.entity.Boat boat=mock(org.bukkit.entity.Boat.class,withSettings().extraInterfaces(Craft.class));Native n=new Native();
   when(((Craft)boat).getHandle()).thenReturn(n);
   when(boat.getLocation()).thenReturn(new org.bukkit.Location(null,16,81,16));
   when(boat.getBoundingBox()).thenReturn(new org.bukkit.util.BoundingBox(15.5,81,15.5,16.5,81.5625,16.5));
   // The ground collision has zeroed velocity; previous position still records the sweep.
   n.xOld=16;n.yOld=81.6;n.zOld=16;
   assertFalse(NativeLandingAccess.supportsSweptBoat(boat,15.7,81.84805,15.7,16.3,16.3));
   verify((Craft)boat,never()).getHandle();
   folia.when(()->Folia.isOwnedByCurrentRegion(boat)).thenReturn(true);
   assertTrue(NativeLandingAccess.supportsSweptBoat(boat,15.7,81.84805,15.7,16.3,16.3));
   assertFalse(NativeLandingAccess.supportsSweptBoat(boat,15.7,82.2,15.7,16.3,16.3));
   assertFalse(NativeLandingAccess.supportsSweptBoat(boat,16.6,81.84805,15.7,17.2,16.3));
   n.xOld=17;
   assertFalse(NativeLandingAccess.supportsSweptBoat(boat,15.5,81.84805,15.7,15.7,16.3));
   n.xOld=16;
   n.yOld=81;assertFalse(NativeLandingAccess.supportsSweptBoat(boat,15.7,81.84805,15.7,16.3,16.3));
   n.yOld=80.5;assertFalse(NativeLandingAccess.supportsSweptBoat(boat,15.7,81.84805,15.7,16.3,16.3));
   verify(boat,never()).getVelocity();
  }
 }

 @Test public void trackedBoatBoxesExpireAndNativeTeleportsDiscardTheOldPath(){
  try(var folia=mockStatic(Folia.class)){
   org.bukkit.entity.Boat boat=mock(org.bukkit.entity.Boat.class,withSettings().extraInterfaces(Craft.class));Native n=new Native();
   java.util.concurrent.atomic.AtomicInteger tick=new java.util.concurrent.atomic.AtomicInteger(10);
   java.util.concurrent.atomic.AtomicReference<org.bukkit.Location> at=new java.util.concurrent.atomic.AtomicReference<>();
   java.util.concurrent.atomic.AtomicReference<org.bukkit.util.BoundingBox> box=new java.util.concurrent.atomic.AtomicReference<>();
   when(((Craft)boat).getHandle()).thenReturn(n);when(boat.getTicksLived()).thenAnswer(call->tick.get());
   when(boat.getLocation()).thenAnswer(call->at.get());when(boat.getBoundingBox()).thenAnswer(call->box.get());
   folia.when(()->Folia.isOwnedByCurrentRegion(boat)).thenReturn(true);
   n.xOld=16;n.zOld=16;double oldY=88;
   for(double y:new double[]{87.6,87.16,86.68,86.16,85.6}){
    n.yOld=oldY;at.set(new org.bukkit.Location(null,16,y,16));
    box.set(new org.bukkit.util.BoundingBox(15.5,y,15.5,16.5,y+0.5625,16.5));
    NativeLandingAccess.supportsSweptBoat(boat,15.7,90,15.7,16.3,16.3);oldY=y;tick.incrementAndGet();
   }
   tick.decrementAndGet();
   // The currently displayed contact is several native ticks behind the boat.
   assertTrue(NativeLandingAccess.supportsSweptBoat(boat,15.7,88,15.7,16.3,16.3));
   tick.set(18);n.yOld=85.6;
   assertFalse(NativeLandingAccess.supportsSweptBoat(boat,15.7,88,15.7,16.3,16.3));
   // A native teleport resets previous position, so no old collision is inherited.
   tick.set(19);n.yOld=80;at.set(new org.bukkit.Location(null,16,80,16));
   box.set(new org.bukkit.util.BoundingBox(15.5,80,15.5,16.5,80.5625,16.5));
   assertFalse(NativeLandingAccess.supportsSweptBoat(boat,15.7,86,15.7,16.3,16.3));
  }
 }

 @Test public void owningBoatSamplesContinueThroughAClientPacketPauseAndExpireWhenUnused(){
  try(var folia=mockStatic(Folia.class);var bukkit=mockStatic(org.bukkit.Bukkit.class)){
   org.bukkit.entity.Boat boat=mock(org.bukkit.entity.Boat.class,withSettings().extraInterfaces(Craft.class));Native n=new Native();
   var tick=new java.util.concurrent.atomic.AtomicInteger(3);
   var at=new java.util.concurrent.atomic.AtomicReference<>(new org.bukkit.Location(null,16,88.696654946153,16));
   when(((Craft)boat).getHandle()).thenReturn(n);when(boat.isValid()).thenReturn(true);
   when(boat.getTicksLived()).thenAnswer(call->tick.get());when(boat.getLocation()).thenAnswer(call->at.get());
   when(boat.getBoundingBox()).thenAnswer(call->{double y=at.get().getY();return new org.bukkit.util.BoundingBox(15.5,y,15.5,16.5,y+.5625,16.5);});
   folia.when(()->Folia.isOwnedByCurrentRegion(boat)).thenReturn(true);
   var plugin=mock(org.bukkit.plugin.Plugin.class);var plugins=mock(org.bukkit.plugin.PluginManager.class);
   bukkit.when(org.bukkit.Bukkit::getServer).thenReturn(mock(org.bukkit.Server.class));
   bukkit.when(org.bukkit.Bukkit::getPluginManager).thenReturn(plugins);when(plugins.getPlugin("NoCheatPlus")).thenReturn(plugin);
   var tasks=new java.util.ArrayDeque<java.util.function.Consumer<Object>>();
   var retired=new java.util.concurrent.atomic.AtomicReference<Runnable>();
   folia.when(()->Folia.runSyncDelayedTaskForEntity(eq(boat),eq(plugin),any(),any(),eq(1L)))
    .thenAnswer(call->{tasks.add(call.getArgument(2));retired.set(call.getArgument(3));return new Object();});
   n.xOld=16;n.zOld=16;n.yOld=88.816654946153;
   NativeLandingAccess.supportsSweptBoat(boat,15.7,90,15.7,16.3,16.3);
   NativeLandingAccess.supportsSweptBoat(boat,15.7,90,15.7,16.3,16.3);assertEquals(1,tasks.size());
   for(double dy:new double[]{.04,.08,.12,.16,.20}){
    n.yOld=at.get().getY();at.set(new org.bukkit.Location(null,16,n.yOld-dy,16));tick.incrementAndGet();
    tasks.remove().accept(null);assertEquals(1,tasks.size());
   }
   // No player packet sampled ticks 4-8. The owner task still retained the real contact plane.
   assertTrue(NativeLandingAccess.supportsSweptBoat(boat,15.7,89.18075494462711,15.7,16.3,16.3));
   clearInvocations((Craft)boat);tick.set(20);tasks.remove().accept(null);
   assertTrue(tasks.isEmpty());verify((Craft)boat,never()).getHandle();
   NativeLandingAccess.supportsSweptBoat(boat,15.7,90,15.7,16.3,16.3);
   var oldTask=tasks.remove();retired.get().run();
   NativeLandingAccess.supportsSweptBoat(boat,15.7,90,15.7,16.3,16.3);assertEquals(1,tasks.size());
   clearInvocations((Craft)boat);oldTask.accept(null);assertEquals(1,tasks.size());
   verify((Craft)boat,never()).getHandle();
  }
 }

}
