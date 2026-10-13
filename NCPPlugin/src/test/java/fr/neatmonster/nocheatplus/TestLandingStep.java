package fr.neatmonster.nocheatplus;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.entity.Player;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

import fr.neatmonster.nocheatplus.checks.moving.MovingConfig;
import fr.neatmonster.nocheatplus.checks.moving.MovingData;
import fr.neatmonster.nocheatplus.checks.moving.magic.LostGround;
import fr.neatmonster.nocheatplus.checks.moving.model.LiftOffEnvelope;
import fr.neatmonster.nocheatplus.checks.moving.model.MoveTrace;
import fr.neatmonster.nocheatplus.checks.moving.model.PlayerMoveData;
import fr.neatmonster.nocheatplus.compat.MCAccess;
import fr.neatmonster.nocheatplus.utilities.location.PlayerLocation;
import fr.neatmonster.nocheatplus.utilities.map.BlockProperties;

public class TestLandingStep {
    private Player player;
    private PlayerLocation from;
    private PlayerLocation to;
    private MovingData data;
    private MovingConfig config;
    private PlayerMoveData current;
    private PlayerMoveData last;
    private final List<String> tags = new ArrayList<>();

    @Before
    public void setup() throws Exception {
        player = mock(Player.class);
        from = mock(PlayerLocation.class);
        to = mock(PlayerLocation.class);
        data = mock(MovingData.class);
        config = mock(MovingConfig.class);
        final var stepHeight = MovingConfig.class.getField("sfStepHeight");
        stepHeight.setAccessible(true);
        stepHeight.setDouble(config, 0.6);
        final MoveTrace<PlayerMoveData> trace = new MoveTrace<>(PlayerMoveData::new, 16);
        final var moves = MovingData.class.getField("playerMoves");
        moves.setAccessible(true);
        moves.set(data, trace);
        current = trace.getCurrentMove();
        last = trace.getFirstPastMove();
        last.toIsValid = true;
        last.yDistance = -0.5143294551172826;
        last.hAllowedDistanceBase = 0.328;
        current.walkSpeed = 0.287;
        data.liftOffEnvelope = LiftOffEnvelope.NORMAL;
        data.sfJumpPhase = 13;
        when(data.getSetBackY()).thenReturn(70.0625);
        when(from.getY()).thenReturn(69.224641421027);
        when(to.getY()).thenReturn(69.5625);
        when(to.isOnGround()).thenReturn(true);
        final MCAccess access = mock(MCAccess.class);
        when(access.getJumpAmplifier(player)).thenReturn(Double.NEGATIVE_INFINITY);
        when(from.getMCAccess()).thenReturn(access);
    }

    @Test
    public void fallingOntoBoatEdgeStepsUpBeforeHorizontalAllowanceIsCalculated() {
        assertEquals(0, current.hAllowedDistanceBase, 0);
        assertTrue(step(0.267561, 69.5625 - 69.224641421027));
        assertTrue(current.touchedGroundWorkaround);
        assertTrue(tags.contains("lostground_step"));
        assertEquals(0, data.sfJumpPhase);
        verify(data).setSetBack(from);
    }

    @Test
    public void straightFallCanStartMovingSidewaysWhileLanding() {
        last.hAllowedDistanceBase = 0;
        assertTrue(step(0.267561, 69.5625 - 69.224641421027));
    }

    @Test
    public void excessiveRiseOrHorizontalMoveStillFails() {
        assertFalse(step(0.267561, 1.5));
        assertFalse(step(0.9, 69.5625 - 69.224641421027));
        assertFalse(current.touchedGroundWorkaround);
    }

    @Test
    public void missingSupportOrAscendingHistoryDoesNotBecomeAStep() {
        when(to.isOnGround()).thenReturn(false);
        assertFalse(step(0.267561, 69.5625 - 69.224641421027));
        when(to.isOnGround()).thenReturn(true);
        last.yDistance = 0.42;
        assertFalse(step(0.267561, 69.5625 - 69.224641421027));
        assertFalse(current.touchedGroundWorkaround);
    }

    private boolean step(final double horizontal, final double vertical) {
        try (MockedStatic<BlockProperties> blocks = mockStatic(BlockProperties.class)) {
            return LostGround.lostGround(player, from, to, horizontal, vertical, true,
                    last, data, config, null, tags);
        }
    }
}
