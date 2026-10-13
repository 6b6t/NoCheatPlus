package fr.neatmonster.nocheatplus.checks.net.protocollib;
import org.bukkit.plugin.Plugin;
import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketEvent;
import fr.neatmonster.nocheatplus.checks.CheckType;
import fr.neatmonster.nocheatplus.checks.net.NetData;
import fr.neatmonster.nocheatplus.players.DataManager;
/** Observe the final native teleport ID; never alter packets or movement. */
public final class NoFallTeleportObserver extends BaseAdapter {
    public NoFallTeleportObserver(Plugin plugin) { super(plugin, ListenerPriority.MONITOR, PacketType.Play.Server.POSITION); }
    @Override public void onPacketSending(PacketEvent event) {
        if (event.isCancelled() || event.getPlayer()==null || event.isPlayerTemporary()) return;
        final var pd=DataManager.getPlayerDataSafe(event.getPlayer());
        if (pd==null || !pd.getCurrentWorldDataSafe().isCheckActive(CheckType.MOVING_NOFALL)) return;
        final var ids=event.getPacket().getIntegers();
        if (ids.size()==1 && ids.read(0)!=null) pd.getGenericInstance(NetData.class).expectNoFallTeleport(ids.read(0));
    }
}
