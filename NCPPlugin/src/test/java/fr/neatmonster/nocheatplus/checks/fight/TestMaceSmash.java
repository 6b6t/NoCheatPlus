package fr.neatmonster.nocheatplus.checks.fight;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;

import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.junit.Before;
import org.junit.Test;

import fr.neatmonster.nocheatplus.checks.CheckType;
import fr.neatmonster.nocheatplus.checks.moving.MovingConfig;
import fr.neatmonster.nocheatplus.checks.moving.MovingData;
import fr.neatmonster.nocheatplus.checks.moving.model.ModelFlying;
import fr.neatmonster.nocheatplus.checks.moving.model.MoveTrace;
import fr.neatmonster.nocheatplus.checks.moving.model.PlayerMoveData;
import fr.neatmonster.nocheatplus.checks.moving.util.MovingUtil;
import fr.neatmonster.nocheatplus.checks.moving.velocity.NativePushTracker;
import fr.neatmonster.nocheatplus.checks.moving.velocity.SimpleAxisVelocity;
import fr.neatmonster.nocheatplus.checks.moving.velocity.SimpleEntry;
import fr.neatmonster.nocheatplus.compat.Bridge1_9;
import fr.neatmonster.nocheatplus.permissions.Permissions;
import fr.neatmonster.nocheatplus.players.IPlayerData;

public class TestMaceSmash {
    private final Player player = mock(Player.class);
    private final IPlayerData pData = mock(IPlayerData.class);
    private final MovingData data = mock(MovingData.class, CALLS_REAL_METHODS);
    private final MoveTrace<PlayerMoveData> moves = new MoveTrace<>(PlayerMoveData::new, 16);
    private final ModelFlying elytra = new ModelFlying(MovingConfig.ID_JETPACK_ELYTRA);
    private int tick = 100;
    private double y = 100.0;

    @Before
    public void setup() throws Exception {
        final Field trace = MovingData.class.getField("playerMoves");
        trace.setAccessible(true);
        trace.set(data, moves);
        when(player.getVelocity()).thenReturn(new Vector(0, -0.1, 0));
        when(player.getTicksLived()).thenAnswer(call -> tick);
        when(player.getFallDistance()).thenReturn(3f);
        when(pData.getGenericInstance(MovingData.class)).thenReturn(data);
        when(pData.isCheckActive(CheckType.FIGHT, player)).thenReturn(true);
        when(pData.isCheckActive(CheckType.MOVING_CREATIVEFLY, player)).thenReturn(true);
    }

    private PlayerMoveData prepare(final double dy, final int elapsed, final ModelFlying model) {
        tick += elapsed;
        final PlayerMoveData move = moves.getCurrentMove();
        move.from.setLocation("world", 0, y, 0, 0, 0);
        move.to.setLocation("world", 0, y + dy, 0, 0, 0);
        move.yDistance = dy;
        move.valid = move.toIsValid = true;
        move.modelFlying = model;
        return move;
    }

    private boolean accept(final PlayerMoveData move) {
        move.invalidMaceFall = MovingUtil.isInvalidMaceFall(player, data);
        final boolean invalid = move.invalidMaceFall;
        y = move.to.getY();
        moves.finishCurrentMove();
        return invalid;
    }

    private boolean move(final double dy) {
        return accept(prepare(dy, 1, elytra));
    }

    private boolean smash() {
        try (var bridge = mockStatic(Bridge1_9.class)) {
            return FightListener.isInvalidMaceSmash(player, pData);
        }
    }

    @Test
    public void quietteeOccamChainAndHoverDivesCannotGainSmashDamage() {
        for (final double firstDip : new double[] {-1.0, -1.5, -1.75, -0.6}) {
            moves.invalidate();
            assertFalse(move(0));
            assertTrue("Initial fake dive " + firstDip, move(firstDip));
            // Occam banks distance, then makes a smaller strike dip. Chain can send both in one tick.
            assertTrue(accept(prepare(-0.7, 0, elytra)));
            assertTrue("Closing the elytra before attacking does not erase the evidence", smash());
        }
    }

    @Test
    public void idleChargingBeforeADiveDoesNotGrantExtraFallDistance() {
        move(0);
        assertTrue(accept(prepare(-1.0, 40, elytra)));
        assertTrue(accept(prepare(-0.7, 1, elytra)));
        assertTrue(smash());
    }

    @Test
    public void unglidedCarryAndStationaryPacketsDoNotLaunderTheFakeDescent() {
        move(0);
        assertTrue(move(-1.5));
        final PlayerMoveData transition = prepare(-1.5, 1, null);
        // The fly-check transition queues the previous descent as synthetic velocity.
        transition.verVelUsed = new SimpleEntry(-1.5, 20);
        assertTrue(accept(transition));
        assertTrue(accept(prepare(0, 1, null)));
        assertTrue(smash());
    }

    @Test
    public void repeatedShortDivesCannotClearEvidenceOnTheirReturnHops() {
        move(0);
        when(player.getVelocity()).thenReturn(new Vector(0, -0.3364168190503776, 0));
        assertTrue(move(-1));
        assertTrue(move(-0.75));
        for (int i = 0; i < 8; i++) {
            // Captured return hops between attacks; server gravity keeps running during the forged rise.
            assertTrue(accept(prepare(1.5, 0, elytra)));
            assertTrue(move(0.25));
            when(player.getVelocity()).thenReturn(new Vector(0, -1.2666373203145314, 0));
            assertTrue(move(-1));
            assertTrue(move(-0.75));
            assertTrue("The next short dive must not regain smash damage", smash());
        }
    }

    @Test
    public void normalFreefallAndTerminalElytraDivesRemainUsable() {
        for (final ModelFlying model : new ModelFlying[] {null, elytra}) {
            moves.invalidate();
            double velocity = 0;
            for (int i = 0; i < 200; i++) {
                velocity = (velocity - 0.08) * 0.98F;
                assertFalse("Dive tick " + i, accept(prepare(velocity, 1, model)));
            }
            assertFalse(smash());
        }
    }

    @Test
    public void pitchChangesPullupsSlowfallAndHighHorizontalSpeedDoNotInventDownwardAcceleration() {
        move(-0.3);
        for (final double dy : new double[] {-0.28, -0.02, 0.0, 0.4, 0.8, 0.2, -0.03, -0.1, -0.17}) {
            final PlayerMoveData move = prepare(dy, 1, elytra);
            move.hDistance = 5.0;
            assertFalse(accept(move));
        }
        assertFalse(smash());
    }

    @Test
    public void delayedEventsAndQueuedClientTicksKeepTheirPhysicsAllowance() {
        move(0);
        // Rotation-only and sub-threshold packets can omit movement events near the apex.
        assertFalse(accept(prepare(-0.12, 20, elytra)));
        moves.invalidate();
        move(-0.5);
        // Normal packets may arrive in a burst in the same server tick.
        for (int i = 0; i < 10; i++) {
            assertFalse(accept(prepare(-0.5, 0, elytra)));
        }
        assertFalse(smash());
    }

    @Test
    public void nativeKnockbackQueuedVelocityFireworksAndRiptideAreRespected() throws Exception {
        move(0);
        when(player.getVelocity()).thenReturn(new Vector(0, -2, 0));
        assertFalse(move(-2));
        when(player.getVelocity()).thenReturn(new Vector(0, -0.1, 0));
        move(0);
        final SimpleAxisVelocity velocity = new SimpleAxisVelocity();
        velocity.add(new SimpleEntry(tick, -2.0, 4));
        final Field tracker = MovingData.class.getDeclaredField("verVel");
        tracker.setAccessible(true);
        tracker.set(data, velocity);
        assertFalse(move(-2));
        assertTrue("Matching queued velocity is inspected without consuming it", velocity.hasQueued());
        move(0);
        data.fireworksBoostDuration = 5;
        assertFalse(move(-2));
        data.fireworksBoostDuration = 0;
        move(0);
        when(player.isRiptiding()).thenReturn(true);
        assertFalse(move(-2));
    }

    @Test
    public void delayedNativeExplosionImpulseDoesNotMarkALegitimateFall() throws Exception {
        final NativePushTracker push = new NativePushTracker();
        final Field tracker = MovingData.class.getField("nativePush");
        tracker.setAccessible(true);
        tracker.set(data, push);
        move(0);
        push.addPush(tick, 0, -5, 0, false, 12);
        assertFalse(accept(prepare(-5, 10, elytra)));
        assertFalse(smash());
    }

    @Test
    public void supportedLandingsEndTheInvalidFallButAirborneAscentsDoNot() {
        move(0);
        move(-1.5);
        assertTrue(move(0.2));
        assertTrue(move(-1.5));
        final PlayerMoveData landing = prepare(-0.1, 1, elytra);
        landing.touchedGround = true;
        assertFalse(accept(landing));
        assertFalse(smash());
    }

    @Test
    public void teleportsAndSplitEventsDoNotCreateEvidence() {
        move(0);
        final PlayerMoveData teleport = prepare(-2, 1, elytra);
        teleport.from.setLocation("other_world", 0, y, 0, 0, 0);
        assertFalse(accept(teleport));
        final PlayerMoveData split = prepare(-2, 1, elytra);
        split.multiMoveCount = 2;
        assertFalse(accept(split));

    }

    @Test
    public void ordinaryMaceHitsGlidingAndDisabledChecksAreUntouched() {
        move(0);
        move(-1.5);
        when(player.getFallDistance()).thenReturn(1.5f);
        assertFalse(smash());
        when(player.getFallDistance()).thenReturn(3f);
        try (var bridge = mockStatic(Bridge1_9.class)) {
            bridge.when(() -> Bridge1_9.isGliding(player)).thenReturn(true);
            assertFalse(FightListener.isInvalidMaceSmash(player, pData));
        }
        when(pData.isCheckActive(CheckType.FIGHT, player)).thenReturn(false);
        assertFalse(smash());
        when(pData.isCheckActive(CheckType.FIGHT, player)).thenReturn(true);
        when(pData.isCheckActive(CheckType.MOVING_CREATIVEFLY, player)).thenReturn(false);
        assertFalse(smash());
        when(pData.isCheckActive(CheckType.MOVING_CREATIVEFLY, player)).thenReturn(true);
        when(pData.hasPermission(Permissions.MOVING_ELYTRAFLY, player)).thenReturn(true);
        assertFalse(smash());
    }

    @Test
    public void waitingDoesNotEraseEvidenceButPositionResetsDo() {
        move(0);
        move(-1.5);
        tick += 21;
        assertTrue(smash());
        assertTrue(accept(prepare(0, 21, elytra)));
        moves.invalidate();
        assertFalse(smash());
        final PlayerMoveData reused = new PlayerMoveData();
        reused.invalidMaceFall = true;
        reused.set(moves.getFirstPastMove().to);
        assertFalse(reused.invalidMaceFall);
    }
}
