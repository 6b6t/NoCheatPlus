package fr.neatmonster.nocheatplus;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import fr.neatmonster.nocheatplus.checks.CheckType;
import fr.neatmonster.nocheatplus.checks.CheckListener;
import fr.neatmonster.nocheatplus.checks.moving.*;
import fr.neatmonster.nocheatplus.checks.moving.model.*;
import fr.neatmonster.nocheatplus.checks.moving.player.NoFall;
import fr.neatmonster.nocheatplus.checks.moving.util.*;
import fr.neatmonster.nocheatplus.compat.*;
import fr.neatmonster.nocheatplus.compat.versions.ServerVersion;
import fr.neatmonster.nocheatplus.components.registry.event.IGenericInstanceHandle;
import fr.neatmonster.nocheatplus.players.IPlayerData;
import fr.neatmonster.nocheatplus.utilities.location.PlayerLocation;
/** Actual NoFall movement and actual queued owning-entity callback, with a
 * narrow native dispatcher. Full native mitigation is verified in the lab. */
@RunWith(Parameterized.class)
public class TestClientNoFallNativeDispatch {
 @Parameterized.Parameters(name="{0}") public static Collection<Object[]> cases(){
  return Arrays.asList(new Object[][]{{"Vanilla",true},{"LB/NoGround",false},{"BleachHack/NoGround",false},
   {"LB/Hypixel/false-contact",false},{"LB/Hypixel/immediate-jump",false},{"LB/Blink/false-contact-burst",false},{"native-zero",false},{"teleport",false},{"water",false},
   {"air",false},{"disabled",false},{"parent-disabled",false},{"allow-flight",false},
   {"glide",false},{"vehicle",false},{"offline",false},{"dead",false},{"policy",false},{"replaced-data",false},{"retired/reconnect",false}});
 }
 final String name; final boolean wireGround;
 public TestClientNoFallNativeDispatch(String n,boolean g){name=n;wireGround=g;}
 @Test public void physicalStoneLandingMustProduceVanillaDamageExactlyOnce()throws Exception{
  String old=ServerVersion.getMinecraftVersion(); ServerVersion.setMinecraftVersion("26.2");
  try(var bukkit=mockStatic(Bukkit.class);var folia=mockStatic(Folia.class);var movement=mockStatic(MovingUtil.class)){
   Player p=mock(Player.class); IPlayerData pd=mock(IPlayerData.class); Plugin plugin=mock(Plugin.class);
   var plugins=mock(org.bukkit.plugin.PluginManager.class);
   bukkit.when(Bukkit::getServer).thenReturn(mock(Server.class));bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
   when(plugins.getPlugin("NoCheatPlus")).thenReturn(plugin);when(p.isOnline()).thenReturn(true);
   MovingData d=mock(MovingData.class,CALLS_REAL_METHODS); MovingConfig cc=mock(MovingConfig.class);
   MoveTrace trace=mock(MoveTrace.class); TestClientNoFallSequences.field(d,MovingData.class,"playerMoves",trace);
   NoFall nf=mock(NoFall.class,CALLS_REAL_METHODS); MCAccess access=mock(MCAccess.class);
   when(nf.isEnabled(p,pd)).thenReturn(true);when(pd.isCheckActive(CheckType.MOVING,p)).thenReturn(true);
   when(pd.getGenericInstance(MovingData.class)).thenReturn(d);when(pd.getGenericInstance(MovingConfig.class)).thenReturn(cc);
   IGenericInstanceHandle h=mock(IGenericInstanceHandle.class); when(h.getHandle()).thenReturn(access);
   TestClientNoFallSequences.field(nf,fr.neatmonster.nocheatplus.checks.Check.class,"mcAccess",h);
   PlayerMoveInfo info=mock(PlayerMoveInfo.class);PlayerLocation support=mock(PlayerLocation.class);
   TestClientNoFallSequences.field(info,MoveInfo.class,"from",support);
   when(support.isOnGround(anyDouble())).thenReturn(true);
   movement.when(()->MovingUtil.shouldCheckSurvivalFly(p,support,null,d,cc,pd)).thenReturn(true);
   AuxMoving aux=mock(AuxMoving.class);when(aux.usePlayerMoveInfo()).thenReturn(info);
   MovingListener listener=mock(MovingListener.class,CALLS_REAL_METHODS);
   TestClientNoFallSequences.field(listener,MovingListener.class,"aux",aux);
   TestClientNoFallSequences.field(listener,MovingListener.class,"noFall",nf);
   TestClientNoFallSequences.field(listener,CheckListener.class,"mcAccess",h);
   AtomicReference<Consumer<Object>> task=new AtomicReference<>();AtomicReference<Runnable> retired=new AtomicReference<>();
   folia.when(()->Folia.runSyncDelayedTaskForEntity(eq(p),eq(plugin),any(),any(),eq(1L)))
       .thenAnswer(c->{task.set(c.getArgument(2));retired.set(c.getArgument(3));return new Object();});
   Method queue=MovingListener.class.getDeclaredMethod("queueNativeLanding",Player.class,MovingData.class,MovingConfig.class,IPlayerData.class);
   queue.setAccessible(true);
   float[] distance={0};double[] damage={0};
   when(p.getFallDistance()).thenAnswer(c->distance[0]);
   doAnswer(c->{distance[0]=c.getArgument(0);return null;}).when(p).setFallDistance(anyFloat());
   when(access.dispatchNativeLanding(p)).thenAnswer(c->{damage[0]+=Math.max(0,NoFall.getDamage(distance[0]));distance[0]=0;return true;});
   World world=mock(World.class);when(p.getLocation()).thenReturn(new Location(world,16,81,16));
   d.noFallMaxY=93;double y=93;
   for(double next:new double[]{92.5,91.5,90,88,85,83,81}){
    TestClientNoFallSequences.move(nf,p,pd,cc,d,trace,y,next,next==81,false);
    queue.invoke(listener,p,d,cc,pd);
    distance[0]+=(float)(y-next);
    if(next==81 && wireGround) {damage[0]+=Math.max(0,NoFall.getDamage(distance[0]));distance[0]=0;d.clearNoFallData();}
    y=next;
   }
   assertNotNull(task.get());
   if(name.equals("retired/reconnect")){
    assertNotNull("Scheduler retirement must release the pending landing probe",retired.get());
    Consumer<Object> oldTask=task.get();retired.get().run();
    assertFalse(d.noFallNativeLandingTaskPending);
    queue.invoke(listener,p,d,cc,pd);assertNotSame(oldTask,task.get());
    oldTask.accept(null);assertEquals(0,damage[0],0);
   }
   boolean blocked=!name.contains("/") && !name.equals("Vanilla");
   if(name.equals("native-zero"))distance[0]=0;
   if(name.equals("teleport"))d.noFallGeneration++;
   if(name.equals("water"))when(support.isResetCond()).thenReturn(true);
   if(name.equals("air"))when(support.isOnGround(anyDouble())).thenReturn(false);
   if(name.equals("disabled"))when(nf.isEnabled(p,pd)).thenReturn(false);
   if(name.equals("parent-disabled"))when(pd.isCheckActive(CheckType.MOVING,p)).thenReturn(false);
   if(name.equals("allow-flight")){
    TestClientNoFallSequences.field(cc,MovingConfig.class,"noFallSkipAllowFlight",true);when(p.getAllowFlight()).thenReturn(true);
   }
   if(name.equals("glide")){try(var glide=mockStatic(Bridge1_9.class)){
    glide.when(()->Bridge1_9.isGlidingWithElytra(p)).thenReturn(true);task.get().accept(null);
    verify(access,never()).dispatchNativeLanding(p);return;
   }}
   if(name.equals("vehicle"))when(p.isInsideVehicle()).thenReturn(true);
   if(name.equals("offline"))when(p.isOnline()).thenReturn(false);
   if(name.equals("dead"))when(p.isDead()).thenReturn(true);
   if(name.equals("policy"))movement.when(()->MovingUtil.shouldCheckSurvivalFly(p,support,null,d,cc,pd)).thenReturn(false);
   if(name.equals("replaced-data"))when(pd.getGenericInstance(MovingData.class)).thenReturn(mock(MovingData.class));
   if(name.contains("immediate-jump")){
    when(support.getY()).thenReturn(81.0);
    Method finish=MovingListener.class.getDeclaredMethod("finishNativeLandingBeforeDeparture",Player.class,PlayerLocation.class,MovingData.class,MovingConfig.class,IPlayerData.class);
    finish.setAccessible(true);finish.invoke(listener,p,support,d,cc,pd);
   }
   task.get().accept(null);task.get().accept(null); // Replay must not submit a second landing.
   assertEquals(name,blocked?0:9,damage[0],0);
   verify(access,times(wireGround||blocked?0:1)).dispatchNativeLanding(p);
   folia.verify(()->Folia.runSyncDelayedTaskForEntity(eq(p),eq(plugin),any(),any(),eq(1L)),times(name.equals("retired/reconnect")?2:1));
  }finally{ServerVersion.setMinecraftVersion(old);}
 }
}
