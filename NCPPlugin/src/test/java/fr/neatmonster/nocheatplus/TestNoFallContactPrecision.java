package fr.neatmonster.nocheatplus;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import org.junit.Test;
import org.bukkit.entity.Player;
import fr.neatmonster.nocheatplus.checks.moving.*;
import fr.neatmonster.nocheatplus.checks.moving.model.*;
import fr.neatmonster.nocheatplus.checks.moving.player.NoFall;
import fr.neatmonster.nocheatplus.compat.versions.ServerVersion;
import fr.neatmonster.nocheatplus.utilities.location.PlayerLocation;
import fr.neatmonster.nocheatplus.players.IPlayerData;
public class TestNoFallContactPrecision {
 @Test public void movementStepToleranceAndTouchedGroundCannotConsumeAnAirborneFall()throws Exception{
  String old=ServerVersion.getMinecraftVersion();ServerVersion.setMinecraftVersion("26.2");
  try{
   NoFall check=mock(NoFall.class,CALLS_REAL_METHODS);Player p=mock(Player.class);
   MovingData d=mock(MovingData.class,CALLS_REAL_METHODS);MovingConfig cc=mock(MovingConfig.class);
   MoveTrace trace=mock(MoveTrace.class);TestClientNoFallSequences.field(d,MovingData.class,"playerMoves",trace);
   PlayerMoveData m=new PlayerMoveData();m.from.setLocation("world",16,82,16,0,0);m.to.setLocation("world",16,81.03,16,0,0);
   m.toIsValid=true;m.yDistance=-.97;m.to.onGround=true;m.touchedGround=true;
   when(trace.getCurrentMove()).thenReturn(m);d.noFallMaxY=93;d.noFallFallDistance=11;
   PlayerLocation from=mock(PlayerLocation.class),to=mock(PlayerLocation.class);
   when(to.isOnGround(.0625)).thenReturn(true);
   check.check(p,from,to,Double.NEGATIVE_INFINITY,d,cc,mock(IPlayerData.class));
   assertEquals(11.97,d.noFallFallDistance,1e-5);verify(p,never()).setFallDistance(anyFloat());
  }finally{ServerVersion.setMinecraftVersion(old);}
 }
 @Test public void supportUsesActualScaledFootprintAndRequiresPositiveOverlap(){
  assertFalse(NoFall.boxSupportsFeet(16.84,81,16,.3,15.5,80,15.5,16.5,81,16.5));
  assertTrue(NoFall.boxSupportsFeet(16.84,81,16,.6,15.5,80,15.5,16.5,81,16.5));
  assertFalse(NoFall.boxSupportsFeet(16.8,81,16,.3,15.5,80,15.5,16.5,81,16.5));
  assertFalse(NoFall.boxSupportsFeet(16,81.03,16,.3,15.5,80,15.5,16.5,81,16.5));
 }
}
