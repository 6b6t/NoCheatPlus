package fr.neatmonster.nocheatplus;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.bukkit.*;
import org.bukkit.entity.Player;
import fr.neatmonster.nocheatplus.checks.*;
import fr.neatmonster.nocheatplus.checks.moving.*;
import fr.neatmonster.nocheatplus.checks.moving.model.*;
import fr.neatmonster.nocheatplus.checks.moving.player.NoFall;
import fr.neatmonster.nocheatplus.checks.moving.util.*;
import fr.neatmonster.nocheatplus.checks.net.*;
import fr.neatmonster.nocheatplus.checks.net.protocollib.NoFallPacketObserver;
import fr.neatmonster.nocheatplus.compat.*;
import fr.neatmonster.nocheatplus.compat.versions.ServerVersion;
import fr.neatmonster.nocheatplus.components.NoCheatPlusAPI;
import fr.neatmonster.nocheatplus.components.registry.event.IGenericInstanceHandle;
import fr.neatmonster.nocheatplus.players.*;
import fr.neatmonster.nocheatplus.utilities.location.PlayerLocation;
/** Actual final packet observer and its owning-entity retirement callback.
 * Replays 81.034496 -> 81.0, which does not produce a Bukkit move event. */
@RunWith(Parameterized.class)
public class TestNoFallSubEventContact {
 @Parameterized.Parameters(name="{0}") public static Collection<Object[]> cases(){return Arrays.asList(new Object[][]{
  {"false-ground"},{"true-ground"},{"true-native-short-contact"},{"missed-contact-then-jump"},{"explicit-contact"},{"glide"},
  {"already-consumed"},{"air"},{"native-collision-absent"},{"cancelled"},{"future-position"},
  {"foreign-owner"},{"disabled"},{"parent-disabled"},{"policy"},{"water"},{"native-wind"},
  {"offline"},{"dead"},{"vehicle"},{"allow-flight"},{"lifecycle-reset"}});}
 final String mode; public TestNoFallSubEventContact(String mode){this.mode=mode;}
 @Test public void finalNativeContactMustDispatchOnceWithoutABukkitEvent()throws Exception{
  String old=ServerVersion.getMinecraftVersion();ServerVersion.setMinecraftVersion("26.2");
  try(var manager=mockStatic(DataManager.class);var provider=mockStatic(NCPAPIProvider.class);
      var folia=mockStatic(Folia.class);var movement=mockStatic(MovingUtil.class);var bridge=mockStatic(Bridge1_9.class)){
   Player p=mock(Player.class);World world=mock(World.class);IPlayerData pd=mock(IPlayerData.class);
   MovingData d=mock(MovingData.class,CALLS_REAL_METHODS);MovingConfig cc=mock(MovingConfig.class);
   MoveTrace moves=mock(MoveTrace.class);TestClientNoFallSequences.field(d,MovingData.class,"playerMoves",moves);
   NetConfig nc=mock(NetConfig.class);for(String f:new String[]{"flyingFrequencySeconds","flyingFrequencyRedundantSeconds","packetFrequencySeconds"})TestClientNoFallSequences.field(nc,NetConfig.class,f,1);
   NetData net=new NetData(nc);NoFall nf=mock(NoFall.class,CALLS_REAL_METHODS);MCAccess access=mock(MCAccess.class);
   IGenericInstanceHandle handle=mock(IGenericInstanceHandle.class);when(handle.getHandle()).thenReturn(access);
   MovingListener listener=mock(MovingListener.class,CALLS_REAL_METHODS);AuxMoving aux=mock(AuxMoving.class);
   PlayerMoveInfo info=mock(PlayerMoveInfo.class);PlayerLocation support=mock(PlayerLocation.class);
   TestClientNoFallSequences.field(info,MoveInfo.class,"from",support);when(aux.usePlayerMoveInfo()).thenReturn(info);
   TestClientNoFallSequences.field(listener,MovingListener.class,"aux",aux);TestClientNoFallSequences.field(listener,MovingListener.class,"noFall",nf);
   TestClientNoFallSequences.field(listener,CheckListener.class,"mcAccess",handle);
   NoCheatPlusAPI api=mock(NoCheatPlusAPI.class);provider.when(NCPAPIProvider::getNoCheatPlusAPI).thenReturn(api);
   when(api.getGenericInstance(MovingListener.class)).thenReturn(listener);
   manager.when(()->DataManager.getPlayerDataSafe(p)).thenReturn(pd);
   when(pd.getGenericInstance(NetData.class)).thenReturn(net);when(pd.getGenericInstance(MovingData.class)).thenReturn(d);when(pd.getGenericInstance(MovingConfig.class)).thenReturn(cc);
   when(pd.isCheckActive(CheckType.MOVING,p)).thenReturn(!mode.equals("parent-disabled"));when(pd.isCheckActive(CheckType.MOVING_NOFALL,p)).thenReturn(true);
   when(nf.isEnabled(p,pd)).thenReturn(!mode.equals("disabled"));
   when(p.isOnline()).thenReturn(!mode.equals("offline"));when(p.isDead()).thenReturn(mode.equals("dead"));when(p.isInsideVehicle()).thenReturn(mode.equals("vehicle"));
   when(p.getAllowFlight()).thenReturn(mode.equals("allow-flight"));TestClientNoFallSequences.field(cc,MovingConfig.class,"noFallSkipAllowFlight",true);
   when(p.getLocation()).thenReturn(new Location(world,96.5,81,16.5));
   folia.when(()->Folia.isOwnedByCurrentRegion(p)).thenReturn(!mode.equals("foreign-owner"));
   when(support.isOnGround(anyDouble())).thenReturn(!mode.equals("air"));when(support.isResetCond()).thenReturn(mode.equals("water"));
   when(access.hasVerticalCollisionBelow(p)).thenReturn(!mode.equals("native-collision-absent"));when(access.isIgnoringFallDamageFromCurrentImpulse(p)).thenReturn(mode.equals("native-wind"));
   movement.when(()->MovingUtil.shouldCheckSurvivalFly(p,support,null,d,cc,pd)).thenReturn(!mode.equals("policy"));
   bridge.when(()->Bridge1_9.isGlidingWithElytra(p)).thenReturn(mode.equals("glide"));
   d.noFallFallDistance=mode.equals("missed-contact-then-jump")?12.539898f:11.529f;d.noFallMaxY=92.5626274;
   if(mode.equals("explicit-contact"))d.noFallContactFallDebt=12;
   if(mode.equals("already-consumed"))d.clearNoFallData();
   if(mode.equals("true-native-short-contact")){d.noFallFallDistance=3.865f;d.noFallMaxY=84.9;}
   float[] fd={0};double[] damage={0};when(p.getFallDistance()).thenAnswer(c->fd[0]);doAnswer(c->{fd[0]=c.getArgument(0);return null;}).when(p).setFallDistance(anyFloat());
   when(access.dispatchNativeLanding(p)).thenAnswer(c->{damage[0]+=Math.max(0,NoFall.getDamage(fd[0]));fd[0]=0;return true;});
   var receipt=new fr.neatmonster.nocheatplus.checks.net.model.DataPacketFlying((mode.equals("true-ground")||mode.equals("true-native-short-contact")),96.5,mode.equals("future-position")?81.01:81.0,16.5,123);
   if(mode.equals("cancelled"))receipt.setCancelled(true);
   NoFallPacketObserver.publish(net,receipt,false);
   if(mode.equals("lifecycle-reset"))d.clearNoFallData();
   retire(p,pd,net);
   boolean dispatch=Set.of("false-ground","true-ground","missed-contact-then-jump","explicit-contact","glide").contains(mode);
   assertEquals(mode,dispatch?(mode.equals("explicit-contact")?9:8):0,damage[0],0);
   if(dispatch){assertEquals(0,d.noFallFallDistance,0);NoFallPacketObserver.publish(net,receipt,false);retire(p,pd,net);verify(access,times(1)).dispatchNativeLanding(p);}
   else {
    verify(access,never()).dispatchNativeLanding(any());
    if(mode.equals("policy")||mode.equals("disabled")||mode.equals("parent-disabled")) {
     assertEquals(0,d.noFallFallDistance,0);
     when(pd.isCheckActive(CheckType.MOVING,p)).thenReturn(true);when(nf.isEnabled(p,pd)).thenReturn(true);
     movement.when(()->MovingUtil.shouldCheckSurvivalFly(p,support,null,d,cc,pd)).thenReturn(true);
     NoFallPacketObserver.publish(net,receipt,false);retire(p,pd,net);verify(access,never()).dispatchNativeLanding(any());
    }
   }
  }finally{ServerVersion.setMinecraftVersion(old);}
 }
 // The old source has the same owner retirement but no contact hook. This
 // fallback runs that production method to demonstrate its missing dispatch.
 private static void retire(Player p,IPlayerData pd,NetData net)throws Exception{
  try{var m=NoFallPacketObserver.class.getDeclaredMethod("retireNativePosition",Player.class,IPlayerData.class,NetData.class);m.invoke(null,p,pd,net);}
  catch(NoSuchMethodException old){var loc=p.getLocation();net.acknowledgeNativeNoFallPosition(loc.getX(),loc.getY(),loc.getZ());}
 }

}
