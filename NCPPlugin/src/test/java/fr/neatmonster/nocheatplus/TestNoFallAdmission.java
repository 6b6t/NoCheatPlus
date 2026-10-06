package fr.neatmonster.nocheatplus;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import org.junit.Test;
import org.bukkit.entity.Player;
import com.comphenix.protocol.events.PacketEvent;
import fr.neatmonster.nocheatplus.checks.CheckType;
import fr.neatmonster.nocheatplus.checks.net.*;
import fr.neatmonster.nocheatplus.checks.net.model.DataPacketFlying;
import fr.neatmonster.nocheatplus.checks.net.protocollib.NoFallPacketGate;
import fr.neatmonster.nocheatplus.players.*;
import fr.neatmonster.nocheatplus.worlds.IWorldData;
public class TestNoFallAdmission {
 @Test public void overloadIsRejectedBeforeNativeAdmissionWithoutEvictingLandingProof() throws Exception {
  try(var manager=mockStatic(DataManager.class)) {
   Player p=mock(Player.class);PacketEvent event=mock(PacketEvent.class);when(event.getPlayer()).thenReturn(p);
   IPlayerData pd=mock(IPlayerData.class);manager.when(()->DataManager.getPlayerDataSafe(p)).thenReturn(pd);
   IWorldData world=mock(IWorldData.class);when(pd.getCurrentWorldDataSafe()).thenReturn(world);
   when(pd.isCheckActive(CheckType.MOVING,p)).thenReturn(true);
   when(pd.isCheckActive(CheckType.MOVING_NOFALL,p)).thenReturn(true);
   NetConfig c=mock(NetConfig.class);for(String f:new String[]{"flyingFrequencySeconds","flyingFrequencyRedundantSeconds","packetFrequencySeconds"})
    TestClientNoFallSequences.field(c,NetConfig.class,f,1);
   NetData net=new NetData(c);when(pd.getGenericInstance(NetData.class)).thenReturn(net);
   NoFallPacketGate gate=mock(NoFallPacketGate.class,CALLS_REAL_METHODS);
   for(int i=0;i<NetData.NO_FALL_POSITION_LIMIT;i++)net.addNoFallPacket(new DataPacketFlying(false,16+i*.000001,81,16,i));
   DataPacketFlying first=net.copyNoFallPackets()[0];gate.onPacketReceiving(event);verify(event).setCancelled(true);
   assertSame(first,net.copyNoFallPackets()[0]);assertEquals(NetData.NO_FALL_POSITION_LIMIT,net.copyNoFallPackets().length);
   net.acknowledgeNoFallPosition(first.getX(),81,16,first.getSequence());
   PacketEvent resumed=mock(PacketEvent.class);when(resumed.getPlayer()).thenReturn(p);
   gate.onPacketReceiving(resumed);verify(resumed,never()).setCancelled(anyBoolean());
   when(pd.isCheckActive(CheckType.MOVING_NOFALL,p)).thenReturn(false);
   net.addNoFallPacket(new DataPacketFlying(false,20,81,16,10000));
   PacketEvent disabled=mock(PacketEvent.class);when(disabled.getPlayer()).thenReturn(p);
   gate.onPacketReceiving(disabled);verify(disabled,never()).setCancelled(anyBoolean());
  }
 }
}
