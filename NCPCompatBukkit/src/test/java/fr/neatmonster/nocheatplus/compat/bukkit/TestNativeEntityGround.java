package fr.neatmonster.nocheatplus.compat.bukkit;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.Test;
import org.bukkit.entity.*;
import org.bukkit.Location;
import org.bukkit.util.BoundingBox;
import fr.neatmonster.nocheatplus.compat.Folia;
import fr.neatmonster.nocheatplus.compat.versions.ServerVersion;
public class TestNativeEntityGround {
 @Test public void cachedMovementEndpointMustActuallyTouchSolidEntityTop(){
  String old=ServerVersion.getMinecraftVersion();ServerVersion.setMinecraftVersion("26.2");
  try(var folia=mockStatic(Folia.class);var nativeAccess=mockStatic(NativeLandingAccess.class)){
   Player player=mock(Player.class);Entity boat=mock(Entity.class),cloud=mock(Entity.class);
   when(player.getUniqueId()).thenReturn(UUID.randomUUID());when(boat.getUniqueId()).thenReturn(UUID.randomUUID());
   when(cloud.getUniqueId()).thenReturn(UUID.randomUUID());
   when(boat.isValid()).thenReturn(true);when(cloud.isValid()).thenReturn(true);
   when(player.getNearbyEntities(1.5,2,1.5)).thenReturn(Arrays.asList(cloud,boat));
   when(player.getLocation()).thenReturn(new Location(null,16,83,16));
   when(boat.getBoundingBox()).thenReturn(new BoundingBox(15.5,80,15.5,16.5,81,16.5));
   folia.when(()->Folia.isOwnedByCurrentRegion(player)).thenReturn(true);
   folia.when(()->Folia.isOwnedByCurrentRegion(boat)).thenReturn(true);
   folia.when(()->Folia.isOwnedByCurrentRegion(cloud)).thenReturn(true);
   nativeAccess.when(()->NativeLandingAccess.canCollide(player,boat)).thenReturn(true);
   BlockCacheBukkitModern cache=mock(BlockCacheBukkitModern.class,CALLS_REAL_METHODS);
   assertTrue(cache.standsOnEntity(player,15.7,80.99999,15.7,16.3,81,16.3));
   assertFalse(cache.standsOnEntity(player,15.7,81.3,15.7,16.3,81.31,16.3));
   assertFalse(cache.standsOnEntity(player,16.6,80.99999,15.7,17.2,81,16.3));
   verify(cloud,never()).getBoundingBox();
   // Only a native collision at current feet permits the moving-boat margin.
   when(player.getLocation()).thenReturn(new Location(null,16,81.65,16));
   nativeAccess.when(()->NativeLandingAccess.hasCollisionBelow(player)).thenReturn(true);
   assertTrue(cache.standsOnEntity(player,15.7,81.64999,15.7,16.3,81.65,16.3));
   assertFalse(cache.standsOnEntity(player,15.7,81.59999,15.7,16.3,81.6,16.3));
   folia.when(()->Folia.isOwnedByCurrentRegion(boat)).thenReturn(false);
   assertFalse(cache.standsOnEntity(player,15.7,80.99999,15.7,16.3,81,16.3));
  }finally{ServerVersion.setMinecraftVersion(old);}
 }
}
