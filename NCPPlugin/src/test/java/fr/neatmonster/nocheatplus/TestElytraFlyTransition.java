package fr.neatmonster.nocheatplus;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Method;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

import fr.neatmonster.nocheatplus.checks.moving.MovingConfig;
import fr.neatmonster.nocheatplus.checks.moving.MovingData;
import fr.neatmonster.nocheatplus.checks.moving.MovingListener;
import fr.neatmonster.nocheatplus.checks.moving.location.tracking.LocationTrace.TraceEntryPool;
import fr.neatmonster.nocheatplus.checks.moving.model.ModelFlying;
import fr.neatmonster.nocheatplus.checks.moving.model.PlayerMoveData;
import fr.neatmonster.nocheatplus.checks.moving.velocity.AccountEntry;
import fr.neatmonster.nocheatplus.checks.moving.velocity.SimpleEntry;
import fr.neatmonster.nocheatplus.checks.workaround.WRPT;
import fr.neatmonster.nocheatplus.players.IPlayerData;

public class TestElytraFlyTransition {
    private Player player;
    private PlayerInventory inventory;
    private MovingData data;
    private MovingConfig config;
    private PlayerMoveData lastMove;
    private MovingListener listener;

    @Before
    public void setup() {
        final PluginTests.UnitTestNoCheatPlusAPI api = new PluginTests.UnitTestNoCheatPlusAPI();
        NCPAPIProvider.setNoCheatPlusAPI(api);
        api.registerGenericInstance(WRPT.class, new WRPT());
        api.registerGenericInstance(TraceEntryPool.class, new TraceEntryPool(100));
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getVersion).thenReturn("Folia (MC: 26.2)");
            config = new MovingConfig(api.getWorldDataManager().getWorldData(UUID.randomUUID().toString()));
        }
        data = new MovingData(config, mock(IPlayerData.class));
        player = mock(Player.class);
        inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        final ItemStack elytra = mock(ItemStack.class);
        when(elytra.getType()).thenReturn(Material.ELYTRA);
        when(inventory.getChestplate()).thenReturn(elytra);
        lastMove = data.playerMoves.getFirstPastMove();
        lastMove.valid = lastMove.toIsValid = true;
        lastMove.hDistance = 0.089;
        lastMove.yDistance = -2.9842794901924208E-12;
        final ModelFlying model = mock(ModelFlying.class);
        when(model.getId()).thenReturn(MovingConfig.ID_JETPACK_ELYTRA);
        lastMove.modelFlying = model;
        listener = mock(MovingListener.class, CALLS_REAL_METHODS);
    }

    private void transition(final int tick) throws Exception {
        final Method method = MovingListener.class.getDeclaredMethod("workaroundFlyCheckTransition",
                Player.class, int.class, boolean.class, MovingData.class, MovingConfig.class);
        method.setAccessible(true);
        method.invoke(listener, player, tick, false, data, config);
    }

    @Test
    public void repeatedWornGlideTogglesCannotMintHoverVelocity() throws Exception {
        // Captured PacketFly: level movement and a glide restart every few ticks.
        for (int tick = 100; tick < 220; tick += 6) {
            transition(tick);
            assertNull("No level-flight credit at tick " + tick, data.getOrUseVerticalVelocity(lastMove.yDistance));
            assertFalse("No synthetic horizontal credit", data.hasQueuedHorVel());
        }
    }

    @Test
    public void togglingKeepsRealVelocityAvailable() throws Exception {
        final SimpleEntry vertical = new SimpleEntry(100, 0.5, 20);
        data.addVerticalVelocity(vertical);
        data.addHorizontalVelocity(new AccountEntry(100, 0.8, 20, 20));
        transition(100);
        assertSame(vertical, data.getOrUseVerticalVelocity(0.5));
        assertEquals(0.8, data.useHorizontalVelocity(0.8), 0.0);
    }

    @Test
    public void actualUnequipKeepsTheExistingHandoff() throws Exception {
        when(inventory.getChestplate()).thenReturn(null);
        lastMove.hDistance = 0.7;
        lastMove.yDistance = -0.3;
        transition(100);
        assertNotNull(data.getOrUseVerticalVelocity(-0.3));
        assertNotNull(data.getOrUseVerticalVelocity(0.0));
        assertTrue(data.hasQueuedHorVel());
    }

    @Test
    public void aLandingKeepsTheExistingHandoff() throws Exception {
        data.playerMoves.getCurrentMove().touchedGround = true;
        transition(100);
        assertNotNull(data.getOrUseVerticalVelocity(0.0));
        assertTrue(data.hasQueuedHorVel());
    }

    @Test
    public void enteringWaterKeepsTheExistingHandoff() throws Exception {
        data.playerMoves.getCurrentMove().to.onGroundOrResetCond = true;
        transition(100);
        assertNotNull(data.getOrUseVerticalVelocity(0.0));
        assertTrue(data.hasQueuedHorVel());
    }

    @Test
    public void leavingWaterKeepsTheExistingHandoff() throws Exception {
        data.playerMoves.getCurrentMove().from.onGroundOrResetCond = true;
        transition(100);
        assertNotNull(data.getOrUseVerticalVelocity(0.0));
        assertTrue(data.hasQueuedHorVel());
    }

    @Test
    public void otherFlyingModelsKeepTheirHandoffWhileWearingElytra() throws Exception {
        when(lastMove.modelFlying.getId()).thenReturn("gamemode.creative");
        lastMove.yDistance = 0.3;
        transition(100);
        assertNotNull(data.getOrUseVerticalVelocity(0.3));
        assertNotNull(data.getOrUseVerticalVelocity(0.0));
        assertTrue(data.hasQueuedHorVel());
    }
}
