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
}
