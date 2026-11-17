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
    public void acceptedElytraDiveMomentumSurvivesTheGlideOffTransition() throws Exception {
        lastMove.yDistance = -0.18;
        lastMove.hDistance = 0.35;
        when(lastMove.modelFlying.getHorizontalModSpeed()).thenReturn(450.0);
        transition(100);
        assertTrue("The checked glide's existing vertical momentum must reach SurvivalFly", data.hasQueuedVerVel());
    }

    @Test
    public void forgedMaceFallDoesNotReceiveElytraTransitionCredit() throws Exception {
        lastMove.yDistance = -1.75;
        lastMove.invalidMaceFall = true;
        transition(100);
        assertFalse("A flagged forged descent cannot renew vertical credit", data.hasQueuedVerVel());
        assertFalse("A flagged forged descent cannot renew horizontal credit", data.hasQueuedHorVel());
    }

    @Test
    public void glideToggleHandoffNeedsDownwardMomentumAndCanBeUsedOnlyOnce() throws Exception {
        lastMove.yDistance = 0.5;
        transition(100);
        transition(101);
        assertFalse("Rising toggles cannot mint flight credit", data.hasQueuedVerVel());
        assertFalse("Rising toggles cannot mint horizontal credit", data.hasQueuedHorVel());

        lastMove.yDistance = -0.3;
        transition(102);
        assertNotNull("The first accepted downward toggle keeps real momentum", data.getOrUseVerticalVelocity(-0.3));
        assertNotNull(data.getOrUseVerticalVelocity(0.0));

        transition(103);
        assertNull("A second toggle inside the recovery window cannot renew momentum",
                data.getOrUseVerticalVelocity(-0.3));
        assertNull(data.getOrUseVerticalVelocity(0.0));
        assertFalse("The second toggle cannot renew horizontal credit", data.hasQueuedHorVel());
    }

    @Test
    public void glideToggleHandoffCanRecoverAfterGroundOrTwentyTicks() {
        data.elytraToggleHandoffTick = 100;
        data.updateElytraToggleHandoffRecovery(false, 119);
        assertEquals("The handoff remains spent during the recovery window", 100, data.elytraToggleHandoffTick);
        data.updateElytraToggleHandoffRecovery(false, 120);
        assertEquals("Twenty ticks end the recovery window", -1, data.elytraToggleHandoffTick);

        data.elytraToggleHandoffTick = 121;
        data.updateElytraToggleHandoffRecovery(true, 122);
        assertEquals("Ground/reset contact immediately restores the handoff", -1, data.elytraToggleHandoffTick);
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
        data.blockElytraRelight(100);
        assertFalse(MovingUtil.canLiftOffWithElytra(player, from, data));
        assertFalse(MovingUtil.isGlidingWithElytraValid(player, from, data, config));
    }

    @Test
    public void repeatedCorrectionsCannotExtendTheFixedRelightBlock() {
        data.blockElytraRelight(100);
        final Location correction = new Location(mock(World.class), 1, 100, 1);
        for (int tick = 101; tick < 120; tick++) {
            data.blockElytraRelight(tick);
            data.prepareSetBack(correction);
            assertTrue(data.elytraRelightBlocked);
            assertEquals("Repeated corrections must not restart the timer", 100, data.elytraRelightBlockedTick);
            data.updateElytraRelightRecovery(false, tick);
        }
        assertTrue(data.elytraRelightBlocked);
        data.updateElytraRelightRecovery(false, 120);
        assertFalse("Normal movement allows redeploying again", data.elytraRelightBlocked);
        data.blockElytraRelight(121);
        assertTrue("A new rejected airborne toggle starts a new block", data.elytraRelightBlocked);
        assertEquals(121, data.elytraRelightBlockedTick);
    }

    @Test
    public void realContactAllowsImmediateRecovery() {
        data.blockElytraRelight(100);
        data.updateElytraRelightRecovery(true, 101);
        assertFalse(data.elytraRelightBlocked);
        assertEquals(-1, data.elytraRelightBlockedTick);
    }

    @Test
    public void unequippingAndReequippingCannotSkipAirborneRecovery() {
        final ItemStack elytra = inventory.getChestplate();
        final PlayerLocation from = mock(PlayerLocation.class);
        when(from.isPassableBox()).thenReturn(true);
        data.blockElytraRelight(100);
        data.timeSinceSetBack = 1;
        when(inventory.getChestplate()).thenReturn(null);
        assertFalse(Bridge1_9.isWearingElytra(player));
        data.updateElytraRelightRecovery(false, 101);
        assertFalse(MovingUtil.canLiftOffWithElytra(player, from, data));
        assertTrue("Removing the Elytra must not clear the relight block", data.elytraRelightBlocked);
        when(inventory.getChestplate()).thenReturn(elytra);
        assertTrue(Bridge1_9.isWearingElytra(player));
        data.updateElytraRelightRecovery(false, 102);
        assertFalse(MovingUtil.canLiftOffWithElytra(player, from, data));
        assertFalse(MovingUtil.isGlidingWithElytraValid(player, from, data, config));
        assertTrue("Re-equipping must still require recovery", data.elytraRelightBlocked);
        data.updateElytraRelightRecovery(false, 120);
        assertFalse(data.elytraRelightBlocked);
        assertTrue(MovingUtil.canLiftOffWithElytra(player, from, data));
    }

    @Test
    public void logoutAndFlyDataResetDoNotKeepAnOldBlock() {
        data.blockElytraRelight(100);
        data.onPlayerLeave();
        assertFalse(data.elytraRelightBlocked);
        assertEquals(-1, data.elytraRelightBlockedTick);
        data.blockElytraRelight(200);
        data.clearFlyData();
        assertFalse(data.elytraRelightBlocked);
        assertEquals(-1, data.elytraRelightBlockedTick);
    }
}
