package fr.neatmonster.nocheatplus;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.persistence.*;
import org.bukkit.util.BoundingBox;
import org.junit.*;
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
/** Replays the observed 85.019 -> 84.459 grounded boat step, followed by
 * native FD zero and the first airborne 0.0784 step. The lower landing crosses
 * a damage boundary if the consumed 0.56 step leaks into the new fall. */
@RunWith(Parameterized.class)
public class TestNoFallGroundedBoatStep {
 @Parameterized.Parameters(name="{0}") public static Collection<Object[]> cases(){
  return Arrays.asList(new Object[][]{{"native-reset"},{"native-under-four"},{"native-positive-after-step"},{"native-glide-after-step"},{"damaging-step"},{"false-ground"},{"cancelled"},{"final-cancelled"},{"final-changed"},{"foreign-player"},{"foreign-boat"},
   {"non-collidable"},{"unsupported"},{"native-positive"},{"different-native-point"},{"explicit-contact"},
   {"lifecycle"},{"leave-existing-checkpoint"},{"leave-invalidated-checkpoint"}});
 }
 final String mode;public TestNoFallGroundedBoatStep(String mode){this.mode=mode;}
 @Test public void aProcessedPhysicalSmallStepMustNotInflateTheNextFall()throws Exception{
  String old=ServerVersion.getMinecraftVersion();ServerVersion.setMinecraftVersion("26.2");
  try(var folia=mockStatic(Folia.class);var glide=mockStatic(Bridge1_9.class)){
   Player p=mock(Player.class);Entity boat=mock(Entity.class);World world=mock(World.class);
   IPlayerData pd=mock(IPlayerData.class);MovingConfig cc=mock(MovingConfig.class);
   MovingData d=mock(MovingData.class,CALLS_REAL_METHODS);MoveTrace trace=mock(MoveTrace.class);
   TestClientNoFallSequences.field(d,MovingData.class,"playerMoves",trace);
   NoFall nf=mock(NoFall.class,CALLS_REAL_METHODS);MCAccess access=mock(MCAccess.class);
   IGenericInstanceHandle handle=mock(IGenericInstanceHandle.class);when(handle.getHandle()).thenReturn(access);
   TestClientNoFallSequences.field(nf,Check.class,"mcAccess",handle);
   when(pd.getGenericInstance(MovingConfig.class)).thenReturn(cc);
   NetData net=mock(NetData.class);when(pd.getGenericInstance(NetData.class)).thenReturn(net);
   folia.when(()->Folia.isOwnedByCurrentRegion(p)).thenReturn(!mode.equals("foreign-player"));
   folia.when(()->Folia.isOwnedByCurrentRegion(boat)).thenReturn(!mode.equals("foreign-boat"));
   when(p.getNearbyEntities(1.5,8.0,1.5)).thenReturn(List.of(boat));when(boat.isValid()).thenReturn(true);
   when(boat.getType()).thenReturn(EntityType.BOAT);when(access.canCollideWith(p,boat)).thenReturn(!mode.equals("non-collidable"));
   when(boat.getBoundingBox()).thenReturn(new BoundingBox(15.5,81,15.5,16.5,81.5625,16.5));
   when(p.getBoundingBox()).thenReturn(new BoundingBox(15.7,84,15.7,16.3,85.8,16.3));
   when(access.hasSweptBoatSupport(eq(boat),anyDouble(),anyDouble(),anyDouble(),anyDouble(),anyDouble()))
       .thenReturn(!mode.equals("unsupported"));
   float[] fd={0};double[] y={mode.equals("native-under-four")?88.35915494615299:mode.equals("damaging-step")?88.55915494615299:85.01915494615298};
   when(p.getFallDistance()).thenAnswer(c->fd[0]);doAnswer(c->{fd[0]=c.getArgument(0);return null;}).when(p).setFallDistance(anyFloat());
   when(p.getLocation()).thenAnswer(c->new Location(world,16,y[0],16));
   double contact=84.45915494615299,next=84.38075494462711;
   DataPacketFlying receipt=new DataPacketFlying(!mode.equals("false-ground"),16,contact,16,123);receipt.setSequence(1);
   if(mode.equals("cancelled"))receipt.setCancelled(true);
   when(net.copyNoFallPackets()).thenReturn(new DataPacketFlying[]{receipt});
   PlayerMoveData move=new PlayerMoveData();move.from.setLocation("world",16,y[0],16,0,0);move.to.setLocation("world",16,contact,16,0,0);
   move.toIsValid=true;move.yDistance=contact-y[0];when(trace.getCurrentMove()).thenReturn(move);
   PlayerLocation from=mock(PlayerLocation.class),to=mock(PlayerLocation.class);when(from.isOnGround(anyDouble())).thenReturn(true);
   nf.check(p,from,to,Double.NEGATIVE_INFINITY,d,cc,pd);
   float step=(float)(y[0]-contact);assertEquals(step,d.noFallFallDistance,1e-6);
   org.bukkit.event.player.PlayerMoveEvent finalEvent=mock(org.bukkit.event.player.PlayerMoveEvent.class);
   when(finalEvent.isCancelled()).thenReturn(mode.equals("final-cancelled"));
   when(finalEvent.getTo()).thenReturn(new Location(world,16,mode.equals("final-changed")?contact+.05:contact,16));
   var monitor=MovingListener.class.getDeclaredMethod("invalidateChangedBoatStep",MovingData.class,org.bukkit.event.player.PlayerMoveEvent.class);
   monitor.setAccessible(true);monitor.invoke(null,d,finalEvent);
   y[0]=mode.equals("different-native-point")?contact+.05:contact;
   fd[0]=mode.equals("native-positive")?.1f:(mode.equals("native-positive-after-step")||mode.equals("native-glide-after-step"))?.0784f:0;
   if(mode.equals("native-positive-after-step")||mode.equals("native-glide-after-step"))y[0]=next;
   if(mode.equals("native-glide-after-step")){glide.when(()->Bridge1_9.isGlidingWithElytra(p)).thenReturn(true);when(p.getVelocity()).thenReturn(new org.bukkit.util.Vector(0,-.0784,0));}
   if(mode.equals("explicit-contact"))d.noFallContactFallDebt=12;
   if(mode.equals("lifecycle")){d.clearNoFallData();assertNull(d.noFallBoatGroundStep);return;}
   if(mode.startsWith("leave-")){
    PersistentDataContainer pdc=mock(PersistentDataContainer.class);when(p.getPersistentDataContainer()).thenReturn(pdc);
    when(pdc.has(any(NamespacedKey.class),eq(PersistentDataType.LONG_ARRAY))).thenReturn(mode.equals("leave-existing-checkpoint"));
    nf.onLeave(p,d,pd);assertEquals(0,d.noFallFallDistance,0);verify(p,never()).setFallDistance(anyFloat());
    if(mode.equals("leave-existing-checkpoint"))verify(pdc,times(2)).remove(any(NamespacedKey.class));
    else verify(pdc,never()).remove(any(NamespacedKey.class));
    verify(pdc,never()).set(any(NamespacedKey.class),any(),any());return;
   }
   DataPacketFlying current=new DataPacketFlying(false,16,next,16,124);current.setSequence(2);
   when(net.copyNoFallPackets()).thenReturn(new DataPacketFlying[]{current});
   TestClientNoFallSequences.move(nf,p,pd,cc,d,trace,contact,next,false,false);
   if(mode.equals("native-reset")||mode.equals("native-under-four")||mode.equals("native-positive-after-step")||mode.equals("native-glide-after-step")){
    assertEquals(.0784f,d.noFallFallDistance,1e-6);assertEquals(contact,d.noFallMaxY,1e-7);
    fd[0]=.0784f;y[0]=next;
    DataPacketFlying landing=new DataPacketFlying(true,16,80.5625,16,125);landing.setSequence(3);
    when(net.copyNoFallPackets()).thenReturn(new DataPacketFlying[]{landing});
    TestClientNoFallSequences.move(nf,p,pd,cc,d,trace,next,80.5625,true,false);
    fd[0]+=(float)(next-80.5625);
    assertEquals(3.896655f,fd[0],1e-5);assertEquals("The old step would inflate native zero damage to one",0,NoFall.getDamage(fd[0]),0);
   }else{
    assertEquals("Unconfirmed receipt/geometry/native reset cannot consume descent",step+.0784f,d.noFallFallDistance,1e-6);
    if(mode.equals("explicit-contact"))assertEquals(12,d.noFallContactFallDebt,0);
   }
  }finally{ServerVersion.setMinecraftVersion(old);}
 }
}
