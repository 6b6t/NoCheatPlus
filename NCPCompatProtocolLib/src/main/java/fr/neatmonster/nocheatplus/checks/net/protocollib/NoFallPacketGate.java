package fr.neatmonster.nocheatplus.checks.net.protocollib;

import org.bukkit.plugin.Plugin;
import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketEvent;
import fr.neatmonster.nocheatplus.checks.CheckType;
import fr.neatmonster.nocheatplus.checks.net.NetData;
import fr.neatmonster.nocheatplus.players.DataManager;

/** Stop overload before native admission rather than evicting unfinished proof.
 * Positionless traffic and teleport confirmations remain admitted. */
public final class NoFallPacketGate extends BaseAdapter {
    public NoFallPacketGate(Plugin plugin) {
        super(plugin, ListenerPriority.HIGHEST, PacketType.Play.Client.POSITION, PacketType.Play.Client.POSITION_LOOK);
    }
    @Override public void onPacketReceiving(PacketEvent event) {
        if (event.isCancelled() || event.getPlayer() == null || event.isPlayerTemporary()) return;
        final var data = DataManager.getPlayerDataSafe(event.getPlayer());
        if (data == null || !data.isCheckActive(CheckType.MOVING, event.getPlayer())
                || !data.isCheckActive(CheckType.MOVING_NOFALL, event.getPlayer())) return;
        final NetData net = data.getGenericInstance(NetData.class);
        if (net != null && !net.canAddNoFallPacket()) event.setCancelled(true);
    }
}
