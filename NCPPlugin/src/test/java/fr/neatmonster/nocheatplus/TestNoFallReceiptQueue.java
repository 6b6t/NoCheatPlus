package fr.neatmonster.nocheatplus;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import org.junit.Test;
import java.util.concurrent.*;
import fr.neatmonster.nocheatplus.checks.net.*;
import fr.neatmonster.nocheatplus.checks.net.model.DataPacketFlying;
import fr.neatmonster.nocheatplus.checks.net.protocollib.NoFallPacketObserver;
public class TestNoFallReceiptQueue {
 private NetData net() throws Exception {
  var c=mock(NetConfig.class);
  for(String f:new String[]{"flyingFrequencySeconds","flyingFrequencyRedundantSeconds","packetFrequencySeconds"})
   TestClientNoFallSequences.field(c,NetConfig.class,f,1);
  return new NetData(c);
 }
 @Test public void ownerAndFinalPacketPublicationCannotExposePendingOrLateCancelledProof() throws Exception {
  NetData d=net();var packet=new DataPacketFlying(false,16,81,16,123);
  var owner=Executors.newSingleThreadExecutor();
  try {
   // The enforcement queue is visible to net checks, but never to NoFall.
   d.addFlyingQueue(packet);assertEquals(0,owner.submit(()->d.copyNoFallPackets().length).get().intValue());
   NoFallPacketObserver.publish(d,packet,true);assertEquals(0,d.copyNoFallPackets().length);
   NoFallPacketObserver.publish(d,packet,false);assertEquals(1,owner.submit(()->d.copyNoFallPackets().length).get().intValue());
  } finally {owner.shutdownNow();}
 }
 @Test public void ownerConsumptionRejectsOldEndpointsButKeepsReceivedFuturePositions() throws Exception {
  NetData d=net();var contact=new DataPacketFlying(false,16,81,16,123);var future=new DataPacketFlying(false,16,81.01,16,124);
  d.addNoFallPacket(contact);d.addNoFallPacket(future);
  d.acknowledgeNoFallPosition(16,81,16,0);assertArrayEquals(new DataPacketFlying[]{future},d.copyNoFallPackets());
  d.acknowledgeNoFallPosition(16,81.01,16,0);assertEquals(0,d.copyNoFallPackets().length);
 }
 @Test public void identicalStationaryPacketsCoalesceAndLifecycleClearsAllReceipts() throws Exception {
  NetData d=net();for(int i=0;i<10000;i++)d.addNoFallPacket(new DataPacketFlying(false,16,81,16,i));
  assertEquals(1,d.copyNoFallPackets().length);d.clearFlyingQueue();assertEquals(0,d.copyNoFallPackets().length);
 }

 @Test public void sustainedNoEventTrafficAndGroundAlternationStayBoundedWithoutEvictingContact() throws Exception {
  for(boolean flags:new boolean[]{false,true}) {
   NetData d=net();DataPacketFlying contact=new DataPacketFlying(false,16,81,16,0);d.addNoFallPacket(contact);
   for(int i=0;i<100000;i++) d.addNoFallPacket(new DataPacketFlying(flags&&(i%2==0),16+(flags?0:(i%2)*0.001),81,16,i));
   assertTrue(d.copyNoFallPackets().length<=NetData.NO_FALL_POSITION_LIMIT);
   assertSame(contact,d.copyNoFallPackets()[0]);assertFalse(d.canAddNoFallPacket());
   d.acknowledgeNativeNoFallPosition(16.001,81,16);
   if(!flags)assertTrue(d.canAddNoFallPacket());
  }
 }
 @Test public void nativePlacementRetiresTinyMovesButKeepsOneContactForTheNextOwnerMove() throws Exception {
  NetData d=net();var contact=new DataPacketFlying(true,16,81,16,0);d.addNoFallPacket(contact);
  for(int i=0;i<10000;i++) {
   d.addNoFallPacket(new DataPacketFlying(i%2==0,16,81,16,i));
   d.acknowledgeNativeNoFallPosition(16,81,16);
   assertEquals(1,d.copyNoFallPackets().length);
  }
  assertTrue(d.beginNoFallRetirement());assertFalse(d.beginNoFallRetirement());d.endNoFallRetirement();
  d.acknowledgeNoFallPosition(16,81,16,d.copyNoFallPackets()[0].getSequence());assertEquals(0,d.copyNoFallPackets().length);
 }
 @Test public void ignoredAndDelayedOwnerTrafficCannotGrowMemoryOrEvictOutstandingLanding() throws Exception {
  NetData d=net();var contact=new DataPacketFlying(false,16,81,16,0);d.addNoFallPacket(contact);
  for(int i=0;i<100000;i++){d.addNoFallPacket(new DataPacketFlying(false,16+i*0.0000002,81,16,i));d.acknowledgeNativeNoFallPosition(100,100,100);}
  assertEquals(NetData.NO_FALL_POSITION_LIMIT,d.copyNoFallPackets().length);assertSame(contact,d.copyNoFallPackets()[0]);
  d.clearFlyingQueue();assertTrue(d.canAddNoFallPacket());
 }

 @Test public void ignoredBeforeTeleportAckReceiptsRetireOnlyForTheActualFinalNativeId() throws Exception {
  NetData d=net();d.expectNoFallTeleport(42);
  for(int i=0;i<100000;i++)d.addNoFallPacket(new DataPacketFlying(false,16+i*.000001,81,16,i));
  assertFalse(d.canAddNoFallPacket());assertFalse(d.confirmNoFallTeleport(41));
  assertEquals(NetData.NO_FALL_POSITION_LIMIT,d.copyNoFallPackets().length);
  assertTrue(d.confirmNoFallTeleport(42));assertEquals(0,d.copyNoFallPackets().length);assertTrue(d.canAddNoFallPacket());
  d.addNoFallPacket(new DataPacketFlying(false,16,81,16,100001));
  assertFalse(d.confirmNoFallTeleport(42));assertEquals(1,d.copyNoFallPackets().length);
  d.expectNoFallTeleport(43);d.clearFlyingQueue();assertFalse(d.confirmNoFallTeleport(43));
 }
}
