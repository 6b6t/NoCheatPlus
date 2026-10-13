package fr.neatmonster.nocheatplus;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import org.junit.Test;
import org.bukkit.*;
import org.bukkit.entity.*;
import java.lang.reflect.Method;
import fr.neatmonster.nocheatplus.checks.moving.*;
import fr.neatmonster.nocheatplus.checks.moving.util.AuxMoving;
import fr.neatmonster.nocheatplus.checks.moving.vehicle.VehicleChecks;
import fr.neatmonster.nocheatplus.checks.moving.location.setback.DefaultSetBackStorage;
import fr.neatmonster.nocheatplus.players.IPlayerData;
public class TestVehicleMountPhase {
 @Test public void newAirborneMountCannotInheritPassengerFallingPhase()throws Exception{ enter(false,0); }
 @Test public void correctiveRemountRetainsExistingVehiclePhase()throws Exception{ enter(true,14); }
 private void enter(boolean correction,int phase)throws Exception{
  VehicleChecks checks=mock(VehicleChecks.class,CALLS_REAL_METHODS);MovingData d=mock(MovingData.class);
  AuxMoving aux=mock(AuxMoving.class);IPlayerData pd=mock(IPlayerData.class);MovingConfig cc=mock(MovingConfig.class);
  Player player=mock(Player.class);Entity vehicle=mock(Entity.class);World world=mock(World.class);
  when(pd.getGenericInstance(MovingConfig.class)).thenReturn(cc);
  when(vehicle.getLocation(any(Location.class))).thenAnswer(x->{Location l=x.getArgument(0);l.setWorld(world);l.setY(87.3);return l;});
  when(player.getLocation(any(Location.class))).thenAnswer(x->{Location l=x.getArgument(0);l.setWorld(world);l.setY(88.5);return l;});
  TestClientNoFallSequences.field(checks,VehicleChecks.class,"aux",aux);
  when(vehicle.getLocation()).thenReturn(new Location(world,0,87.3,0));
  when(player.getLocation()).thenReturn(new Location(world,0,88.5,0));
  DefaultSetBackStorage backs=mock(DefaultSetBackStorage.class);TestClientNoFallSequences.field(d,MovingData.class,"vehicleSetBacks",backs);
  // Elytra v3 ends its unequip window on vehicle enter; a constructor-less mock needs that (final) state.
  TestClientNoFallSequences.field(d,MovingData.class,"elytraResidual",new ElytraResidual.State());
  d.sfJumpPhase=14;d.isVehicleSetBack=correction;
  Method m=VehicleChecks.class.getDeclaredMethod("dataOnVehicleEnter",Player.class,Entity.class,MovingData.class,IPlayerData.class);m.setAccessible(true);m.invoke(checks,player,vehicle,d,pd);
  assertEquals(phase,d.sfJumpPhase);verify(aux).resetVehiclePositions(eq(vehicle),any(Location.class),eq(d),eq(cc));
  verify(backs,correction?never():times(1)).resetAll(any(Location.class));
 }
}
