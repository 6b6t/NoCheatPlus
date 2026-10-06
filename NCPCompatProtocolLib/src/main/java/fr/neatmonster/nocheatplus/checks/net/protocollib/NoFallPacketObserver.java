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
    public NoFallPacketObserver(Plugin plugin) {
        super(plugin, ListenerPriority.MONITOR, PacketType.Play.Client.POSITION, PacketType.Play.Client.POSITION_LOOK);
    }

    @Override public void onPacketReceiving(PacketEvent event) {
        if (event.isCancelled() || event.getPlayer() == null || event.isPlayerTemporary()) return;
        final IPlayerData pData = DataManager.getPlayerDataSafe(event.getPlayer());
        if (pData == null || !pData.getCurrentWorldDataSafe().isCheckActive(CheckType.MOVING_NOFALL)) return;
        final var packet = event.getPacket();
        final DataPacketFlying receipt = MovingFlying.decodePacket(event.getPacketType(), packet.getBooleans().getValues(),
                packet.getDoubles().getValues(), packet.getFloat().getValues(), System.currentTimeMillis());
        publish(pData.getGenericInstance(NetData.class), receipt, event.isCancelled());
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
