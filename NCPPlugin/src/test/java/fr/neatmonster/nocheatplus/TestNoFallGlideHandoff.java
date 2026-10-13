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
import fr.neatmonster.nocheatplus.checks.CheckListener;
import fr.neatmonster.nocheatplus.checks.CheckType;
import fr.neatmonster.nocheatplus.checks.moving.*;
import fr.neatmonster.nocheatplus.checks.moving.model.*;
import fr.neatmonster.nocheatplus.checks.moving.player.NoFall;
import fr.neatmonster.nocheatplus.checks.moving.util.*;
import fr.neatmonster.nocheatplus.compat.*;
import fr.neatmonster.nocheatplus.compat.versions.ServerVersion;
import fr.neatmonster.nocheatplus.components.registry.event.IGenericInstanceHandle;
import fr.neatmonster.nocheatplus.players.IPlayerData;
import fr.neatmonster.nocheatplus.utilities.location.PlayerLocation;

/** REVIEW-LAB-3 problems 1-2 (fix-4 lab debug traces, LiquidBounce glide modes):
 * PacketJump: a +1e-9 packet makes the server jump and reset native FD; we
 * restore 11.4 for the landing packet, which native then reverts (untracked
 * position back at 81.1536), and the glide limit caps native FD to 1.
 * SpoofLanding: the last contact arrives below the move-event threshold and the
 * owner probe ran before the native-position completion, at native FD zero.
 * Glide jump: a rejected landing, then a server jump whose velocity triggers the
 * glide limit. Real NoFall/MovingListener code, narrow native dispatch model. */
@RunWith(Parameterized.class)
public class TestNoFallGlideHandoff {
 @Parameterized.Parameters(name="{0}") public static Collection<Object[]> cases(){
  return Arrays.asList(new Object[][]{{"packetjump-reverted-airborne"},{"packetjump-reverted-setback"},{"native-consumed-control"},
   {"sub-event-landing-probe-first"},{"sub-event-small-control"}});
 }
 final String name;public TestNoFallGlideHandoff(String n){name=n;}
 @Test public void restoredOrSubEventLandingIsChargedOnce()throws Exception{
  String old=ServerVersion.getMinecraftVersion();ServerVersion.setMinecraftVersion("26.2");
  try(var bukkit=mockStatic(Bukkit.class);var folia=mockStatic(Folia.class);var movement=mockStatic(MovingUtil.class)){
   Player p=mock(Player.class);IPlayerData pd=mock(IPlayerData.class);Plugin plugin=mock(Plugin.class);
   var plugins=mock(org.bukkit.plugin.PluginManager.class);
   bukkit.when(Bukkit::getServer).thenReturn(mock(Server.class));bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
   when(plugins.getPlugin("NoCheatPlus")).thenReturn(plugin);when(p.isOnline()).thenReturn(true);
   MovingData d=mock(MovingData.class,CALLS_REAL_METHODS);MovingConfig cc=mock(MovingConfig.class);
   MoveTrace trace=mock(MoveTrace.class);TestClientNoFallSequences.field(d,MovingData.class,"playerMoves",trace);
   NoFall nf=mock(NoFall.class,CALLS_REAL_METHODS);MCAccess access=mock(MCAccess.class);
   when(nf.isEnabled(p,pd)).thenReturn(true);when(pd.isCheckActive(CheckType.MOVING,p)).thenReturn(true);
   when(pd.getGenericInstance(MovingData.class)).thenReturn(d);when(pd.getGenericInstance(MovingConfig.class)).thenReturn(cc);
   IGenericInstanceHandle h=mock(IGenericInstanceHandle.class);when(h.getHandle()).thenReturn(access);
   TestClientNoFallSequences.field(nf,fr.neatmonster.nocheatplus.checks.Check.class,"mcAccess",h);
   PlayerMoveInfo info=mock(PlayerMoveInfo.class);PlayerLocation support=mock(PlayerLocation.class);
   TestClientNoFallSequences.field(info,MoveInfo.class,"from",support);
   movement.when(()->MovingUtil.shouldCheckSurvivalFly(p,support,null,d,cc,pd)).thenReturn(true);
   AuxMoving aux=mock(AuxMoving.class);when(aux.usePlayerMoveInfo()).thenReturn(info);
   MovingListener listener=mock(MovingListener.class,CALLS_REAL_METHODS);
   TestClientNoFallSequences.field(listener,MovingListener.class,"aux",aux);
   TestClientNoFallSequences.field(listener,MovingListener.class,"noFall",nf);
   TestClientNoFallSequences.field(listener,CheckListener.class,"mcAccess",h);
   AtomicReference<Consumer<Object>> task=new AtomicReference<>();
   folia.when(()->Folia.runSyncDelayedTaskForEntity(eq(p),eq(plugin),any(),any(),eq(1L)))
       .thenAnswer(c->{task.set(c.getArgument(2));return new Object();});
   folia.when(()->Folia.isOwnedByCurrentRegion(p)).thenReturn(true);
   Method queue=MovingListener.class.getDeclaredMethod("queueNativeLanding",Player.class,MovingData.class,MovingConfig.class,IPlayerData.class);
   queue.setAccessible(true);
   float[] fd={0};double[] damage={0};
   when(p.getFallDistance()).thenAnswer(c->fd[0]);
   doAnswer(c->{fd[0]=c.getArgument(0);return null;}).when(p).setFallDistance(anyFloat());
   when(access.dispatchNativeLanding(p)).thenAnswer(c->{damage[0]+=Math.max(0,NoFall.getDamage(fd[0]));fd[0]=0;return true;});
   World world=mock(World.class);Location[] at={null};when(p.getLocation()).thenAnswer(c->at[0]);
   final double top=81.15363618412385;
   if(name.startsWith("sub-event")) {
    // Accepted geometric descent kept; the last native packet landed below the event threshold.
    d.noFallMaxY=name.contains("small")?84.5:93.0;d.noFallFallDistance=(float)(d.noFallMaxY-81.0345);
    at[0]=new Location(world,16,81.0345,16);fd[0]=1.131f;
    queue.invoke(listener,p,d,cc,pd);
    fd[0]=0f;at[0]=new Location(world,16,81.0,16);when(support.isOnGround(anyDouble())).thenReturn(true);
    task.get().accept(null);
    assertEquals(name,name.contains("small")?0:9,damage[0],0);
    return;
   }
   // Gliding descent from 92.5626; the server jump at the +1e-9 packet reset native FD.
   d.noFallMaxY=92.5626270431092;d.noFallFallDistance=11.408991f;fd[0]=0f;at[0]=new Location(world,16,top,16);
   if(name.equals("native-consumed-control")){
    // Fix-6: only a landing native can process silently is consumed by native zero.
    // Damage cooldown (hit by 9 just before): hurtServer skips the FALL event of an 8.
    when(p.getNoDamageTicks()).thenReturn(19);when(p.getMaximumNoDamageTicks()).thenReturn(20);when(p.getLastDamage()).thenReturn(9.0);
   }
   TestClientNoFallSequences.move(nf,p,pd,cc,d,trace,top,81.0,true,false);
   assertEquals("The landing packet gets the accepted distance",11.4089,fd[0],1e-3);
   queue.invoke(listener,p,d,cc,pd);
   if(name.equals("native-consumed-control")) {
    // Native processed the landing (FD reset) without a damage event, e.g. cooldown.
    fd[0]=0f;at[0]=new Location(world,16,81.0,16);when(support.isOnGround(anyDouble())).thenReturn(true);
    task.get().accept(null);task.get().accept(null);
    assertEquals("A processed landing is never replayed",0,damage[0],0);
    verify(access,never()).dispatchNativeLanding(p);
    return;
   }
   // Native reverted the landing packet; next tick the glide limit capped FD to 1.
   fd[0]=1f;
   if(name.equals("packetjump-reverted-airborne")) {
    when(support.isOnGround(anyDouble())).thenReturn(false);
    Consumer<Object> first=task.get();first.accept(null);
    assertEquals(0,damage[0],0);assertTrue(d.noFallContactFallDebt>11.5f);
    assertNotSame("Airborne owed landing keeps one owner probe",first,task.get());
   }
   // Untracked-move set back (or a later packet) puts the player on the pad.
   at[0]=new Location(world,16,81.0,16);when(support.isOnGround(anyDouble())).thenReturn(true);
   task.get().accept(null);
   assertEquals("vanilla glide landing from 92.56 = 8",8,damage[0],0);
   verify(access,times(1)).dispatchNativeLanding(p);
  }finally{ServerVersion.setMinecraftVersion(old);}
 }

}
