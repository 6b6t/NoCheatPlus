package fr.neatmonster.nocheatplus;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.lang.reflect.Method;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.junit.Test;
import fr.neatmonster.nocheatplus.checks.CheckListener;
import fr.neatmonster.nocheatplus.checks.CheckType;
import fr.neatmonster.nocheatplus.checks.moving.*;
import fr.neatmonster.nocheatplus.checks.moving.model.*;
import fr.neatmonster.nocheatplus.checks.moving.player.NoFall;
import fr.neatmonster.nocheatplus.checks.moving.util.*;
import fr.neatmonster.nocheatplus.compat.*;
import fr.neatmonster.nocheatplus.compat.versions.ServerVersion;
import fr.neatmonster.nocheatplus.components.registry.event.IGenericInstanceHandle;
import fr.neatmonster.nocheatplus.players.*;
import fr.neatmonster.nocheatplus.utilities.location.PlayerLocation;

/** Fix-5 items 1 and 2 (lab traces evidence-fix4-claude/c4-lb1 PacketJump r0-r11
 * and c4-lb2 SpoofLanding r7). Real MovingListener FALL handler and NoFall
 * check; native fall distance is a small explicit model.
 * PacketJump: NCP's own landing dispatch fires FALL while the server already has
 * the player on the jump arc (81.1536, rising). The still-airborne branch cancels
 * it and keeps the debt; the next move then starts on the 81.0 pad.
 * SpoofLanding: our set-back zeroes the downward speed at 82.19; the glide that
 * follows triggers the native glide limit (FD 1). */
public class TestNoFallRetainedDebt {
 static final double PAD=81.0, ARC=81.15363618412385;
 Player p=mock(Player.class);IPlayerData pd=mock(IPlayerData.class);MovingConfig cc=mock(MovingConfig.class);
 MovingData d=mock(MovingData.class,CALLS_REAL_METHODS);MoveTrace trace=mock(MoveTrace.class);
 NoFall nf=mock(NoFall.class,CALLS_REAL_METHODS);World world=mock(World.class);
 float[] fd={0};double[] y={ARC};

 void setUp()throws Exception{
  TestClientNoFallSequences.field(d,MovingData.class,"playerMoves",trace);
  TestClientNoFallSequences.field(d,MovingData.class,"vDistAcc",new fr.neatmonster.nocheatplus.utilities.ds.count.ActionAccumulator(3,3));
  when(nf.isEnabled(p,pd)).thenReturn(true);when(pd.isCheckActive(CheckType.MOVING,p)).thenReturn(true);
  when(pd.getGenericInstance(MovingData.class)).thenReturn(d);when(pd.getGenericInstance(MovingConfig.class)).thenReturn(cc);
  when(p.getFallDistance()).thenAnswer(c->fd[0]);
  doAnswer(c->{fd[0]=c.getArgument(0);return null;}).when(p).setFallDistance(anyFloat());
  when(p.getLocation()).thenAnswer(c->new Location(world,16,y[0],16));
  when(p.getBoundingBox()).thenAnswer(c->new org.bukkit.util.BoundingBox(15.7,y[0],15.7,16.3,y[0]+1.8,16.3));
  when(p.getVelocity()).thenReturn(new org.bukkit.util.Vector(0,-0.155,0));
 }

 /** One accepted Bukkit move; the native model adds descent afterwards and
  * charges the landing like LivingEntity.causeFallDamage. Returns damage. */
 double move(double from,double to,boolean fromGround,boolean toGround,boolean reset){
  PlayerMoveData m=new PlayerMoveData();m.from.setLocation("world",16,from,16,0,0);
  m.to.setLocation("world",16,to,16,0,0);m.toIsValid=true;m.yDistance=to-from;
  m.from.onGround=fromGround;m.to.onGround=toGround;m.to.resetCond=reset;
  when(trace.getCurrentMove()).thenReturn(m);
  PlayerLocation a=mock(PlayerLocation.class),b=mock(PlayerLocation.class);
  when(a.isOnGround(anyDouble())).thenReturn(fromGround);when(b.isOnGround(anyDouble())).thenReturn(toGround);
  nf.check(p,a,b,Double.NEGATIVE_INFINITY,d,cc,pd);
  y[0]=to;
  if(reset){fd[0]=0;return 0;}
  if(to<from)fd[0]+=(float)(from-to);
  if(!toGround)return 0;
  double dmg=Math.max(0,NoFall.getDamage(fd[0]));fd[0]=0;return dmg;
 }

 /** The FALL handler as production calls it, at the current position. */
 boolean fallEvent(double raw,boolean onGround)throws Exception{
  try(var manager=mockStatic(DataManager.class);var movement=mockStatic(MovingUtil.class)){
   manager.when(()->DataManager.getPlayerData(p)).thenReturn(pd);
   PlayerMoveInfo info=mock(PlayerMoveInfo.class);PlayerLocation at=mock(PlayerLocation.class);
   TestClientNoFallSequences.field(info,MoveInfo.class,"from",at);
   when(at.isOnGround(anyDouble())).thenReturn(onGround);
   movement.when(()->MovingUtil.shouldCheckSurvivalFly(p,at,null,d,cc,pd)).thenReturn(true);
   AuxMoving aux=mock(AuxMoving.class);when(aux.usePlayerMoveInfo()).thenReturn(info);
   MovingListener listener=mock(MovingListener.class,CALLS_REAL_METHODS);
   TestClientNoFallSequences.field(listener,MovingListener.class,"aux",aux);
   TestClientNoFallSequences.field(listener,MovingListener.class,"noFall",nf);
   IGenericInstanceHandle h=mock(IGenericInstanceHandle.class);when(h.getHandle()).thenReturn(mock(MCAccess.class));
   TestClientNoFallSequences.field(listener,CheckListener.class,"mcAccess",h);
   EntityDamageEvent event=new EntityDamageEvent(p,DamageCause.FALL,raw);
   Method check=MovingListener.class.getDeclaredMethod("checkFallDamageEvent",Player.class,EntityDamageEvent.class);
   check.setAccessible(true);check.invoke(listener,p,event);
   // Native resets fall distance after the event either way.
   fd[0]=0;
   return event.isCancelled();
  }
 }

 void packetJump(String c)throws Exception{
  String old=ServerVersion.getMinecraftVersion();ServerVersion.setMinecraftVersion("26.2");
  try(var folia=mockStatic(Folia.class);var bukkit=mockStatic(Bukkit.class)){
   setUp();
   // Gliding descent from 92.7; NCP restored 11.56 and dispatched the landing
   // (completeNativeLanding cleared its data) while native kept the arc.
   d.clearNoFallData();fd[0]=11.56f;y[0]=ARC;
   assertTrue("Airborne FALL is cancelled",fallEvent(8,false));
   assertEquals(11.56f,d.noFallCancelledAirFallDebt,1e-4);
   // The 1-tick restore task has not run yet: the next accepted move starts on the pad.
   double damage=0;
   if(c.equals("water")){
    // Control: the arc ends in water (a real reset), so nothing is owed.
    damage+=move(PAD,ARC,true,false,false);damage+=move(ARC,80.9,false,false,true);
    assertEquals(0f,NoFall.retainedNativeResetDebt(d),0);assertEquals(0f,d.noFallFallDistance,0);
   }
   else{
    damage+=move(PAD,ARC,true,false,false);
    assertEquals("Debt survives the departure from the pad",11.56f,Math.max(fd[0],NoFall.retainedNativeResetDebt(d)),1e-3);
    // About a second later the client lands on the pad.
    damage+=move(ARC,PAD,false,true,false);
    if(c.equals("no-replay")){
     // Native damage fired on the pad: the handler consumes the contact.
     y[0]=PAD;assertFalse(fallEvent(8,true));
     damage+=move(PAD,81.42,true,false,false);damage+=move(81.42,PAD,false,true,false);
    }
   }
   assertEquals(c,c.equals("water")?0:8,damage,0);
  }finally{ServerVersion.setMinecraftVersion(old);}
 }
 @Test public void packetJumpCancelledAirDebtIsChargedAtTheNextContact()throws Exception{packetJump("packetjump");}
 @Test public void packetJumpDebtIsChargedOnce()throws Exception{packetJump("no-replay");}
 @Test public void waterStillEndsACancelledAirDebt()throws Exception{packetJump("water");}

 double glide(String c)throws Exception{
  String old=ServerVersion.getMinecraftVersion();ServerVersion.setMinecraftVersion("26.2");
  try(var bridge=mockStatic(Bridge1_9.class)){
   setUp();
   boolean[] gliding={false};int[] tick={100};
   bridge.when(()->Bridge1_9.isGlidingWithElytra(p)).thenAnswer(x->gliding[0]);
   when(p.getTicksLived()).thenAnswer(x->tick[0]);
   double start=82.19347388999101;
   if(c.equals("setback")){
    // Accepted descent 93.0 -> 81.42 (11.58); the move to 81.75 is cancelled
    // and NCP sets the player back to 82.19 with zero vertical speed.
    d.noFallMaxY=93.0;d.noFallFallDistance=11.58f;fd[0]=11.58f;y[0]=81.42;
    d.noFallSetBackFallDebt=11.58f;
    // The cancelled move is SpoofLanding's jump after the dive: NoFall.checkDamage
    // marks that debt as an impossible glide reversal (FIX-8: kept in full).
    d.noFallSetBackReversal=true;
    y[0]=start;NoFall.afterSetBack(p,new Location(world,16,start,16),d);
   } else if(c.equals("dive-pull-up")){
    // Control: a real dive to FD 10.6 (no set-back), then pulling up; vanilla limits it.
    d.noFallMaxY=start+10.6;d.noFallFallDistance=10.6f;fd[0]=10.6f;y[0]=start;
   } else {
    // Control: deploy from a standing start at the same height.
    d.clearNoFallData();d.noFallMaxY=start;fd[0]=0;y[0]=start;
   }
   gliding[0]=true;double damage=0;
   double[] path={start,82.0382418854744,81.80771451656145,81.5033976891039,81.12676719086525};
   for(int i=1;i<path.length;i++){
    tick[0]++;
    double vy=c.equals("dive-pull-up")&&i==1?-1.1:-0.155;
    when(p.getVelocity()).thenReturn(new org.bukkit.util.Vector(0,vy,0));
    if(vy>-0.5)fd[0]=Math.min(1f,fd[0]); // native updateFallFlying limit
    damage+=move(path[i-1],path[i],false,false,false);
   }
   gliding[0]=false;tick[0]++;NoFall.stopGlide(p,d);
   damage+=move(path[path.length-1],PAD,false,true,false);
   return damage;
  }finally{ServerVersion.setMinecraftVersion(old);}
 }
 @Test public void setBackDebtSurvivesTheGlideLimitItCaused()throws Exception{
  assertEquals("vanilla elytra-fixture landing = 8",8,glide("setback"),0);
 }
 @Test public void legitGlideFromAStandingStartIsUnchanged()throws Exception{assertEquals(0,glide("standing"),0);}
 @Test public void legitDivePullUpLimitIsUnchanged()throws Exception{assertEquals(0,glide("dive-pull-up"),0);}
}
