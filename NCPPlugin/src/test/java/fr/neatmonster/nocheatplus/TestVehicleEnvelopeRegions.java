package fr.neatmonster.nocheatplus;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.*;
import java.lang.reflect.*;
import org.bukkit.entity.*;
import org.junit.Test;
import fr.neatmonster.nocheatplus.checks.moving.*;
import fr.neatmonster.nocheatplus.checks.moving.model.*;
import fr.neatmonster.nocheatplus.checks.moving.vehicle.*;
import fr.neatmonster.nocheatplus.checks.moving.magic.LostGroundVehicle;
import fr.neatmonster.nocheatplus.players.IPlayerData;
/** Enter the real public check and inspect scratch state at its external lost-ground
 * boundary. No mock physics result is used as a gameplay protection claim. */
public class TestVehicleEnvelopeRegions {
 private static final class EndFixture extends RuntimeException { }
 private static class Fixture {
  VehicleEnvelope check=mock(VehicleEnvelope.class,CALLS_REAL_METHODS);
  final Entity vehicle=mock(Entity.class);final Player player=mock(Player.class);
  final MovingData data=mock(MovingData.class);final MovingConfig config=mock(MovingConfig.class);
  final IPlayerData pd=mock(IPlayerData.class);final VehicleMoveInfo info=mock(VehicleMoveInfo.class);
  final VehicleMoveData move=new VehicleMoveData();
  Fixture(EntityType kind) throws Exception {
   when(vehicle.getType()).thenReturn(kind);var trace=mock(MoveTrace.class);
   TestClientNoFallSequences.field(data,MovingData.class,"vehicleMoves",trace);
  }
  void run() {try{check.check(player,vehicle,move,false,data,config,pd,info);fail("fixture boundary not reached");}catch(EndFixture expected){}}
 }
 @SuppressWarnings("unchecked") private ScopedValue<Object> context() throws Exception {
  Field f=VehicleEnvelope.class.getDeclaredField("CONTEXT");f.setAccessible(true);return (ScopedValue<Object>)f.get(null);
 }
 private Object details(Object context) throws Exception {Field f=context.getClass().getDeclaredField("details");f.setAccessible(true);return f.get(context);}
 @Test public void overlappingVehicleChecksDoNotShareTagsOrPhysicsDetails() throws Exception {
  Fixture a=new Fixture(EntityType.BOAT),b=new Fixture(EntityType.HORSE);
  b.check=a.check; // One registered check instance services both regions.
  var entered=new CountDownLatch(2);Object[] physics=new Object[2];
  var executor=Executors.newFixedThreadPool(2);
  try {
   var jobs=new ArrayList<Future<?>>();
   for(int i=0;i<2;i++) {
    int index=i;Fixture f=i==0?a:b;
    jobs.add(executor.submit(()->{
     try(var lost=mockStatic(LostGroundVehicle.class)) {
      lost.when(()->LostGroundVehicle.lostGround(eq(f.vehicle),any(),any(),anyDouble(),anyDouble(),anyBoolean(),any(),eq(f.data),eq(f.config),any(),anyList()))
       .thenAnswer(c->{
        List<String> tags=c.getArgument(10);physics[index]=details(context().get());
        entered.countDown();assertTrue(entered.await(10,TimeUnit.SECONDS));
        assertEquals(List.of("entity."+f.vehicle.getType()),tags);throw new EndFixture();
       });
      f.run();
     }
    }));
   }
   for(var job:jobs)job.get(15,TimeUnit.SECONDS);
   assertNotSame(physics[0],physics[1]);assertFalse(context().isBound());
  } finally {executor.shutdownNow();}
 }
 @Test public void nestedVehicleCheckRestoresOuterContextAndDiagnostics() throws Exception {
  Fixture outer=new Fixture(EntityType.BOAT),inner=new Fixture(EntityType.HORSE);inner.check=outer.check;
  Object[] physics=new Object[2];
  try(var lost=mockStatic(LostGroundVehicle.class)) {
   lost.when(()->LostGroundVehicle.lostGround(any(),any(),any(),anyDouble(),anyDouble(),anyBoolean(),any(),any(),any(),any(),anyList()))
    .thenAnswer(c->{
     List<String> tags=c.getArgument(10);
     if(c.getArgument(0)==outer.vehicle) {
      Object active=context().get();physics[0]=details(active);inner.run();assertSame(active,context().get());
      assertEquals(List.of("entity.BOAT"),tags);
     } else {physics[1]=details(context().get());assertEquals(List.of("entity.HORSE"),tags);}
     throw new EndFixture();
    });
   outer.run();
  }
  assertNotSame(physics[0],physics[1]);assertFalse(context().isBound());
 }
}
