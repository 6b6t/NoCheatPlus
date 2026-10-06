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

/** REVIEW-LAB-3 problem 3 (mount8-enabled mount-air-0): after an airborne
 * dismount the client lands on the falling boat's displayed top (87.45915),
 * two native boat ticks behind the server. Vanilla resets there and lands with
 * FD 5.897 = 2 damage; the old check kept maxY 89.02 and wrote 7.25 = 4. */
@RunWith(Parameterized.class)
public class TestNoFallDisplayedBoatTop {
 @Parameterized.Parameters(name="{0}") public static Collection<Object[]> cases() {
  return Arrays.asList(new Object[][]{{"legitimate"},{"owed-large"},{"denied-ground"},{"no-packet"},{"unsupported"},
   {"non-collidable"},{"foreign-boat"},{"not-a-boat"}});
 }
 final String name;public TestNoFallDisplayedBoatTop(String name){this.name=name;}
 @Test public void displayedFallingBoatTopIsTheVanillaContact() throws Exception {
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
   folia.when(()->Folia.isOwnedByCurrentRegion(p)).thenReturn(true);
   folia.when(()->Folia.isOwnedByCurrentRegion(boat)).thenReturn(!name.equals("foreign-boat"));
   when(p.getNearbyEntities(1.5,8.0,1.5)).thenReturn(List.of(boat));when(boat.isValid()).thenReturn(true);
   when(boat.getType()).thenReturn(name.equals("not-a-boat")?EntityType.ARMOR_STAND:EntityType.BOAT);
   // The server boat already moved on (top 86.62); only its recent native frames passed 87.459.
   when(boat.getBoundingBox()).thenReturn(new BoundingBox(15.3,86.0567,15.3,16.7,86.6192,16.7));
   when(p.getBoundingBox()).thenReturn(new BoundingBox(15.7,87.874,15.7,16.3,89.674,16.3));
   when(access.canCollideWith(p,boat)).thenReturn(!name.equals("non-collidable"));
   final double from=87.87404824550134, contact=87.45915494615299;
   when(access.hasSweptBoatSupport(eq(boat),anyDouble(),anyDouble(),anyDouble(),anyDouble(),anyDouble()))
       .thenAnswer(c->!name.equals("unsupported") && Math.abs((double)c.getArgument(2)-contact)<1e-9);
   World world=mock(World.class);Location[] position={new Location(world,16,from,16)};
   when(p.getLocation()).thenAnswer(c->position[0]);
   float[] nativeFd={1.145f};double[] damage={0};
   when(p.getFallDistance()).thenAnswer(c->nativeFd[0]);
   doAnswer(c->{nativeFd[0]=c.getArgument(0);return null;}).when(p).setFallDistance(anyFloat());
   DataPacketFlying packet=new DataPacketFlying(!name.equals("denied-ground"),16,contact,16,System.currentTimeMillis());packet.setSequence(1);
   when(net.copyNoFallPackets()).thenReturn(name.equals("no-packet")?new DataPacketFlying[0]:new DataPacketFlying[]{packet});
   d.noFallMaxY=name.equals("owed-large")?from+8:89.02075494462711;
   d.noFallFallDistance=(float)(d.noFallMaxY-from);
   TestClientNoFallSequences.move(nf,p,pd,cc,d,trace,from,contact,false,false);
   boolean contactAccepted=List.of("legitimate","owed-large").contains(name);
   if(contactAccepted) {
    assertEquals("A consumed contact restarts at the contact, not the pre-contact height",contact,d.noFallMaxY,1e-9);
    assertEquals(0,d.noFallFallDistance,0);
    if(name.equals("owed-large")) {
     // Native processes this true-ground packet with the restored distance: one vanilla landing.
     assertEquals(8.0+0.415,nativeFd[0]+(from-contact),0.01);
     return;
    }
   } else {
    assertEquals("Unproven boat contact retains the descent",89.02075494462711,d.noFallMaxY,1e-9);
    assertEquals(89.02075494462711-contact,d.noFallFallDistance,1e-4);
   }
   // Native: the true-ground packet reset FD at the contact (vanilla), then descent to the pad.
   nativeFd[0]=contactAccepted?0f:nativeFd[0]+(float)(from-contact);
   position[0]=new Location(world,16,contact,16);
   when(net.copyNoFallPackets()).thenReturn(new DataPacketFlying[0]);
   TestClientNoFallSequences.move(nf,p,pd,cc,d,trace,contact,81.77075494462711,false,false);
   nativeFd[0]+=(float)(contact-81.77075494462711);
   position[0]=new Location(world,16,81.77075494462711,16);
   DataPacketFlying landing=new DataPacketFlying(true,16,81.5625,16,System.currentTimeMillis());landing.setSequence(2);
   when(net.copyNoFallPackets()).thenReturn(new DataPacketFlying[]{landing});
   TestClientNoFallSequences.move(nf,p,pd,cc,d,trace,81.77075494462711,81.5625,true,false);
   nativeFd[0]+=(float)(81.77075494462711-81.5625);
   if(name.equals("legitimate")) assertEquals("vanilla FD 5.897 = 2 damage, not 7.46 = 4",2,NoFall.getDamage(nativeFd[0]),0);
   else assertEquals("unproven contact keeps the whole descent",4,NoFall.getDamage(nativeFd[0]),0);
  } finally {ServerVersion.setMinecraftVersion(old);}
 }
}
