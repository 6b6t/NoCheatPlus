package fr.neatmonster.nocheatplus;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import fr.neatmonster.nocheatplus.checks.moving.*;
import fr.neatmonster.nocheatplus.checks.moving.model.*;
import fr.neatmonster.nocheatplus.checks.moving.player.NoFall;
import fr.neatmonster.nocheatplus.compat.Bridge1_9;
import fr.neatmonster.nocheatplus.compat.versions.ServerVersion;
import fr.neatmonster.nocheatplus.players.IPlayerData;
/** Replays measured native dive coordinates through real NoFall methods.
 * Names identify source/runtime-visible ground-erasure boundaries, not client certification. */
@RunWith(Parameterized.class)
public class TestNoFallDiveSpoofs {
 @Parameterized.Parameters(name="{0}") public static Collection<Object[]> modes() {
  return Arrays.asList(new Object[][]{{"vanilla"},{"Wurst/allow-elytra"},{"LB/SpoofGround"},{"LB/SpoofLanding"},{"LB/NoGround"},
   {"LB/Packet"},{"LB/ForceJump"},{"LB/Spartan524Flag"},{"LB/Hypixel"},{"LB/HypixelPacket"},{"LB/PacketJump"},
   {"LB/Blink"},{"LB/Verus"},{"LB/Vulcan277"},{"Lambda/Grim"}});
 }
 private final String mode;public TestNoFallDiveSpoofs(String mode){this.mode=mode;}
 @Test public void steepDiveCannotLoseDistanceToGroundClaimsAndShallowTickStillLimitsIt() throws Exception {
  String old=ServerVersion.getMinecraftVersion();ServerVersion.setMinecraftVersion("26.2");
  try(var bridge=mockStatic(Bridge1_9.class)) {
   Player p=mock(Player.class);World w=mock(World.class);MovingData d=mock(MovingData.class,CALLS_REAL_METHODS);
   NoFall nf=mock(NoFall.class,CALLS_REAL_METHODS);MovingConfig cc=mock(MovingConfig.class);IPlayerData pd=mock(IPlayerData.class);
   MoveTrace trace=mock(MoveTrace.class);TestClientNoFallSequences.field(d,MovingData.class,"playerMoves",trace);
   bridge.when(()->Bridge1_9.isGlidingWithElytra(p)).thenReturn(true);
   final double[] ys={93,92.92159999847412,92.69107262956116,92.38675580210362,92.01012530386497,91.56262740688155,
    91.04567945777657,90.46067045626782,89.8089616221052,89.0918869506696,88.31075375745972,87.46684321168922,
    86.56141085921195,85.59568713498858,84.57087786530408,83.48816475994072,82.34870589450762,81.15363618312385,81};
   float[] fd={0};double[] pos={93};int[] tick={0};
   when(p.getLocation()).thenAnswer(c->new Location(w,16,pos[0],16));when(p.getFallDistance()).thenAnswer(c->fd[0]);
   when(p.getTicksLived()).thenAnswer(c->tick[0]);doAnswer(c->{fd[0]=c.getArgument(0);return null;}).when(p).setFallDistance(anyFloat());
   for(int i=1;i<ys.length;i++) {
    tick[0]=i;double dy=ys[i]-ys[i-1];when(p.getVelocity()).thenReturn(new Vector(0,dy,0));
    if(i>1 && ys[i-1]-ys[i-2]>-0.5)fd[0]=Math.min(1,fd[0]);
    if(!mode.equals("vanilla"))fd[0]=0;
    TestClientNoFallSequences.move(nf,p,pd,cc,d,trace,ys[i-1],ys[i],i==ys.length-1,false);
    fd[0]+=(float)-dy;pos[0]=ys[i];
   }
   assertEquals("Native control dive is 11.563 blocks",11.562627f,fd[0],1e-5);
   assertEquals(8,NoFall.getDamage(fd[0]),0);
   d.clearNoFallData();d.clearGlideDebt();fd[0]=9;pos[0]=90;when(p.getVelocity()).thenReturn(new Vector(0,-0.2,0));
   NoFall.onGlidingMove(p,d,-0.2);tick[0]++;fd[0]=0;NoFall.onGlidingMove(p,d,-0.2);
   assertEquals(1.2f,d.noFallFallDistance,1e-6);verify(p,never()).setFallDistance(9f);
  } finally {ServerVersion.setMinecraftVersion(old);}
 }
}
