package fr.neatmonster.nocheatplus;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import fr.neatmonster.nocheatplus.checks.Check;
import fr.neatmonster.nocheatplus.checks.moving.*;
import fr.neatmonster.nocheatplus.checks.moving.model.*;
import fr.neatmonster.nocheatplus.checks.moving.player.NoFall;
import fr.neatmonster.nocheatplus.checks.net.NetData;
import fr.neatmonster.nocheatplus.checks.net.model.DataPacketFlying;
import fr.neatmonster.nocheatplus.compat.*;
import fr.neatmonster.nocheatplus.compat.versions.ServerVersion;
import fr.neatmonster.nocheatplus.components.registry.event.IGenericInstanceHandle;
import fr.neatmonster.nocheatplus.players.IPlayerData;
import fr.neatmonster.nocheatplus.utilities.location.PlayerLocation;

/** Actual accepted movement around the omitted true-ground packet from the
 * final mounted trace. Native dispatch is a narrow default-attribute model. */
@RunWith(Parameterized.class)
public class TestNoFallMissedBoatContact {
 @Parameterized.Parameters(name="{0}") public static Collection<Object[]> cases() {
  return Arrays.asList(new Object[][]{{"legitimate"},{"upward-departure"},{"owed-large"},{"split-small"},{"split-owed-large"},
   {"cancelled"},{"stale"},{"future"},{"no-current"},{"foreign-player"},{"foreign-boat"},
   {"unsupported"},{"non-collidable"},{"denied-ground"},{"native-positive"},{"status-only"},{"equals-from"}});
 }
 final String name;public TestNoFallMissedBoatContact(String name){this.name=name;}
 @Test public void omittedNativeContactCannotInflateTheLaterVanillaFall() throws Exception {
  String old=ServerVersion.getMinecraftVersion();ServerVersion.setMinecraftVersion("26.2");
  try(var folia=mockStatic(Folia.class)) {
   Player p=mock(Player.class);Entity boat=mock(Entity.class);IPlayerData pd=mock(IPlayerData.class);
   MovingData d=mock(MovingData.class,CALLS_REAL_METHODS);MovingConfig cc=mock(MovingConfig.class);
   MoveTrace trace=mock(MoveTrace.class);TestClientNoFallSequences.field(d,MovingData.class,"playerMoves",trace);
   NoFall nf=mock(NoFall.class,CALLS_REAL_METHODS);MCAccess access=mock(MCAccess.class);
   IGenericInstanceHandle handle=mock(IGenericInstanceHandle.class);when(handle.getHandle()).thenReturn(access);
   TestClientNoFallSequences.field(nf,Check.class,"mcAccess",handle);
   when(pd.getGenericInstance(MovingConfig.class)).thenReturn(cc);
   NetData net=mock(NetData.class);when(pd.getGenericInstance(NetData.class)).thenReturn(net);
   folia.when(()->Folia.isOwnedByCurrentRegion(p)).thenReturn(!name.equals("foreign-player"));
   folia.when(()->Folia.isOwnedByCurrentRegion(boat)).thenReturn(!name.equals("foreign-boat"));
   when(p.getNearbyEntities(1.5,8.0,1.5)).thenReturn(List.of(boat));when(boat.isValid()).thenReturn(true);
   when(boat.getType()).thenReturn(EntityType.BOAT);
   when(boat.getBoundingBox()).thenReturn(new BoundingBox(15.8,81,15.8,16.2,81.5625,16.2));
   when(p.getBoundingBox()).thenReturn(new BoundingBox(15.7,87,15.7,16.3,88.8,16.3));
   when(access.canCollideWith(p,boat)).thenReturn(!name.equals("non-collidable"));
   when(access.hasSweptBoatSupport(eq(boat),anyDouble(),anyDouble(),anyDouble(),anyDouble(),anyDouble()))
       .thenReturn(!name.equals("unsupported"));
   double contactY=name.equals("equals-from")?87.62655034851794:87.579154946153;
   double targetY=name.equals("upward-departure")?87.669154946153:87.50075494462712;
   World world=mock(World.class);Location[] position={new Location(world,16,contactY,16)};
   when(p.getLocation()).thenAnswer(c->position[0]);float[] distance={name.equals("native-positive")?.5f:0f};
   double[] damage={0};when(p.getFallDistance()).thenAnswer(c->distance[0]);
   doAnswer(c->{distance[0]=c.getArgument(0);return null;}).when(p).setFallDistance(anyFloat());
   when(access.dispatchNativeLanding(p)).thenAnswer(c->{damage[0]+=Math.max(0,NoFall.getDamage(distance[0]));distance[0]=0;return true;});
   DataPacketFlying contact=name.equals("status-only")?new DataPacketFlying(true,System.currentTimeMillis())
       :new DataPacketFlying(!name.equals("denied-ground"),16,contactY,16,System.currentTimeMillis());
   contact.setSequence(name.equals("future")?3:1);if(name.equals("cancelled"))contact.setCancelled(true);
   DataPacketFlying current=new DataPacketFlying(false,16,targetY,16,System.currentTimeMillis());current.setSequence(2);
   when(net.copyNoFallPackets()).thenReturn(name.equals("no-current")?new DataPacketFlying[]{contact}:new DataPacketFlying[]{current,contact});
   if(name.equals("stale"))d.noFallMatchedPacketSequence=1;
   d.noFallMaxY=name.contains("owed-large")?contactY+12:89.30075494462712;
   d.noFallFallDistance=name.contains("owed-large")?12:1.6742046f;
   if(name.startsWith("split-")) {
    // Production checkPlayerMove first checks from -> native location, then native location -> to.
    TestClientNoFallSequences.move(nf,p,pd,cc,d,trace,87.62655034851794,contactY,false,false);
    assertEquals(contactY,d.noFallMaxY,1e-7);assertEquals(0,d.noFallFallDistance,0);
    assertEquals(1,d.noFallMatchedPacketSequence);
    TestClientNoFallSequences.move(nf,p,pd,cc,d,trace,contactY,targetY,false,false);
   } else TestClientNoFallSequences.move(nf,p,pd,cc,d,trace,87.62655034851794,targetY,false,false);
   boolean accepted=List.of("legitimate","upward-departure","owed-large","split-small","split-owed-large","native-positive").contains(name);
   if(accepted) {
    assertEquals(name.equals("upward-departure")?targetY:contactY,d.noFallMaxY,1e-7);
    assertEquals(Math.max(0,contactY-targetY),d.noFallFallDistance,1e-5);
    assertEquals(name.contains("owed-large")?9:0,damage[0],0);
    // Native movement applies only its actual departure, not the stale Bukkit origin.
    distance[0]+=(float)Math.max(0,contactY-targetY);position[0]=new Location(world,16,targetY,16);
    DataPacketFlying landing=new DataPacketFlying(true,16,81.5625,16,System.currentTimeMillis());landing.setSequence(4);
    when(net.copyNoFallPackets()).thenReturn(new DataPacketFlying[]{landing});
    TestClientNoFallSequences.move(nf,p,pd,cc,d,trace,targetY,81.5625,true,false);
    distance[0]+=(float)(targetY-81.5625);
    assertEquals("The old geometric debt must not inflate native 3 to 4",3,NoFall.getDamage(distance[0]),0);
   } else {
    assertEquals("Unproven history must retain descent",89.30075494462712,d.noFallMaxY,1e-7);
    assertTrue(d.noFallFallDistance>1.7f);assertEquals(0,damage[0],0);
   }
   verify(access,times(name.contains("owed-large")?1:0)).dispatchNativeLanding(p);
   if(name.equals("foreign-player"))verify(p,never()).getNearbyEntities(anyDouble(),anyDouble(),anyDouble());
   if(name.equals("foreign-boat"))verify(boat,never()).getBoundingBox();
  } finally {ServerVersion.setMinecraftVersion(old);}
 }
}
