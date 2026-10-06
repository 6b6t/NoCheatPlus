package fr.neatmonster.nocheatplus;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.persistence.*;
import org.junit.Test;
import fr.neatmonster.nocheatplus.checks.moving.MovingData;
import fr.neatmonster.nocheatplus.checks.moving.player.FallStateSnapshot;

public class TestFallStateSnapshot {
    private static class Fixture {
        final Player player = mock(Player.class);
        final World world = mock(World.class);
        final MovingData data = mock(MovingData.class, CALLS_REAL_METHODS);
        final PersistentDataContainer container = mock(PersistentDataContainer.class);
        final Map<NamespacedKey, Object> serialized = new HashMap<>();
        final Location location = new Location(world, 520.5, 81.565, 16.5);
        Fixture() {
            when(world.getKey()).thenReturn(NamespacedKey.minecraft("overworld"));
            when(player.getLocation()).thenReturn(location);
            when(player.getPersistentDataContainer()).thenReturn(container);
            doAnswer(call -> {
                Object value = call.getArgument(2);
                serialized.put(call.getArgument(0), value instanceof long[] ? ((long[]) value).clone() : value);
                return null;
            }).when(container).set(any(), any(), any());
            when(container.get(any(), any())).thenAnswer(call -> serialized.get(call.getArgument(0)));
            doAnswer(call -> { serialized.remove(call.getArgument(0)); return null; }).when(container).remove(any());
        }
        void save() {
            data.noFallMaxY = 105; data.noFallFallDistance = 23.435f;
            FallStateSnapshot.save(player, data, location);
        }
    }
    @Test public void serializedCheckpointRestoresOnANewWorkerWithoutDoubleCounting() {
        Fixture source = new Fixture(); source.save();
        Fixture target = new Fixture(); target.serialized.putAll(source.serialized);
        FallStateSnapshot.restore(target.player, target.data);
        FallStateSnapshot.restore(target.player, target.data);
        assertEquals(105, target.data.noFallMaxY, 0);
        assertEquals(23.435f, target.data.noFallFallDistance, 0);
        // Both pieces of the descent total the original 165-block fall.
        target.data.noFallFallDistance += 141.565f;
        assertEquals(165, target.data.noFallFallDistance, 1.0e-4);
    }
    @Test public void lifecycleClearCannotReviveOldDescent() {
        Fixture f = new Fixture(); f.save(); FallStateSnapshot.clear(f.player);
        f.data.clearNoFallData(); FallStateSnapshot.restore(f.player, f.data);
        assertEquals(0, f.data.noFallFallDistance, 0); assertTrue(f.serialized.isEmpty());
    }
    @Test public void worldAndPlacementMismatchesDiscardTheCheckpoint() {
        for (boolean changeWorld : new boolean[] {false, true}) {
            Fixture f = new Fixture(); f.save(); f.data.clearNoFallData();
            if (changeWorld) when(f.world.getKey()).thenReturn(NamespacedKey.minecraft("the_nether"));
            else f.location.setY(90);
            FallStateSnapshot.restore(f.player, f.data);
            assertEquals(0, f.data.noFallFallDistance, 0); assertTrue(f.serialized.isEmpty());
        }
    }
    @Test public void invalidMetadataCannotCreateFallDistance() {
        for (double[] invalid : new double[][] {{1}, {Double.NaN,105,23,Double.NaN}, {81.565,105,-1,Double.NaN}, {81.565,70,23,Double.NaN}}) {
            Fixture f = new Fixture(); f.save(); f.data.clearNoFallData();
            f.serialized.put(new NamespacedKey("nocheatplus", "fall_state_v2"), java.util.Arrays.stream(invalid).mapToLong(Double::doubleToRawLongBits).toArray());
            FallStateSnapshot.restore(f.player, f.data);
            assertEquals(0, f.data.noFallFallDistance, 0); assertTrue(f.serialized.isEmpty());
        }
    }
    @Test public void completedLandingRemovesBothKeys() {
        Fixture f = new Fixture(); f.save(); f.data.clearNoFallData();
        FallStateSnapshot.save(f.player, f.data, f.location); assertTrue(f.serialized.isEmpty());
    }
    @Test public void ordinaryWorkerTeleportCannotReviveResetSourceDistance() throws Exception {
        Fixture f=new Fixture();f.save();f.data.clearNoFallData();f.location.setY(81);
        var trace=mock(fr.neatmonster.nocheatplus.checks.moving.model.MoveTrace.class);
        var placement=new fr.neatmonster.nocheatplus.checks.moving.model.PlayerMoveData();placement.from.onGround=true;
        when(trace.getFirstPastMove()).thenReturn(placement);
        var field=MovingData.class.getField("playerMoves");field.setAccessible(true);field.set(f.data,trace);
        FallStateSnapshot.restore(f.player,f.data,true);
        verify(f.player, never()).setFallDistance(anyFloat());
        assertEquals(81,f.data.noFallMaxY,0);assertEquals(0,f.data.noFallFallDistance,0);
        assertTrue(f.data.noFallSkipAirCheck);
    }
    @Test public void existingWindChargeFallOriginSurvivesMigration() {
        Fixture f = new Fixture(); f.data.noFallCurrentLocOnWindChargeHit = new Location(f.world,16,90,16);
        f.save(); f.data.noFallCurrentLocOnWindChargeHit = null;
        FallStateSnapshot.restore(f.player, f.data);
        assertEquals(90, f.data.noFallCurrentLocOnWindChargeHit.getY(),0);
    }
    @Test public void wrongPrimitiveTypeIsDiscardedWithoutAnException() {
        Fixture f = new Fixture(); f.save(); f.data.clearNoFallData();
        f.serialized.put(new NamespacedKey("nocheatplus","fall_state_v2"),"invalid");
        FallStateSnapshot.restore(f.player,f.data); assertTrue(f.serialized.isEmpty());
        assertEquals(0,f.data.noFallFallDistance,0);
    }

    @Test public void ascentCanCarryWindOriginWithoutAddingDamageAndClearsOldOrigins() {
        Fixture f = new Fixture(); f.data.noFallMaxY = f.location.getY();
        f.data.noFallCurrentLocOnWindChargeHit = new Location(f.world,16,80,16);
        FallStateSnapshot.save(f.player,f.data,f.location);
        Fixture next = new Fixture(); next.serialized.putAll(f.serialized);
        FallStateSnapshot.restore(next.player,next.data);
        assertEquals(80,next.data.noFallCurrentLocOnWindChargeHit.getY(),0);
        f.data.noFallCurrentLocOnWindChargeHit = null; f.save();
        next.serialized.putAll(f.serialized); FallStateSnapshot.restore(next.player,next.data);
        assertNull(next.data.noFallCurrentLocOnWindChargeHit);
    }

    @Test public void legacyGlideDebtCannotReviveAcrossWorkers() {
        Fixture f = new Fixture(); f.save();
        f.serialized.put(new NamespacedKey("nocheatplus", "fall_state_v2"),
                java.util.Arrays.stream(new double[]{81.565,105,23.435,Double.NaN,4,23.435,0})
                        .mapToLong(Double::doubleToRawLongBits).toArray());
        when(f.player.getFallDistance()).thenReturn(1f);
        FallStateSnapshot.restore(f.player, f.data);
        assertEquals(1f, f.data.noFallFallDistance, 0);
        assertEquals(81.565, f.data.noFallMaxY, 0);
        assertEquals(0f, f.data.noFallGlideCarry, 0);
        verify(f.player, never()).setFallDistance(anyFloat());
    }
    @Test public void legacyGlideDebtCannotReviveDuringAnOrdinaryWorkerTeleport() {
        Fixture f = new Fixture(); f.save();
        f.serialized.put(new NamespacedKey("nocheatplus", "fall_state_v2"),
                java.util.Arrays.stream(new double[]{81.565,105,23.435,Double.NaN,4,23.435,0})
                        .mapToLong(Double::doubleToRawLongBits).toArray());
        f.location.setY(90);
        when(f.player.getFallDistance()).thenReturn(1f);
        FallStateSnapshot.restore(f.player, f.data, true);
        assertEquals(1f, f.data.noFallFallDistance, 0);
        assertEquals(90, f.data.noFallMaxY, 0);
        verify(f.player, never()).setFallDistance(anyFloat());
    }

    @Test public void explicitDebtsRoundTripWithNativeZeroAndWithoutConnectionState() {
        Fixture source=new Fixture();source.data.noFallMaxY=source.location.getY();
        source.data.noFallCancelledAirFallDebt=11;source.data.noFallSetBackFallDebt=7;source.data.noFallContactFallDebt=12;
        source.data.noFallMatchedPacketSequence=998;source.data.noFallNativeLandingTaskPending=true;
        FallStateSnapshot.save(source.player,source.data,source.location);
        Fixture target=new Fixture();target.serialized.putAll(source.serialized);
        FallStateSnapshot.restore(target.player,target.data);FallStateSnapshot.restore(target.player,target.data);
        assertEquals(11,target.data.noFallCancelledAirFallDebt,0);assertEquals(7,target.data.noFallSetBackFallDebt,0);
        assertEquals(12,target.data.noFallContactFallDebt,0);assertEquals(0,target.data.noFallFallDistance,0);
        assertEquals(0,target.data.noFallMatchedPacketSequence);assertFalse(target.data.noFallNativeLandingTaskPending);
    }
    @Test public void eachExplicitDebtAloneKeepsTheCheckpoint() {
        for(int kind=0;kind<3;kind++) {
            Fixture f=new Fixture();f.data.noFallMaxY=f.location.getY();
            if(kind==0)f.data.noFallCancelledAirFallDebt=12;
            if(kind==1)f.data.noFallSetBackFallDebt=12;
            if(kind==2)f.data.noFallContactFallDebt=12;
            FallStateSnapshot.save(f.player,f.data,f.location);f.data.clearNoFallData();
            FallStateSnapshot.restore(f.player,f.data);
            assertEquals(12,fr.neatmonster.nocheatplus.checks.moving.player.NoFall.retainedNativeResetDebt(f.data),0);
        }
    }
    @Test public void invalidExplicitDebtCannotSurviveMigration() {
        for(double invalid:new double[]{Double.NaN,Double.POSITIVE_INFINITY,-1,(double)Float.MAX_VALUE*2}) {
            Fixture f=new Fixture();f.save();f.data.clearNoFallData();
            long[] bits=(long[])f.serialized.get(new NamespacedKey("nocheatplus","fall_state_v2"));
            bits[9]=Double.doubleToRawLongBits(invalid);FallStateSnapshot.restore(f.player,f.data);
            assertTrue(f.serialized.isEmpty());assertEquals(0,f.data.noFallContactFallDebt,0);
        }
    }
    @Test public void sameHeightHorizontalTeleportDiscardsExplicitDebt() {
        Fixture f=new Fixture();f.save();f.data.clearNoFallData();f.location.setX(600);
        FallStateSnapshot.restore(f.player,f.data);assertTrue(f.serialized.isEmpty());
        assertEquals(0,f.data.noFallContactFallDebt,0);
    }
    @Test public void serverConfirmedGlideCannotReviveExplicitDebt() {
        Fixture f=new Fixture();f.save();f.data.noFallContactFallDebt=12;
        FallStateSnapshot.save(f.player,f.data,f.location);f.data.clearNoFallData();when(f.player.isGliding()).thenReturn(true);
        when(f.player.getFallDistance()).thenReturn(1f);FallStateSnapshot.restore(f.player,f.data);
        assertEquals(0,f.data.noFallContactFallDebt,0);assertEquals(1,f.data.noFallFallDistance,0);
    }
}
