package fr.neatmonster.nocheatplus.checks.net.protocollib;

import org.bukkit.plugin.Plugin;
import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketEvent;
import fr.neatmonster.nocheatplus.checks.CheckType;
import fr.neatmonster.nocheatplus.checks.net.NetData;
import fr.neatmonster.nocheatplus.checks.net.model.DataPacketFlying;
import fr.neatmonster.nocheatplus.players.DataManager;
import fr.neatmonster.nocheatplus.players.IPlayerData;
import fr.neatmonster.nocheatplus.utilities.location.LocUtil;

/** Observe final packet admission without enforcing or changing packet state.
 * NoFall still requires accepted owner movement and physical support. */
public final class NoFallPacketObserver extends BaseAdapter {
    private static PacketType[] types() {
        var types = new java.util.ArrayList<PacketType>();
        types.add(PacketType.Play.Client.POSITION); types.add(PacketType.Play.Client.POSITION_LOOK);
        PacketType ack = ProtocolLibComponent.findPacketTypeByName(PacketType.Protocol.PLAY, PacketType.Sender.CLIENT, "AcceptTeleportation");
        if (ack == null) ack = ProtocolLibComponent.findPacketTypeByName(PacketType.Protocol.PLAY, PacketType.Sender.CLIENT, "TeleportAccept");
        if (ack != null) types.add(ack);
        return types.toArray(new PacketType[0]);
    }
    public NoFallPacketObserver(Plugin plugin) {
        super(plugin, ListenerPriority.MONITOR, types());
    }

    @Override public void onPacketReceiving(PacketEvent event) {
        if (event.isCancelled() || event.getPlayer() == null || event.isPlayerTemporary()) return;
        final IPlayerData pData = DataManager.getPlayerDataSafe(event.getPlayer());
        if (pData == null) return;
        if (event.getPacketType() != PacketType.Play.Client.POSITION && event.getPacketType() != PacketType.Play.Client.POSITION_LOOK) {
            final var ids = event.getPacket().getIntegers();
            if (ids.size() == 1 && ids.read(0) != null) pData.getGenericInstance(NetData.class).confirmNoFallTeleport(ids.read(0));
            return;
        }
        if (!pData.isCheckActive(CheckType.MOVING, event.getPlayer())
                || !pData.isCheckActive(CheckType.MOVING_NOFALL, event.getPlayer())) return;
        final var packet = event.getPacket();
        final DataPacketFlying receipt = MovingFlying.decodePacket(event.getPacketType(), packet.getBooleans().getValues(),
                packet.getDoubles().getValues(), packet.getFloat().getValues(), System.currentTimeMillis());
        final NetData net = pData.getGenericInstance(NetData.class);
        publish(net, receipt, event.isCancelled());
        if (net != null && net.beginNoFallRetirement()) {
            final var player = event.getPlayer();
            final Object task = fr.neatmonster.nocheatplus.compat.Folia.runSyncDelayedTaskForEntity(player, getPlugin(), ignored -> {
                try {
                    if (!player.isOnline() || pData.getGenericInstance(NetData.class) != net) return;
                    final var location = player.getLocation();
                    // Final native placement, including moves below Bukkit's event
                    // threshold, is an owner acknowledgment. Unmatched future
                    // positions remain pending; no time/size eviction is used.
                    net.acknowledgeNativeNoFallPosition(location.getX(), location.getY(), location.getZ());
                    if (player.isDead()) net.clearFlyingQueue();
                } finally { net.endNoFallRetirement(); }
            }, net::endNoFallRetirement, 1L);
            if (task == null || task instanceof Integer && ((Integer) task) == -1) net.endNoFallRetirement();
        }
    }

    /** Publication happens once, after the final outcome, never with a pending
     * default accepted flag. Kept separate to test the owner/network boundary. */
    public static void publish(NetData data, DataPacketFlying receipt, boolean cancelled) {
        if (data == null || receipt == null || !receipt.hasPos || cancelled || receipt.isCancelled()
                || LocUtil.isBadCoordinate(receipt.getX(), receipt.getY(), receipt.getZ())
                || receipt.hasLook && LocUtil.isBadCoordinate(receipt.getYaw(), receipt.getPitch())) return;
        data.addNoFallPacket(receipt);
    }
}
