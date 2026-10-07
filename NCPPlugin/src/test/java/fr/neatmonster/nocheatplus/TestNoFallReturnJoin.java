package fr.neatmonster.nocheatplus;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.Plugin;
import org.junit.Test;
import fr.neatmonster.nocheatplus.checks.CheckListener;
import fr.neatmonster.nocheatplus.checks.CheckType;
import fr.neatmonster.nocheatplus.checks.moving.*;
import fr.neatmonster.nocheatplus.checks.moving.model.*;
import fr.neatmonster.nocheatplus.checks.moving.player.FallStateSnapshot;
import fr.neatmonster.nocheatplus.checks.moving.player.NoFall;
import fr.neatmonster.nocheatplus.checks.moving.util.*;
import fr.neatmonster.nocheatplus.compat.*;
import fr.neatmonster.nocheatplus.compat.versions.ServerVersion;
import fr.neatmonster.nocheatplus.components.registry.event.IGenericInstanceHandle;
import fr.neatmonster.nocheatplus.players.IPlayerData;
import fr.neatmonster.nocheatplus.utilities.location.PlayerLocation;

/**
 * REVIEW-CODE-5 P2 (return-to-source worker): the official boundary trace
 * showed an inner return join with native FD 0 but local ncpDistance 35.86.
 * The player had left inner mid-air; the fall ended on outer (FALL there
 * cleared the checkpoint); a same-world rejoin reuses inner's MovingData
 * (dataOnJoin keeps it). Those leftovers must not be charged on inner.
 * Real join restore, owner probe and NoFall check; native is a small model.
 */
public class TestNoFallReturnJoin {
    Player p = mock(Player.class); IPlayerData pd = mock(IPlayerData.class); MovingConfig cc = mock(MovingConfig.class);
    NoFall nf = mock(NoFall.class, CALLS_REAL_METHODS); MCAccess access = mock(MCAccess.class);
    MovingListener listener = mock(MovingListener.class, CALLS_REAL_METHODS);
    PlayerLocation support = mock(PlayerLocation.class); World world = mock(World.class);
    Map<NamespacedKey, Object> pdc = new HashMap<>();
    float[] fd = {0}; double[] y = {81}; double damage = 0;
    AtomicReference<Consumer<Object>> task = new AtomicReference<>();

    MovingData data() throws Exception {
        MovingData d = mock(MovingData.class, CALLS_REAL_METHODS); MoveTrace trace = mock(MoveTrace.class);
        TestClientNoFallSequences.field(d, MovingData.class, "playerMoves", trace);
        TestClientNoFallSequences.field(d, MovingData.class, "vDistAcc", new fr.neatmonster.nocheatplus.utilities.ds.count.ActionAccumulator(3, 3));
        return d;
    }

    void move(MovingData d, double from, double to, boolean fromGround, boolean toGround) throws Exception {
        PlayerMoveData m = new PlayerMoveData(); m.from.setLocation("world", 508.5, from, 16.5, 0, 0);
        m.to.setLocation("world", 508.5, to, 16.5, 0, 0); m.toIsValid = true; m.yDistance = to - from;
        m.from.onGround = fromGround; m.to.onGround = toGround;
        when(d.playerMoves.getCurrentMove()).thenReturn(m);
        PlayerLocation a = mock(PlayerLocation.class), b = mock(PlayerLocation.class);
        when(a.isOnGround(anyDouble())).thenReturn(fromGround); when(b.isOnGround(anyDouble())).thenReturn(toGround);
        when(a.getY()).thenReturn(from);
        y[0] = from;
        Method finish = MovingListener.class.getDeclaredMethod("finishNativeLandingBeforeDeparture", Player.class, PlayerLocation.class, MovingData.class, MovingConfig.class, IPlayerData.class);
        finish.setAccessible(true); finish.invoke(listener, p, a, d, cc, pd);
        nf.check(p, a, b, Double.NEGATIVE_INFINITY, d, cc, pd);
        Method queue = MovingListener.class.getDeclaredMethod("queueNativeLanding", Player.class, MovingData.class, MovingConfig.class, IPlayerData.class);
        queue.setAccessible(true); queue.invoke(listener, p, d, cc, pd);
        // Native: descent adds, a ground packet lands, an upward packet resets.
        y[0] = to;
        if (to < from) fd[0] += (float) (from - to);
        if (toGround) { damage += Math.max(0, NoFall.getDamage(fd[0])); fd[0] = 0; }
        if (to > from) fd[0] = 0;
    }

    double returnJoin(boolean unfinishedElsewhere) throws Exception {
        String old = ServerVersion.getMinecraftVersion(); ServerVersion.setMinecraftVersion("26.2");
        try (var bukkit = mockStatic(Bukkit.class); var folia = mockStatic(Folia.class); var movement = mockStatic(MovingUtil.class)) {
            Plugin plugin = mock(Plugin.class); var plugins = mock(org.bukkit.plugin.PluginManager.class);
            bukkit.when(Bukkit::getServer).thenReturn(mock(Server.class)); bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
            when(plugins.getPlugin("NoCheatPlus")).thenReturn(plugin);
            folia.when(() -> Folia.runSyncDelayedTaskForEntity(eq(p), eq(plugin), any(), any(), eq(1L)))
                .thenAnswer(c -> { task.set(c.getArgument(2)); return new Object(); });
            folia.when(() -> Folia.isOwnedByCurrentRegion(p)).thenReturn(true);
            folia.when(Folia::isFoliaServer).thenReturn(true);
            movement.when(() -> MovingUtil.shouldCheckSurvivalFly(any(), any(), any(), any(), any(), any())).thenReturn(true);
            when(nf.isEnabled(p, pd)).thenReturn(true); when(pd.isCheckActive(CheckType.MOVING, p)).thenReturn(true);
            when(pd.getGenericInstance(MovingConfig.class)).thenReturn(cc); when(p.isOnline()).thenReturn(true);
            when(p.getFallDistance()).thenAnswer(c -> fd[0]);
            doAnswer(c -> { fd[0] = c.getArgument(0); return null; }).when(p).setFallDistance(anyFloat());
            when(p.getLocation()).thenAnswer(c -> new Location(world, 508.5, y[0], 16.5));
            when(p.getBoundingBox()).thenAnswer(c -> new org.bukkit.util.BoundingBox(508.2, y[0], 16.2, 508.8, y[0] + 1.8, 16.8));
            when(world.getKey()).thenReturn(NamespacedKey.minecraft("overworld"));
            PersistentDataContainer container = mock(PersistentDataContainer.class);
            when(p.getPersistentDataContainer()).thenReturn(container);
            doAnswer(c -> { Object v = c.getArgument(2); pdc.put(c.getArgument(0), v instanceof long[] ? ((long[]) v).clone() : v); return null; })
                .when(container).set(any(), any(), any());
            when(container.get(any(), any())).thenAnswer(c -> pdc.get(c.getArgument(0)));
            when(container.has(any(), any())).thenAnswer(c -> pdc.containsKey(c.getArgument(0)));
            doAnswer(c -> { pdc.remove(c.getArgument(0)); return null; }).when(container).remove(any());
            IGenericInstanceHandle h = mock(IGenericInstanceHandle.class); when(h.getHandle()).thenReturn(access);
            TestClientNoFallSequences.field(nf, fr.neatmonster.nocheatplus.checks.Check.class, "mcAccess", h);
            PlayerMoveInfo info = mock(PlayerMoveInfo.class); TestClientNoFallSequences.field(info, MoveInfo.class, "from", support);
            when(support.isOnGround(anyDouble())).thenReturn(true);
            AuxMoving aux = mock(AuxMoving.class); when(aux.usePlayerMoveInfo()).thenReturn(info);
            TestClientNoFallSequences.field(listener, MovingListener.class, "aux", aux);
            TestClientNoFallSequences.field(listener, MovingListener.class, "noFall", nf);
            TestClientNoFallSequences.field(listener, CheckListener.class, "mcAccess", h);
            when(access.dispatchNativeLanding(p)).thenAnswer(c -> { damage += Math.max(0, NoFall.getDamage(fd[0])); fd[0] = 0; return true; });

            // Inner: the player left mid-air (pad top 130, ncpDistance 35.86 at the boundary).
            MovingData inner = data();
            inner.noFallMaxY = 130.0; inner.noFallFallDistance = 35.858334f; fd[0] = 35.858334f;
            // MovingData.onPlayerLeave's NoFall part (the rest needs a constructed instance).
            inner.invalidateNativeLandingTask(); inner.noFallGeneration++;
            inner.noFallCancelledAirFallDebt = 0f; inner.noFallSetBackFallDebt = 0f; inner.noFallContactFallDebt = 0f;
            // Outer finished that fall (its FALL event cleared the checkpoint), or,
            // for the control, left it unfinished: 10 blocks still owed.
            pdc.clear();
            fd[0] = 0f;
            if (unfinishedElsewhere) {
                MovingData outer = data(); outer.noFallMaxY = 91.0; outer.noFallFallDistance = 10f; fd[0] = 10f; y[0] = 81.0;
                FallStateSnapshot.save(p, outer, new Location(world, 508.5, 81.0, 16.5));
                fd[0] = 0f; // Ground spoof erased native distance before the return.
            }
            // Same-world return to inner reuses its MovingData.
            when(pd.getGenericInstance(MovingData.class)).thenReturn(inner);
            y[0] = 81.0;
            Method join = MovingListener.class.getDeclaredMethod("restoreNoFallOnJoin", Player.class, MovingData.class, IPlayerData.class);
            join.setAccessible(true); join.invoke(listener, p, inner, pd);
            if (task.get() != null) { task.get().accept(null); task.get().accept(null); }
            // Sneak on the walkway, then jump twice on inner.
            move(inner, 81.0, 81.0, true, true);
            move(inner, 81.0, 81.42, true, false); move(inner, 81.42, 81.0, false, true);
            move(inner, 81.0, 81.42, true, false); move(inner, 81.42, 81.0, false, true);
            if (task.get() != null) task.get().accept(null);
            return damage;
        } finally { ServerVersion.setMinecraftVersion(old); }
    }

    @Test public void fallCompletedOnAnotherWorkerIsNotChargedAfterTheReturn() throws Exception {
        assertEquals("Vanilla: nothing after the return", 0, returnJoin(false), 0);
    }

    @Test public void fallStillUnfinishedOnTheCheckpointIsChargedOnceAfterTheReturn() throws Exception {
        assertEquals("The checkpoint's 10 blocks are owed at the first contact", 7, returnJoin(true), 0);
    }
}
