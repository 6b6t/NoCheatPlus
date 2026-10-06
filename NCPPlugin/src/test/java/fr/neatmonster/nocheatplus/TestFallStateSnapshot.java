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
            when(player.getVelocity()).thenReturn(new org.bukkit.util.Vector(0,-0.8,0));
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
            else { FallStateSnapshot.clear(f.player); f.location.setY(90); }
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
        Fixture f=new Fixture();f.save();f.data.clearNoFallData();FallStateSnapshot.clear(f.player);f.location.setY(81);
        var trace=mock(fr.neatmonster.nocheatplus.checks.moving.model.MoveTrace.class);
        var placement=new fr.neatmonster.nocheatplus.checks.moving.model.PlayerMoveData();placement.from.onGround=true;
        when(trace.getFirstPastMove()).thenReturn(placement);
        var field=MovingData.class.getField("playerMoves");field.setAccessible(true);field.set(f.data,trace);
        fr.neatmonster.nocheatplus.checks.moving.player.NoFall.afterTeleport(f.player,f.data,f.location,0,true);
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
        assertEquals(82.565, f.data.noFallMaxY, 0);
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
        assertEquals(91, f.data.noFallMaxY, 0);
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
        Fixture f=new Fixture();f.save();f.data.clearNoFallData();FallStateSnapshot.clear(f.player);f.location.setX(600);
        FallStateSnapshot.restore(f.player,f.data);assertTrue(f.serialized.isEmpty());
        assertEquals(0,f.data.noFallContactFallDebt,0);
    }
    @Test public void migrationDuringGlideKeepsExplicitSpoofDebt() {
        Fixture f=new Fixture();f.save();f.data.noFallContactFallDebt=12;
        FallStateSnapshot.save(f.player,f.data,f.location);f.data.clearNoFallData();when(f.player.isGliding()).thenReturn(true);
        when(f.player.getFallDistance()).thenReturn(1f);FallStateSnapshot.restore(f.player,f.data);
        assertEquals(12,f.data.noFallContactFallDebt,0);assertEquals(23.435f,f.data.noFallFallDistance,0);
    }

    @Test public void nativeDriftAndRelocatedMigrationPreserveZeroNativeContactDebt() {
        for (double dx : new double[]{0,0.001,0.05,12.4}) for (double dy : new double[]{0,-0.001,-0.05}) {
            Fixture source=new Fixture(); source.data.noFallMaxY=source.location.getY();source.data.noFallContactFallDebt=12;
            FallStateSnapshot.save(source.player,source.data,source.location);
            Fixture target=new Fixture();target.serialized.putAll(source.serialized);target.location.add(dx,dy,0);
            FallStateSnapshot.restore(target.player,target.data);
            assertEquals(12,target.data.noFallContactFallDebt,0);
            assertEquals(target.location.getY(),target.data.noFallMaxY,0);
            verify(target.player,never()).setFallDistance(anyFloat());
            FallStateSnapshot.save(target.player,target.data,target.location);
            Fixture third=new Fixture();third.serialized.putAll(target.serialized);
            FallStateSnapshot.restore(third.player,third.data);assertEquals(12,third.data.noFallContactFallDebt,0);
        }
    }

    @Test public void nativeGlideLimitCrossesMigrationWithoutForgivingACompletedContact() {
        Fixture f=new Fixture();f.save();f.data.noFallGlideEstablished=true;f.data.noFallGlideLimitPending=true;
        f.data.noFallContactFallDebt=11.563f;when(f.player.isGliding()).thenReturn(true);
        FallStateSnapshot.save(f.player,f.data,f.location);f.data.clearNoFallData();f.data.clearGlideDebt();
        FallStateSnapshot.restore(f.player,f.data);assertTrue(f.data.noFallGlideLimitPending);
        when(f.player.getFallDistance()).thenReturn(0f);
        fr.neatmonster.nocheatplus.checks.moving.player.NoFall.onGlidingMove(f.player,f.data,-.2);
        assertEquals(1.2f,f.data.noFallFallDistance,1e-6);assertEquals(11.563f,f.data.noFallContactFallDebt,0);
    }

    /** REVIEW-CODE-3 P1: a queued owner probe that ends the fall must also end
     * its worker checkpoint; stale/retired callbacks must never clear a newer one. */
    @Test public void ownerProbeResetInvalidatesTheWorkerCheckpoint() throws Exception {
        String old = fr.neatmonster.nocheatplus.compat.versions.ServerVersion.getMinecraftVersion();
        fr.neatmonster.nocheatplus.compat.versions.ServerVersion.setMinecraftVersion("26.2");
        try {
            for (String kind : new String[] {"geometric", "contact"}) {
                for (String mode : new String[] {"water", "policy", "allow-flight", "vehicle", "flying", "moving-off",
                        "nofall-off", "air-control", "stale-vehicle", "replaced-data-vehicle", "offline-control"}) {
                    runOwnerProbe(kind, mode);
                }
            }
        } finally {
            fr.neatmonster.nocheatplus.compat.versions.ServerVersion.setMinecraftVersion(old);
        }
    }

    private static void runOwnerProbe(String kind, String mode) throws Exception {
        try (var bukkit = mockStatic(Bukkit.class);
             var folia = mockStatic(fr.neatmonster.nocheatplus.compat.Folia.class);
             var movement = mockStatic(fr.neatmonster.nocheatplus.checks.moving.util.MovingUtil.class)) {
            Fixture source = new Fixture();
            MovingData data = source.data; Player player = source.player;
            data.noFallMaxY = source.location.getY() + ("geometric".equals(kind) ? 12 : 0);
            data.noFallFallDistance = "geometric".equals(kind) ? 12 : 0;
            data.noFallContactFallDebt = "contact".equals(kind) ? 12 : 0;
            FallStateSnapshot.save(player, data, source.location);
            assertEquals(2, source.serialized.size());
            var pd = mock(fr.neatmonster.nocheatplus.players.IPlayerData.class);
            var cc = mock(fr.neatmonster.nocheatplus.checks.moving.MovingConfig.class);
            var nf = mock(fr.neatmonster.nocheatplus.checks.moving.player.NoFall.class, CALLS_REAL_METHODS);
            when(pd.getGenericInstance(MovingData.class)).thenReturn(mode.startsWith("replaced") ? mock(MovingData.class) : data);
            when(pd.getGenericInstance(fr.neatmonster.nocheatplus.checks.moving.MovingConfig.class)).thenReturn(cc);
            when(pd.isCheckActive(fr.neatmonster.nocheatplus.checks.CheckType.MOVING, player)).thenReturn(!"moving-off".equals(mode));
            when(nf.isEnabled(player, pd)).thenReturn(!"nofall-off".equals(mode));
            when(player.isOnline()).thenReturn(!"offline-control".equals(mode));
            when(player.isInsideVehicle()).thenReturn(mode.endsWith("vehicle"));
            when(player.isFlying()).thenReturn("flying".equals(mode));
            when(player.getAllowFlight()).thenReturn("allow-flight".equals(mode));
            TestClientNoFallSequences.field(cc, fr.neatmonster.nocheatplus.checks.moving.MovingConfig.class, "noFallSkipAllowFlight", true);
            var listener = mock(fr.neatmonster.nocheatplus.checks.moving.MovingListener.class, CALLS_REAL_METHODS);
            var aux = mock(fr.neatmonster.nocheatplus.checks.moving.util.AuxMoving.class);
            var info = mock(fr.neatmonster.nocheatplus.checks.moving.model.PlayerMoveInfo.class);
            var support = mock(fr.neatmonster.nocheatplus.utilities.location.PlayerLocation.class);
            TestClientNoFallSequences.field(info, fr.neatmonster.nocheatplus.checks.moving.model.MoveInfo.class, "from", support);
            when(aux.usePlayerMoveInfo()).thenReturn(info);
            TestClientNoFallSequences.field(listener, fr.neatmonster.nocheatplus.checks.moving.MovingListener.class, "aux", aux);
            TestClientNoFallSequences.field(listener, fr.neatmonster.nocheatplus.checks.moving.MovingListener.class, "noFall", nf);
            TestClientNoFallSequences.field(data, MovingData.class, "playerMoves", mock(fr.neatmonster.nocheatplus.checks.moving.model.MoveTrace.class));
            var access = mock(fr.neatmonster.nocheatplus.compat.MCAccess.class);
            var handle = mock(fr.neatmonster.nocheatplus.components.registry.event.IGenericInstanceHandle.class);
            when(handle.getHandle()).thenReturn(access);
            TestClientNoFallSequences.field(listener, fr.neatmonster.nocheatplus.checks.CheckListener.class, "mcAccess", handle);
            when(support.isResetCond()).thenReturn("water".equals(mode));
            movement.when(() -> fr.neatmonster.nocheatplus.checks.moving.util.MovingUtil.shouldCheckSurvivalFly(player, support, null, data, cc, pd))
                    .thenReturn(!"policy".equals(mode));
            folia.when(fr.neatmonster.nocheatplus.compat.Folia::isFoliaServer).thenReturn(true);
            folia.when(() -> fr.neatmonster.nocheatplus.compat.Folia.isOwnedByCurrentRegion(player)).thenReturn(true);
            var plugins = mock(org.bukkit.plugin.PluginManager.class); var plugin = mock(org.bukkit.plugin.Plugin.class);
            bukkit.when(Bukkit::getServer).thenReturn(mock(Server.class)); bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
            when(plugins.getPlugin("NoCheatPlus")).thenReturn(plugin);
            var task = new java.util.concurrent.atomic.AtomicReference<java.util.function.Consumer<Object>>();
            folia.when(() -> fr.neatmonster.nocheatplus.compat.Folia.runSyncDelayedTaskForEntity(eq(player), eq(plugin), any(), any(), eq(1L)))
                    .thenAnswer(c -> { task.set(c.getArgument(2)); return new Object(); });
            var queue = fr.neatmonster.nocheatplus.checks.moving.MovingListener.class.getDeclaredMethod("queueNativeLanding", Player.class,
                    MovingData.class, fr.neatmonster.nocheatplus.checks.moving.MovingConfig.class, fr.neatmonster.nocheatplus.players.IPlayerData.class);
            queue.setAccessible(true);
            queue.invoke(listener, player, data, cc, pd);
            if ("stale-vehicle".equals(mode)) {
                // A reset after queueing starts a newer fall with its own checkpoint.
                data.clearNoFallData(); data.noFallMaxY = source.location.getY() + 7; data.noFallFallDistance = 7;
                FallStateSnapshot.save(player, data, source.location);
            }
            task.get().accept(null);
            Fixture dest = new Fixture(); dest.serialized.putAll(source.serialized);
            dest.location.add(.001, -.001, 0);
            FallStateSnapshot.restore(dest.player, dest.data, true);
            final float restored = Math.max(dest.data.noFallFallDistance,
                    fr.neatmonster.nocheatplus.checks.moving.player.NoFall.retainedNativeResetDebt(dest.data));
            final String c = kind + "/" + mode;
            if (mode.endsWith("control") || mode.startsWith("replaced")) {
                assertEquals(c, 2, source.serialized.size()); assertEquals(c, 12f, restored, 1e-4);
            } else if ("stale-vehicle".equals(mode)) {
                assertEquals(c, 2, source.serialized.size()); assertEquals(c, 7f, restored, 1e-4);
            } else {
                assertEquals(c, 0, source.serialized.size()); assertEquals(c, 0f, restored, 0f);
                assertEquals(c, 0f, Math.max(data.noFallFallDistance,
                        fr.neatmonster.nocheatplus.checks.moving.player.NoFall.retainedNativeResetDebt(data)), 0f);
            }
        }
    }
}
