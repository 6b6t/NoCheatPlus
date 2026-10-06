package fr.neatmonster.nocheatplus;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.*;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.*;
import fr.neatmonster.nocheatplus.checks.*;
import fr.neatmonster.nocheatplus.checks.moving.*;
import fr.neatmonster.nocheatplus.checks.moving.model.*;
import fr.neatmonster.nocheatplus.checks.moving.player.NoFall;
import fr.neatmonster.nocheatplus.checks.moving.util.*;
import fr.neatmonster.nocheatplus.compat.*;
import fr.neatmonster.nocheatplus.compat.versions.ServerVersion;
import fr.neatmonster.nocheatplus.components.registry.event.IGenericInstanceHandle;
import fr.neatmonster.nocheatplus.players.IPlayerData;
import fr.neatmonster.nocheatplus.utilities.location.PlayerLocation;
/** Real queued owner callback with the observed retained Y81.153636 native
 * placement and actual block shape at Y81. The nearby-block controls cannot
 * turn unsupported air into a landing without a native downward collision. */
@RunWith(Parameterized.class)
public class TestNoFallSweptBlockContact {
 @Parameterized.Parameters(name="{0}")public static Collection<Object[]> cases(){return Arrays.asList(new Object[][]{
  {"native-sweep"},{"explicit-debt"},{"native-zero"},{"collision-after-owner-delay"},{"no-collision"},{"foreign-player"},{"foreign-block"},
  {"unloaded"},{"lateral"},{"gap"},{"different-native-point"},{"jump"},{"shorter-descent"},{"consumed"}});}
 final String mode;public TestNoFallSweptBlockContact(String mode){this.mode=mode;}
 @Test public void nativeSweepCompletesTheLandingBeforeTheJumpCanResetIt()throws Exception{
  String old=ServerVersion.getMinecraftVersion();ServerVersion.setMinecraftVersion("26.2");
  try(var bukkit=mockStatic(Bukkit.class);var folia=mockStatic(Folia.class);var movement=mockStatic(MovingUtil.class)){
   Player p=mock(Player.class);World world=mock(World.class);IPlayerData pd=mock(IPlayerData.class);Plugin plugin=mock(Plugin.class);
   var plugins=mock(org.bukkit.plugin.PluginManager.class);bukkit.when(Bukkit::getServer).thenReturn(mock(Server.class));bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);when(plugins.getPlugin("NoCheatPlus")).thenReturn(plugin);
   MovingData d=mock(MovingData.class,CALLS_REAL_METHODS);MovingConfig cc=mock(MovingConfig.class);MoveTrace moves=mock(MoveTrace.class);
   TestClientNoFallSequences.field(d,MovingData.class,"playerMoves",moves);
   var last=new PlayerMoveData();last.toIsValid=true;last.yDistance=mode.equals("jump")?.42:mode.equals("shorter-descent")?-.1:-1.195069711;
   last.to.setLocation("world",96.5,mode.equals("different-native-point")?81.16:81.15363618312385,16.5,0,0);when(moves.getFirstPastMove()).thenReturn(last);
   when(p.getLocation()).thenReturn(new Location(world,96.5,81.15363618312385,16.5));when(p.getBoundingBox()).thenReturn(new BoundingBox(96.2,81.1536,16.2,96.8,82.95,16.8));when(p.isOnline()).thenReturn(true);
   when(pd.getGenericInstance(MovingData.class)).thenReturn(d);when(pd.getGenericInstance(MovingConfig.class)).thenReturn(cc);when(pd.isCheckActive(CheckType.MOVING,p)).thenReturn(true);
   NoFall nf=mock(NoFall.class,CALLS_REAL_METHODS);when(nf.isEnabled(p,pd)).thenReturn(true);MCAccess access=mock(MCAccess.class);when(access.hasVerticalCollisionBelow(p)).thenReturn(!mode.equals("no-collision") && !mode.equals("collision-after-owner-delay"));
   folia.when(()->Folia.isOwnedByCurrentRegion(p)).thenReturn(!mode.equals("foreign-player"));folia.when(()->Folia.isOwnedByCurrentRegion(any(Location.class))).thenReturn(!mode.equals("foreign-block"));
   when(world.isChunkLoaded(anyInt(),anyInt())).thenReturn(!mode.equals("unloaded"));
   Block block=mock(Block.class),air=mock(Block.class);VoxelShape shape=mock(VoxelShape.class),empty=mock(VoxelShape.class);when(air.getCollisionShape()).thenReturn(empty);when(empty.getBoundingBoxes()).thenReturn(List.of());when(block.getCollisionShape()).thenReturn(shape);
   when(shape.getBoundingBoxes()).thenReturn(List.of(mode.equals("lateral")?new BoundingBox(0,0,0,.1,1,1):mode.equals("gap")?new BoundingBox(0,0,0,1,.5,1):new BoundingBox(0,0,0,1,1,1)));
   when(world.getBlockAt(anyInt(),anyInt(),anyInt())).thenReturn(air);when(world.getBlockAt(96,80,16)).thenReturn(block);
   IGenericInstanceHandle h=mock(IGenericInstanceHandle.class);when(h.getHandle()).thenReturn(access);MovingListener listener=mock(MovingListener.class,CALLS_REAL_METHODS);
   TestClientNoFallSequences.field(listener,CheckListener.class,"mcAccess",h);TestClientNoFallSequences.field(listener,MovingListener.class,"noFall",nf);
   PlayerMoveInfo info=mock(PlayerMoveInfo.class);PlayerLocation from=mock(PlayerLocation.class);TestClientNoFallSequences.field(info,MoveInfo.class,"from",from);AuxMoving aux=mock(AuxMoving.class);when(aux.usePlayerMoveInfo()).thenReturn(info);TestClientNoFallSequences.field(listener,MovingListener.class,"aux",aux);
   movement.when(()->MovingUtil.shouldCheckSurvivalFly(p,from,null,d,cc,pd)).thenReturn(true);
   float[] fd={mode.equals("native-zero")?0:11.408991f};double[] damage={0};when(p.getFallDistance()).thenAnswer(c->fd[0]);doAnswer(c->{fd[0]=c.getArgument(0);return null;}).when(p).setFallDistance(anyFloat());
   when(access.dispatchNativeLanding(p)).thenAnswer(c->{damage[0]+=Math.max(0,NoFall.getDamage(fd[0]));fd[0]=0;return true;});
   d.noFallMaxY=92.5626274;d.noFallFallDistance=11.408991f;if(mode.equals("explicit-debt"))d.noFallContactFallDebt=12;if(mode.equals("consumed")){d.clearNoFallData();fd[0]=0;}
   AtomicReference<Consumer<Object>> task=new AtomicReference<>();folia.when(()->Folia.runSyncDelayedTaskForEntity(eq(p),eq(plugin),any(),any(),eq(1L))).thenAnswer(c->{task.set(c.getArgument(2));return new Object();});
   var queue=MovingListener.class.getDeclaredMethod("queueNativeLanding",Player.class,MovingData.class,MovingConfig.class,IPlayerData.class);queue.setAccessible(true);queue.invoke(listener,p,d,cc,pd);if(task.get()!=null)task.get().accept(null);
   if(mode.equals("collision-after-owner-delay")) {when(access.hasVerticalCollisionBelow(p)).thenReturn(true);fd[0]=0;task.get().accept(null);}
   if(mode.equals("no-collision")) {for(int i=0;i<8;i++){if(d.noFallNativeLandingTaskPending)task.get().accept(null);}assertFalse(d.noFallNativeLandingTaskPending);}
   boolean dispatch=mode.equals("native-sweep")||mode.equals("explicit-debt")||mode.equals("native-zero")||mode.equals("collision-after-owner-delay");assertEquals(mode,dispatch?(mode.equals("explicit-debt")?9:8):0,damage[0],0);
   if(dispatch){assertEquals(0,d.noFallFallDistance,0);verify(access).dispatchNativeLanding(p);}else verify(access,never()).dispatchNativeLanding(any());
   if(mode.equals("foreign-block")||mode.equals("unloaded"))verify(world,never()).getBlockAt(anyInt(),anyInt(),anyInt());
  }finally{ServerVersion.setMinecraftVersion(old);}
 }
}
