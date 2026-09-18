package fr.neatmonster.nocheatplus;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Method;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.Test;
import fr.neatmonster.nocheatplus.checks.moving.MovingConfig;
import fr.neatmonster.nocheatplus.checks.moving.MovingData;
import fr.neatmonster.nocheatplus.checks.moving.MovingListener;
import fr.neatmonster.nocheatplus.checks.moving.model.MoveInfo;
import fr.neatmonster.nocheatplus.checks.moving.model.PlayerMoveInfo;
import fr.neatmonster.nocheatplus.compat.Folia;
import fr.neatmonster.nocheatplus.players.IPlayerData;
import fr.neatmonster.nocheatplus.utilities.location.PlayerLocation;
import fr.neatmonster.nocheatplus.utilities.map.MapUtil;

public class TestHoverChunkReadiness {
    @Test
    public void negativeCoordinatesLoadTheCorrectChunkOnBukkit() {
        final World world = mock(World.class);
        try (var folia = mockStatic(Folia.class)) {
            assertEquals(1, MapUtil.ensureChunksLoaded(world, -1, -17, 0));
            verify(world).getChunkAt(-1, -2);
            verify(world, never()).getChunkAt(0, -1);
        }
    }

    @Test
    public void sharedChunkLoaderNeverBlocksAFoliaTick() {
        final World world = mock(World.class);
        try (var folia = mockStatic(Folia.class)) {
            folia.when(Folia::isFoliaServer).thenReturn(true);
            assertEquals(0, MapUtil.ensureChunksLoaded(world, 15.9, -16.1, 1));
            verify(world, never()).getChunkAt(anyInt(), anyInt());
        }
    }

    @Test
    public void missingNeighborDefersBeforeAnyBlockInspection() throws Exception {
        checkHover(false, true);
    }

    @Test
    public void loadedForeignRegionDefersBeforeAnyBlockInspection() throws Exception {
        checkHover(true, false);
    }

    @Test
    public void ownedLoadedNeighborsStillRunTheHoverCheckWithoutLoading() throws Exception {
        checkHover(true, true);
    }

    private void checkHover(boolean loaded, boolean owned) throws Exception {
        final World world = mock(World.class);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(loaded);
        final Player player = mock(Player.class);
        final Location location = new Location(world, -0.1, 80, 15.9);
        when(player.getLocation()).thenReturn(location);
        when(player.getWidth()).thenReturn(0.6);
        final MovingListener listener = mock(MovingListener.class, CALLS_REAL_METHODS);
        final MovingData data = mock(MovingData.class);
        data.sfHoverTicks = 100;
        final MovingConfig config = mock(MovingConfig.class);
        final IPlayerData playerData = mock(IPlayerData.class);
        final PlayerMoveInfo info = mock(PlayerMoveInfo.class);
        final PlayerLocation from = mock(PlayerLocation.class);
        final var field = MoveInfo.class.getDeclaredField("from");
        field.setAccessible(true);
        field.set(info, from);
        when(from.isOnGroundOrResetCond()).thenReturn(true);
        final Method hover = MovingListener.class.getDeclaredMethod("checkHover", Player.class,
                MovingData.class, MovingConfig.class, IPlayerData.class, PlayerMoveInfo.class);
        hover.setAccessible(true);
        try (var folia = mockStatic(Folia.class)) {
            folia.when(Folia::isFoliaServer).thenReturn(true);
            folia.when(() -> Folia.isOwnedByCurrentRegion(any(Location.class))).thenReturn(owned);
            boolean remove = (boolean) hover.invoke(listener, player, data, config, playerData, info);
            assertEquals(loaded && owned, remove);
            assertEquals("Unavailable geometry must not accumulate hover violations", 0, data.sfHoverTicks);
            verify(from, never()).ensureChunksLoaded();
            if (!loaded || !owned) {
                verifyNoInteractions(info, from);
            } else {
                verify(from).isOnGroundOrResetCond();
                verify(world).isChunkLoaded(-1, 0);
                verify(world).isChunkLoaded(0, 1);
            }
            verify(world, never()).getChunkAt(anyInt(), anyInt());
        }
    }
}
