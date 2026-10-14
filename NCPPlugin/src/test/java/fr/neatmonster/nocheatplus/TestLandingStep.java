package fr.neatmonster.nocheatplus;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.entity.Player;
import org.junit.After;
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
import fr.neatmonster.nocheatplus.compat.versions.ServerVersion;
import fr.neatmonster.nocheatplus.utilities.location.PlayerLocation;
import fr.neatmonster.nocheatplus.utilities.map.BlockProperties;

/** Predicate boundaries; full SurvivalFly and collision fixtures are in TestLandingStepSequence. */
public class TestLandingStep {
    private final boolean base = Boolean.getBoolean("landingstep.base");
    private Player player;
    private PlayerLocation from, to;
    private MovingData data;
    private MovingConfig config;
    private PlayerMoveData current, last;
    private String oldVersion;
    private final List<String> tags = new ArrayList<>();

    @Before
    public void setup() throws Exception {
        oldVersion = ServerVersion.getMinecraftVersion();
        ServerVersion.setMinecraftVersion("26.2");
        player = mock(Player.class);
        from = mock(PlayerLocation.class);
        to = mock(PlayerLocation.class);
        data = mock(MovingData.class);
        config = mock(MovingConfig.class);
        stepHeight(1.1);
        final MoveTrace<PlayerMoveData> trace = new MoveTrace<>(PlayerMoveData::new, 16);
        final var moves = MovingData.class.getField("playerMoves");
        moves.setAccessible(true);
        moves.set(data, trace);
        current = trace.getCurrentMove();
        last = trace.getFirstPastMove();
        last.toIsValid = true;
        last.yDistance = -0.374;
        last.hAllowedDistanceBase = 0.2873;
        current.walkSpeed = 0.221;
        data.liftOffEnvelope = LiftOffEnvelope.NORMAL;
        data.sfJumpPhase = 12;
        when(data.getSetBackY()).thenReturn(0.0);
        when(to.isOnGround()).thenReturn(true);
        when(to.stepsOnEntityTop(eq(from), anyDouble())).thenReturn(true);
        final MCAccess access = mock(MCAccess.class);
        when(access.getJumpAmplifier(player)).thenReturn(Double.NEGATIVE_INFINITY);
        when(from.getMCAccess()).thenReturn(access);
        landing(0.0, 0.1215, 0.5625);
    }

    @After
    public void restoreVersion() { ServerVersion.setMinecraftVersion(oldVersion); }

    @Test
    public void recordedBoatLandingsNeedNoCurrentHorizontalAllowance() {
        assertEquals(0, current.hAllowedDistanceBase, 0);
        assertLanding(0.04);
        assertLanding(0.20);
        if (!base) {
            assertTrue(tags.contains("lostground_landingstep"));
            assertFalse(tags.contains("lostground_step"));
            assertTrue(current.touchedGroundWorkaround);
            assertEquals(0, data.sfJumpPhase);
            verify(data, times(2)).setSetBack(from);
        }
    }

    @Test
    public void straightFallCanStartMovingSidewaysWhileLanding() {
        last.hAllowedDistanceBase = 0;
        assertLanding(0.267561);
    }

    @Test
    public void raisedCarpetLandingCanExceedTheOldJumpAnchor() {
        landing(1.0625, 1.17675927506424, 1.625);
        last.yDistance = -0.07544406518948656;
        assertLanding(0.1024);
    }

    @Test
    public void supportMustBeReachedByTheNextFall() {
        landing(1.0, 1.17675927506424, 1.625);
        last.yDistance = -0.07544406518948656;
        assertFalse(step(0.1024, to.getY() - from.getY()));
        verify(data, never()).setSetBack(from);
    }

    @Test
    public void risePlusSupportGapNeverExceedsBoatHeight() {
        landing(0, 0.12, 0.56252);
        assertFalse(step(0.2, to.getY() - from.getY()));
        landing(0, 0.12, 0.5625);
        assertLanding(0.2);
    }

    @Test
    public void unsupportedOneAndOnePointOneBlockRisesStayRejected() {
        when(from.isOnGround(anyDouble(), eq(0.0), eq(0.0))).thenReturn(false);
        for (double rise : new double[] {1.0, 1.1}) {
            final double top = from.getY() + rise;
            when(to.getY()).thenReturn(top);
            assertFalse(step(0.1, rise));
        }
        verify(data, never()).setSetBack(from);
    }

    @Test
    public void hoveringAscendingAndGroundedHistoryDoNotBecomeLandingSteps() {
        last.yDistance = 0;
        assertFalse(step(0.2, 0.441));
        last.yDistance = 0.42;
        assertFalse(step(0.2, 0.441));
        last.yDistance = -0.374;
        last.from.onGround = true;
        last.touchedGround = true;
        assertFalse(step(0.2, 0.441));
    }

    @Test
    public void missingEndpointContactCannotResetTheAnchor() {
        when(to.stepsOnEntityTop(eq(from), anyDouble())).thenReturn(false);
        assertFalse(step(0.2, 0.441));
        verify(data, never()).setSetBack(from);
    }

    @Test
    public void directBlockTopFallsBackToBase() {
        when(to.stepsOnEntityTop(eq(from), anyDouble())).thenReturn(false);
        when(to.isOnGround(anyDouble(), eq(0.0), eq(0.0))).thenReturn(true);
        assertFalse(step(0.2, 0.441));
    }

    @Test
    public void unsupportedEndpointAndResetConditionsDoNotBecomeLandingSteps() {
        when(to.isOnGround()).thenReturn(false);
        assertFalse(step(0.2, 0.441));
        when(to.isOnGround()).thenReturn(true);
        when(from.isResetCond()).thenReturn(true);
        assertFalse(step(0.2, 0.441));
        when(from.isResetCond()).thenReturn(false);
        when(to.isResetCond()).thenReturn(true);
        assertFalse(step(0.2, 0.441));
        when(to.isResetCond()).thenReturn(false);
        last.to.resetCond = true;
        assertFalse(step(0.2, 0.441));
    }

    @Test
    public void configuredStepHeightCanNarrowButNeverWidenTheLandingBudget() throws Exception {
        stepHeight(0.5);
        assertFalse(step(0.2, 0.441));
        stepHeight(1.1);
        landing(0, 0.12, 0.9);
        assertFalse(step(0.2, 0.78));
    }

    @Test
    public void horizontalGateDoesNotInheritLargePreviousAllowances() {
        last.hAllowedDistanceBase = 4.0;
        assertLanding(0.4);
        assertFalse(step(current.walkSpeed * 1.82 + 0.000001, 0.441));
        assertFalse(step(0.9, 0.441));
    }

    @Test
    public void oldStepRetainsItsOriginalHorizontalGate() {
        when(from.isOnGround(anyDouble(), eq(0.0), eq(0.0))).thenReturn(false);
        assertFalse(step(0.1, 1.0));
        assertTrue(step(0.0, 1.0));
        assertTrue(tags.contains("lostground_step"));
    }

    @Test
    public void highAirborneAnchorAndFenceTopNeedReachableSupport() {
        landing(0, 1.1215, 1.5625);
        assertFalse(step(0.2, 0.441));
        landing(0, 1.1, 1.5);
        assertFalse(step(0.2, 0.4));
    }

    @Test
    public void legacyEntityToleranceCannotSupplyNewLandingContact() {
        ServerVersion.setMinecraftVersion("1.21.11");
        assertFalse(step(0.2, 0.441));
    }

    private void stepHeight(double height) throws Exception {
        final var field = MovingConfig.class.getField("sfStepHeight");
        field.setAccessible(true);
        field.setDouble(config, height);
    }

    private void landing(double support, double feet, double top) {
        when(from.getY()).thenReturn(feet);
        when(to.getY()).thenReturn(top);
        when(from.isOnGround(anyDouble(), eq(0.0), eq(0.0))).thenAnswer(call ->
                (double) call.getArgument(0) >= feet - support);
    }

    private void assertLanding(double horizontal) {
        assertEquals("supported landing", !base, step(horizontal, to.getY() - from.getY()));
    }

    private boolean step(double horizontal, double vertical) {
        try (MockedStatic<BlockProperties> blocks = mockStatic(BlockProperties.class)) {
            return LostGround.lostGround(player, from, to, horizontal, vertical, true,
                    last, data, config, null, tags);
        }
    }
}
