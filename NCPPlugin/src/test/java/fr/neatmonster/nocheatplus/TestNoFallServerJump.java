package fr.neatmonster.nocheatplus;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.junit.Test;
import fr.neatmonster.nocheatplus.checks.moving.MovingData;
import fr.neatmonster.nocheatplus.checks.moving.player.NoFall;
import fr.neatmonster.nocheatplus.compat.Bridge1_9;
import fr.neatmonster.nocheatplus.compat.versions.ServerVersion;

/** REVIEW-LAB-3 problem 2 (LB SpoofLanding, revlab3 LB-elytra-more2 r2/r3): the
 * landing packet is rejected, the next upward packet makes the server jump from
 * its own ground state, and the jump velocity triggers the native glide limit.
 * The descent must survive as an explicit receipt for the next real contact. */
public class TestNoFallServerJump {
 @Test public void serverJumpWhileGlidingKeepsTheDescentAsAReceipt(){
  String old=ServerVersion.getMinecraftVersion();ServerVersion.setMinecraftVersion("26.2");
  try(var bridge=mockStatic(Bridge1_9.class)){
   for(String c:new String[]{"glide","not-gliding","small","vehicle"}){
    Player p=mock(Player.class);MovingData d=mock(MovingData.class,CALLS_REAL_METHODS);
    bridge.when(()->Bridge1_9.isGlidingWithElytra(p)).thenReturn(!c.equals("not-gliding"));
    when(p.isInsideVehicle()).thenReturn(c.equals("vehicle"));
    when(p.getLocation()).thenReturn(new Location(mock(World.class),16,81.1536,16));
    when(p.getVelocity()).thenReturn(new org.bukkit.util.Vector(0,0.42,0));
    d.noFallMaxY=c.equals("small")?84.0:92.7428;d.noFallFallDistance=(float)(d.noFallMaxY-81.1536);
    NoFall.onServerJump(p,d);
    // The jump velocity then triggers the native glide limit on the next ticks.
    when(p.getTicksLived()).thenReturn(1);NoFall.onGlidingMove(p,d,0.1796);
    when(p.getTicksLived()).thenReturn(2);NoFall.onGlidingMove(p,d,0.2481);
    assertTrue(c,d.noFallFallDistance<=1.3f);
    assertEquals(c,c.equals("glide")?11.5892f:0f,d.noFallContactFallDebt,1e-3);
   }
  }finally{ServerVersion.setMinecraftVersion(old);}
 }
}
