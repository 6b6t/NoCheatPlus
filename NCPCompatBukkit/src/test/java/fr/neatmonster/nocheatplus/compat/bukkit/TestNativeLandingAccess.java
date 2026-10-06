package fr.neatmonster.nocheatplus.compat.bukkit;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import org.bukkit.entity.Player;
import org.bukkit.entity.Entity;
import org.junit.Test;
import fr.neatmonster.nocheatplus.compat.Folia;

public class TestNativeLandingAccess {
 public interface Craft { Object getHandle(); }
 public static class Native {
  public boolean verticalCollisionBelow;
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
}
