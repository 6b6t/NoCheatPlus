package fr.neatmonster.nocheatplus;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

import fr.neatmonster.nocheatplus.checks.moving.MovingConfig;
import fr.neatmonster.nocheatplus.checks.moving.MovingData;
import fr.neatmonster.nocheatplus.checks.moving.MovingListener;
import fr.neatmonster.nocheatplus.checks.moving.ElytraResidual;
import fr.neatmonster.nocheatplus.checks.moving.location.tracking.LocationTrace.TraceEntryPool;
import fr.neatmonster.nocheatplus.checks.moving.model.ModelFlying;
import fr.neatmonster.nocheatplus.checks.moving.model.PlayerMoveData;
import fr.neatmonster.nocheatplus.checks.moving.velocity.AccountEntry;
import fr.neatmonster.nocheatplus.checks.moving.velocity.SimpleEntry;
import fr.neatmonster.nocheatplus.checks.moving.util.MovingUtil;
import fr.neatmonster.nocheatplus.checks.workaround.WRPT;
import fr.neatmonster.nocheatplus.compat.Bridge1_9;
import fr.neatmonster.nocheatplus.players.IPlayerData;
import fr.neatmonster.nocheatplus.utilities.TickTask;
import fr.neatmonster.nocheatplus.utilities.location.PlayerLocation;

public class TestElytraFlyTransition {
    private Player player;
    private PlayerInventory inventory;
    private MovingData data;
    private MovingConfig config;
    private PlayerMoveData lastMove;
    private MovingListener listener;
    private Damageable elytraDamage;

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
        elytraDamage = mock(Damageable.class);
        when(elytra.getItemMeta()).thenReturn(elytraDamage);
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

    private boolean isToggle() throws Exception {
        final Method method = MovingListener.class.getDeclaredMethod("isAirborneElytraToggle",
                Player.class, PlayerMoveData.class, PlayerMoveData.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, player, data.playerMoves.getCurrentMove(), lastMove);
    }

    @Test
    public void invalidatedGlideCannotStartARelightLockout() throws Exception {
        assertTrue(isToggle());
        lastMove.invalidate();
        assertFalse(isToggle());
    }

    @Test
    public void depletedElytraKeepsTheNormalMomentumHandoff() throws Exception {
        when(elytraDamage.getDamage()).thenReturn(Material.ELYTRA.getMaxDurability() - 1);
        when(lastMove.modelFlying.getHorizontalModSpeed()).thenReturn(450.0);
        lastMove.hDistance = 1.6523030862037018;
        lastMove.yDistance = -0.18312551390701515;
        assertFalse("Vanilla ended the glide; this is not a toggle", isToggle());
        transition(100);
        assertNotNull(data.getOrUseVerticalVelocity(lastMove.yDistance));
        assertTrue(data.hasQueuedHorVel());
        assertTrue("Depletion opens the same bounded coast as unequip", data.elytraResidual.open);
        // The captured client still glides briefly after the server depletes the item.
        for (final double[] move : new double[][] {{1.6534891593350949, -0.1813580346855872},
                {1.6545106380214776, -0.17979389497219245}, {1.6545106380214776, -0.17979389497219245}}) {
            assertTrue(ElytraResidual.allow(data.elytraResidual, move[0], move[1], 10.0,
                    false, false, Bridge1_9.isWearingUsableElytra(player)));
        }
        when(elytraDamage.getDamage()).thenReturn(0);
        assertFalse("Repairing the elytra closes residual credit", ElytraResidual.allow(data.elytraResidual,
                1.65, -0.18, 10.0, false, false, Bridge1_9.isWearingUsableElytra(player)));
    }

    @Test
    public void theLastUsableDurabilityPointStillDetectsToggles() throws Exception {
        when(elytraDamage.getDamage()).thenReturn(Material.ELYTRA.getMaxDurability() - 2);
        assertTrue(isToggle());
    }

    @Test
    public void customMaximumDurabilityUsesTheSameVanillaBoundary() throws Exception {
        for (final int maximum : new int[] {1, 100, 1000}) {
            when(elytraDamage.serialize()).thenReturn(Map.of("max-damage", maximum));
            when(elytraDamage.getDamage()).thenReturn(Math.max(0, maximum - 2));
            assertEquals("Maximum " + maximum, maximum > 1, isToggle());
            when(elytraDamage.getDamage()).thenReturn(maximum - 1);
            assertFalse("Depleted at maximum " + maximum, isToggle());
        }
    }

    @Test
    public void unbreakableElytraCanStillFlyAtTheDamageBoundary() throws Exception {
        when(elytraDamage.getDamage()).thenReturn(Material.ELYTRA.getMaxDurability() - 1);
        when(elytraDamage.isUnbreakable()).thenReturn(true);
        assertTrue(isToggle());
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
    public void glideExitMustMatchCarriedVelocityOrAirDrag() {
        assertTrue(ElytraResidual.matchesGlideCoast(1.65, -0.18, 0.0, 1.67, -0.18, 0.0, true));
        assertFalse(ElytraResidual.matchesGlideCoast(1.65, -0.18, 0.0, 1.67, -0.18, 0.0, false));
        assertTrue(ElytraResidual.matchesGlideCoast(1.65, -0.18, 0.0, 1.50, -0.255, 0.0, false));
        assertFalse(ElytraResidual.matchesGlideCoast(1.65, -0.18, 0.0, -1.65, -0.18, 0.0, true));
        assertFalse(ElytraResidual.matchesGlideCoast(1.65, -0.18, 0.0, 1.80, -0.18, 0.0, true));
        assertFalse(ElytraResidual.matchesGlideCoast(0.09, 0.0, 0.0, 0.09, 0.0, 0.0, true));
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

    @Test
    public void aRejectedToggleCannotImmediatelySwitchBackToGliding() {
        final PlayerLocation from = mock(PlayerLocation.class);
        when(from.isPassableBox()).thenReturn(true);
        assertTrue(MovingUtil.canLiftOffWithElytra(player, from, data));
        data.blockElytraRelight(TickTask.getTick());
        assertFalse(MovingUtil.canLiftOffWithElytra(player, from, data));
        assertFalse(MovingUtil.isGlidingWithElytraValid(player, from, data, config));
    }

    @Test
    public void repeatedCorrectionsDoNotExtendTheRelightBlock() {
        final PlayerLocation from = mock(PlayerLocation.class);
        when(from.isPassableBox()).thenReturn(true);
        final Location correction = mock(Location.class);
        when(correction.getWorld()).thenReturn(mock(World.class));
        lastMove.modelFlying = null;
        data.blockElytraRelight(100);
        try (MockedStatic<TickTask> ticks = mockStatic(TickTask.class)) {
            for (int tick = 100; tick < 120; tick++) {
                ticks.when(TickTask::getTick).thenReturn(tick);
                data.blockElytraRelight(tick);
                data.prepareSetBack(correction);
                assertFalse(MovingUtil.canLiftOffWithElytra(player, from, data));
                assertFalse(MovingUtil.isGlidingWithElytraValid(player, from, data, config));
            }
            ticks.when(TickTask::getTick).thenReturn(120);
            assertTrue(MovingUtil.canLiftOffWithElytra(player, from, data));
            assertTrue(MovingUtil.isGlidingWithElytraValid(player, from, data, config));
            data.blockElytraRelight(120);
            assertFalse(MovingUtil.canLiftOffWithElytra(player, from, data));
        }
    }

    @Test
    public void groundContactOrCleanMovementAllowsEarlierRecovery() {
        final PlayerMoveData move = data.playerMoves.getCurrentMove();
        data.blockElytraRelight(100);
        move.touchedGround = true;
        data.updateElytraRelightRecovery(move, 101);
        assertFalse(data.isElytraRelightBlocked(101));

        data.blockElytraRelight(102);
        move.touchedGround = false;
        move.to.onGroundOrResetCond = true;
        data.updateElytraRelightRecovery(move, 103);
        assertFalse(data.isElytraRelightBlocked(103));

        data.blockElytraRelight(104);
        move.to.onGroundOrResetCond = false;
        data.timeSinceSetBack = 9;
        data.updateElytraRelightRecovery(move, 111);
        assertTrue(data.isElytraRelightBlocked(111));
        data.timeSinceSetBack++;
        data.updateElytraRelightRecovery(move, 112);
        assertFalse(data.isElytraRelightBlocked(112));
    }

    @Test
    public void unequippingAndReequippingCannotSkipAirborneRecovery() {
        final ItemStack elytra = inventory.getChestplate();
        final PlayerLocation from = mock(PlayerLocation.class);
        when(from.isPassableBox()).thenReturn(true);
        final int tick = TickTask.getTick();
        data.blockElytraRelight(tick);
        when(inventory.getChestplate()).thenReturn(null);
        assertFalse(Bridge1_9.isWearingElytra(player));
        assertTrue("Removing the Elytra must not clear the relight block", data.isElytraRelightBlocked(tick));
        when(inventory.getChestplate()).thenReturn(elytra);
        assertTrue(Bridge1_9.isWearingElytra(player));
        assertFalse(MovingUtil.canLiftOffWithElytra(player, from, data));
        assertFalse(MovingUtil.isGlidingWithElytraValid(player, from, data, config));
        assertTrue("Re-equipping must still require recovery", data.isElytraRelightBlocked(tick));
    }

    @Test
    public void logoutAndFlyDataResetDoNotKeepAnOldBlock() {
        data.blockElytraRelight(100);
        data.onPlayerLeave();
        assertFalse(data.isElytraRelightBlocked(100));
        data.blockElytraRelight(100);
        data.clearFlyData();
        assertFalse(data.isElytraRelightBlocked(100));
    }
}
