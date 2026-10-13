package fr.neatmonster.nocheatplus;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.util.concurrent.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.junit.Test;
import fr.neatmonster.nocheatplus.checks.*;
import fr.neatmonster.nocheatplus.checks.combined.*;
import fr.neatmonster.nocheatplus.checks.moving.*;
import fr.neatmonster.nocheatplus.checks.moving.model.*;
import fr.neatmonster.nocheatplus.checks.moving.player.NoFall;
import fr.neatmonster.nocheatplus.players.*;
import fr.neatmonster.nocheatplus.compat.Folia;
public class TestMovingMonitorRegions {
 @Test public void overlappingLegitMonitorsInDifferentWorldsRetainTheirOwnLocationsAndCheckpoint() throws Exception {
  var listener=mock(MovingListener.class,CALLS_REAL_METHODS);
  var nf=mock(NoFall.class);TestClientNoFallSequences.field(listener,MovingListener.class,"noFall",nf);
  TestClientNoFallSequences.field(listener,CheckListener.class,"mcAccess",mock(fr.neatmonster.nocheatplus.components.registry.event.IGenericInstanceHandle.class));
  var processing=new ConcurrentHashMap<String,PlayerMoveEvent>();
  TestClientNoFallSequences.field(listener,MovingListener.class,"processingEvents",processing);
  TestClientNoFallSequences.field(listener,MovingListener.class,"requestedMovePositions",new ConcurrentHashMap<>());
  var worlds=new World[]{mock(World.class),mock(World.class)};
  var players=new Player[]{mock(Player.class),mock(Player.class)};
  var events=new PlayerMoveEvent[2];var pds=new IPlayerData[2];var md=new MovingData[2];
  var firstRead=new CountDownLatch(1);var secondDone=new CountDownLatch(1);
  for(int i=0;i<2;i++) {
   int region=i;when(worlds[i].getName()).thenReturn("world"+i);when(players[i].getName()).thenReturn("Region"+i);
   Location pos=new Location(worlds[i],16,92,16);
   events[i]=new PlayerMoveEvent(players[i],new Location(worlds[i],16,93,16),pos);
   pds[i]=mock(IPlayerData.class);md[i]=mock(MovingData.class);
   when(pds[i].isCheckActive(CheckType.MOVING,players[i])).thenReturn(true);
   when(pds[i].getGenericInstance(MovingData.class)).thenReturn(md[i]);
   when(pds[i].getGenericInstance(MovingConfig.class)).thenReturn(mock(MovingConfig.class));
   when(pds[i].getGenericInstance(CombinedData.class)).thenReturn(mock(CombinedData.class));
   when(players[i].getPersistentDataContainer()).thenReturn(mock(org.bukkit.persistence.PersistentDataContainer.class));
   MoveTrace trace=mock(MoveTrace.class);PlayerMoveData past=new PlayerMoveData();past.toIsValid=true;
   past.to.setLocation(worlds[i].getName(),16,92,16,0,0);when(trace.getFirstPastMove()).thenReturn(past);
   TestClientNoFallSequences.field(md[i],MovingData.class,"playerMoves",trace);
   processing.put(players[i].getName(),events[i]);
   when(players[i].getLocation()).thenAnswer(c->{
    if(region==0 && firstRead.getCount()>0) { firstRead.countDown();assertTrue(secondDone.await(10,TimeUnit.SECONDS)); }
    if(region==1) assertTrue(firstRead.await(10,TimeUnit.SECONDS));
    return pos.clone();
   });
   // Baseline uses the shared destination overload and suffers the same overlap.
   when(players[i].getLocation(any(Location.class))).thenAnswer(c->{
    Location shared=c.getArgument(0);shared.setWorld(worlds[region]);shared.setX(16);shared.setY(92);shared.setZ(16);
    if(region==0){firstRead.countDown();assertTrue(secondDone.await(10,TimeUnit.SECONDS));}
    else assertTrue(firstRead.await(10,TimeUnit.SECONDS));return shared;
   });
  }
  var executor=Executors.newFixedThreadPool(2);
  try {
   var a=executor.submit(()->monitor(listener,players[0],events[0],pds[0],null));
   var b=executor.submit(()->monitor(listener,players[1],events[1],pds[1],secondDone));
   b.get(15,TimeUnit.SECONDS);a.get(15,TimeUnit.SECONDS);
   for(int i=0;i<2;i++){assertSame(worlds[i],events[i].getTo().getWorld());verify(md[i]).updateTrace(eq(players[i]),eq(events[i].getTo()),anyLong(),any());}
   assertTrue(processing.isEmpty());
  } finally {executor.shutdownNow();}
 }
 private void monitor(MovingListener listener,Player player,PlayerMoveEvent event,IPlayerData pd,CountDownLatch done) {
  try(var data=mockStatic(DataManager.class);var folia=mockStatic(Folia.class);var combined=mockStatic(Combined.class)) {
   data.when(()->DataManager.getPlayerData(player)).thenReturn(pd);folia.when(Folia::isFoliaServer).thenReturn(true);
   listener.onPlayerMoveMonitor(event);
  } finally {if(done!=null)done.countDown();}
 }
}
