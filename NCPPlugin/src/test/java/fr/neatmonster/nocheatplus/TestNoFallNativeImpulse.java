package fr.neatmonster.nocheatplus;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import org.junit.Test;
import org.bukkit.entity.Player;
import fr.neatmonster.nocheatplus.checks.Check;
import fr.neatmonster.nocheatplus.checks.moving.*;
import fr.neatmonster.nocheatplus.checks.moving.model.*;
import fr.neatmonster.nocheatplus.checks.moving.player.NoFall;
import fr.neatmonster.nocheatplus.compat.MCAccess;
import fr.neatmonster.nocheatplus.compat.versions.ServerVersion;
import fr.neatmonster.nocheatplus.players.IPlayerData;
import fr.neatmonster.nocheatplus.components.registry.event.IGenericInstanceHandle;
public class TestNoFallNativeImpulse {
 @Test public void fullUpwardImpulseAccountingKeepsNativeReductionThroughLanding()throws Exception{ accounting(true); }
 @Test public void packetMotionAloneCannotDiscardEarlierDescent()throws Exception{ accounting(false); }
 private void accounting(boolean nativeImpulse)throws Exception{
  String old=ServerVersion.getMinecraftVersion();ServerVersion.setMinecraftVersion("26.2");
  try{
   NoFall check=mock(NoFall.class,CALLS_REAL_METHODS);Player p=mock(Player.class);
   MovingData d=mock(MovingData.class,CALLS_REAL_METHODS);MovingConfig cc=mock(MovingConfig.class);
   MoveTrace trace=mock(MoveTrace.class);TestClientNoFallSequences.field(d,MovingData.class,"playerMoves",trace);
   MCAccess access=mock(MCAccess.class);IGenericInstanceHandle<MCAccess> handle=mock(IGenericInstanceHandle.class);
   when(handle.getHandle()).thenReturn(access);TestClientNoFallSequences.field(check,Check.class,"mcAccess",handle);
   when(access.isIgnoringFallDamageFromCurrentImpulse(p)).thenReturn(nativeImpulse);
   float[] fd={1};when(p.getFallDistance()).thenAnswer(x->fd[0]);doAnswer(x->{fd[0]=x.getArgument(0);return null;}).when(p).setFallDistance(anyFloat());
   d.noFallFallDistance=6.594f;d.noFallMaxY=100;
   IPlayerData pd=mock(IPlayerData.class);
   TestClientNoFallSequences.move(check,p,pd,cc,d,trace,93.40618,93.66,false,false);
   if(nativeImpulse){assertEquals(1,d.noFallFallDistance,0);assertEquals(94.40618,d.noFallMaxY,1e-5);}
   // Server physics have accumulated 10.7 before the final one-block move.
   fd[0]=10.7f;
   TestClientNoFallSequences.move(check,p,pd,cc,d,trace,82,81,true,false);
   assertEquals(nativeImpulse?10.7:18,fd[0],1e-5);
   assertEquals(nativeImpulse?8:16,NoFall.getDamage(fd[0]+1),0);
  }finally{ServerVersion.setMinecraftVersion(old);}
 }
}
