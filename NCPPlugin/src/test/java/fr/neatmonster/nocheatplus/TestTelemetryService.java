package fr.neatmonster.nocheatplus;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.*;
import org.junit.Test;
import fr.neatmonster.nocheatplus.checks.*;
import fr.neatmonster.nocheatplus.checks.moving.MovingData;
import fr.neatmonster.nocheatplus.checks.moving.model.*;
import fr.neatmonster.nocheatplus.compat.Folia;
import fr.neatmonster.nocheatplus.players.*;
import fr.neatmonster.nocheatplus.telemetry.*;

public class TestTelemetryService {
    @Test public void realSamplingKeepsGeneralCohortAndExcludesBotsAndUnknowns() throws Exception {
        for (Boolean classification : Arrays.asList(false, true, null)) {
            Player p=player(); TelemetryWriter w=writer();
            TelemetryService.BotCache cache=new TelemetryService.BotCache(){
                @Override Boolean lookup(UUID id){return classification;}
                @Override Boolean lookupTempbot(UUID id){return classification == null ? null : false;}
            };
            TelemetryService service=new TelemetryService(w,"fox-eu-1",0,100,100,cache);
            PlayerMoveEvent event=new PlayerMoveEvent(p,p.getLocation(),p.getLocation());
            try(var folia=mockStatic(Folia.class)) {
                folia.when(()->Folia.isOwnedByCurrentRegion(p)).thenReturn(true); service.begin(event);service.end(event);
            }
            assertEquals(Boolean.FALSE.equals(classification)?1:0,w.queueSize());
            if(w.queueSize()>0){assertEquals(100d,records(w).peek().fields.get("sample_percent"));assertEquals(false,records(w).peek().fields.get("bot"));}
            // General sampling remains independently available for all classifications, including bots/unknowns.
            TelemetryService general=new TelemetryService(w,"fox-eu-1",100,10,100,cache);
            try(var folia=mockStatic(Folia.class)) {folia.when(()->Folia.isOwnedByCurrentRegion(p)).thenReturn(true);general.begin(event);general.end(event);}
            assertEquals(Boolean.FALSE.equals(classification)?2:1,w.queueSize());
            assertEquals(100d,new ArrayList<>(records(w)).get(w.queueSize()-1).fields.get("sample_percent"));
        }
        for(double bad:new double[]{-1,101,Double.NaN,Double.POSITIVE_INFINITY}) {
            assertFalse(TelemetryService.validPercent(bad));
            try {new TelemetryService(writer(),"worker",1,bad,100,bots());fail("Accepted invalid real rate");}catch(IllegalArgumentException expected){}
        }
        assertTrue(TelemetryService.validPercent(0));assertTrue(TelemetryService.validPercent(100));
        assertEquals(0d,new fr.neatmonster.nocheatplus.config.DefaultConfig().getDouble("telemetry.move-sample-real-percent"),0);
    }
    @Test public void missingPolicyNeighbourhoodsCaptureBothEndpointsAndKeepOriginsRelative() throws Exception {
        for(String reason:List.of("nofall-native-air-landing","owner-correction","poll-correction")) {
            Player p=player(); World world=p.getWorld();
            org.bukkit.block.Block block=mock(org.bukkit.block.Block.class);
            when(world.isChunkLoaded(anyInt(),anyInt())).thenReturn(true);
            when(world.getMinHeight()).thenReturn(-64);when(world.getMaxHeight()).thenReturn(320);
            when(world.getBlockAt(anyInt(),anyInt(),anyInt())).thenReturn(block);when(block.getType()).thenReturn(Material.OBSIDIAN);
            TelemetryWriter w=writer(); TelemetryService service=new TelemetryService(w,"fox-eu-1",0,100,bots());
            IPlayerData pd=mock(IPlayerData.class);
            try(var folia=mockStatic(Folia.class);var data=mockStatic(DataManager.class)) {
                folia.when(()->Folia.isOwnedByCurrentRegion(p)).thenReturn(true);
                folia.when(()->Folia.isOwnedByCurrentRegion(any(Location.class))).thenReturn(true);
                data.when(()->DataManager.getPlayerData(p)).thenReturn(pd);
                // Outside a move: the accepted endpoint supplies from, current owned position supplies to.
                PlayerMoveEvent accepted=new PlayerMoveEvent(p,new Location(world,100,64,100),new Location(world,101,64,100));
                service.begin(accepted);service.end(accepted);service.setback(p,reason);
                var r=records(w).peek().fields;assertEquals(reason,r.get("tags"));assertNull(r.get("blocks_skipped"));
                for(String key:List.of("blocks_from","blocks_to")) {
                    Map<?,?> snapshot=(Map<?,?>)r.get(key);assertEquals(key.equals("blocks_from")?List.of(-1d,-1d,-1d):List.of(-101d,-1d,-100d),snapshot.get("origin"));
                    assertEquals(List.of(3,4,3),snapshot.get("size"));assertEquals(36,((List<?>)snapshot.get("cells")).size());
                }
                assertEquals(72,org.mockito.Mockito.mockingDetails(world).getInvocations().stream().filter(v->v.getMethod().getName().equals("getBlockAt")).count());
                verify(world,never()).getChunkAt(anyInt(),anyInt());verify(block,never()).getCollisionShape();
                clearInvocations(world);
                folia.when(()->Folia.isOwnedByCurrentRegion(any(Location.class))).thenReturn(false);
                service.setback(p,reason);var skipped=new ArrayList<>(records(w)).get(1).fields;
                assertNull(skipped.get("blocks_from"));assertNull(skipped.get("blocks_to"));
                assertEquals("neighbourhood-unowned-or-unloaded",skipped.get("blocks_skipped"));
                verify(world,never()).isChunkLoaded(anyInt(),anyInt());verify(world,never()).getBlockAt(anyInt(),anyInt(),anyInt());
            }
        }
    }
    @Test public void correctionDestinationAndBlockFailureRetainIncidentAndOtherEndpoint() throws Exception {
        for (String reason : List.of("nofall-native-air-landing", "owner-correction", "poll-correction")) {
            Player p=player();World world=p.getWorld();TelemetryWriter w=writer();
            TelemetryService service=new TelemetryService(w,"worker-0",0,100,bots());
            IPlayerData pd=mock(IPlayerData.class);org.bukkit.block.Block block=mock(org.bukkit.block.Block.class);
            when(world.isChunkLoaded(anyInt(),anyInt())).thenReturn(true);
            when(world.getMinHeight()).thenReturn(-64);when(world.getMaxHeight()).thenReturn(320);
            when(block.getType()).thenReturn(Material.OBSIDIAN);
            when(world.getBlockAt(anyInt(),anyInt(),anyInt())).thenAnswer(inv -> {
                if ((int)inv.getArgument(0)<10) throw new IllegalStateException("unavailable");
                return block;
            });
            Location target=new Location(world,30.123456,70.123456,40.123456);
            try(var folia=mockStatic(Folia.class);var data=mockStatic(DataManager.class)) {
                folia.when(()->Folia.isOwnedByCurrentRegion(p)).thenReturn(true);
                folia.when(()->Folia.isOwnedByCurrentRegion(any(Location.class))).thenReturn(true);
                data.when(()->DataManager.getPlayerData(p)).thenReturn(pd);
                // Trusted destination can advance independently of the last accepted move.
                PlayerMoveEvent accepted=new PlayerMoveEvent(p,new Location(world,100,64,100),new Location(world,101,64,100));
                service.begin(accepted);service.end(accepted);
                Telemetry.install(service);
                try { Telemetry.setback(p,reason,target); } finally { Telemetry.install(null); }
                assertEquals(1,w.queueSize());assertEquals(1,w.captureErrors.get());
                Map<String,Object> r=records(w).peek().fields;
                assertEquals(List.of(0d,0d,0d,0d,0d),r.get("from"));
                assertEquals(List.of(29.123,6.123,39.123,0d,0d),r.get("to"));
                assertNull(r.get("blocks_from"));assertNotNull(r.get("blocks_to"));
                assertEquals(List.of(28d,5d,38d),((Map<?,?>)r.get("blocks_to")).get("origin"));
                verify(world).getBlockAt(30,70,40);
                // If both endpoints fail, a second incident still survives.
                when(world.isChunkLoaded(anyInt(),anyInt())).thenThrow(new IllegalStateException());
                service.setback(p,reason,target);assertEquals(2,w.queueSize());assertEquals(3,w.captureErrors.get());
                // A destination-only failure preserves the source neighbourhood as well.
                doReturn(true).when(world).isChunkLoaded(anyInt(),anyInt());
                doAnswer(inv -> {
                    if ((int)inv.getArgument(0)>10) throw new IllegalStateException("unavailable");
                    return block;
                }).when(world).getBlockAt(anyInt(),anyInt(),anyInt());
                service.setback(p,reason,target);assertEquals(3,w.queueSize());assertEquals(4,w.captureErrors.get());
                Map<String,Object> destinationFailed=new ArrayList<>(records(w)).get(2).fields;
                assertNotNull(destinationFailed.get("blocks_from"));assertNull(destinationFailed.get("blocks_to"));
            }
        }
    }
    @Test public void privacyIsTranslationInvariantAndDoesNotMutateCheckSnapshots() throws Exception {
        Player p=player();TelemetryWriter w=writer();TelemetryService service=new TelemetryService(w,"worker-0",100,100,bots());
        IPlayerData pd=mock(IPlayerData.class);Check check=mock(Check.class);when(check.getType()).thenReturn(CheckType.MOVING_CREATIVEFLY);
        ViolationData vd=mock(ViolationData.class);TestClientNoFallSequences.field(vd,ViolationData.class,"check",check);TestClientNoFallSequences.field(vd,ViolationData.class,"player",p);
        List<Map<String,Object>> captured=new ArrayList<>();
        try(var folia=mockStatic(Folia.class);var data=mockStatic(DataManager.class)) {
            folia.when(()->Folia.isOwnedByCurrentRegion(p)).thenReturn(true);data.when(()->DataManager.getPlayerData(p)).thenReturn(pd);
            for(double offset:new double[]{1000,9000,-5000}) {
                List<Object> from=Arrays.asList(offset+.25,offset+.5,offset+.75,1f,2f),to=Arrays.asList(offset+1.25,offset+2.5,offset+3.75,3f,4f);
                Map<String,Object> blocks=Map.of("origin",List.of((int)offset-1,(int)offset-1,(int)offset-1),"size",List.of(3,4,3),"cells",Collections.nCopies(36,null));
                when(vd.getTelemetryFrom()).thenReturn(from);when(vd.getTelemetryTo()).thenReturn(to);when(vd.getTelemetryBlocksFrom()).thenReturn(blocks);when(vd.getTelemetryBlocksTo()).thenReturn(blocks);
                service.cancel(vd);Map<String,Object> r=new ArrayList<>(records(w)).get(captured.size()).fields;captured.add(r);
                assertFalse(r.containsKey("uuid"));assertFalse(r.containsKey("name"));assertFalse(r.containsKey("world"));assertEquals(2,r.get("v"));
                assertEquals(List.of(.25,.5,.75,1d,2d),r.get("from"));
                assertEquals(List.of(1.25,2.5,3.75,3d,4d),r.get("to"));
                assertEquals(List.of(-1d,-1d,-1d),((Map<?,?>)r.get("blocks_from")).get("origin"));
                assertEquals(List.of((int)offset-1,(int)offset-1,(int)offset-1),blocks.get("origin"));
            }
            for(String key:List.of("from","to","history","blocks_from","blocks_to","player")) assertEquals(captured.get(0).get(key),captured.get(1).get(key));
            for (TelemetryRecord record : records(w)) {
                assertFalse(record.json().contains(p.getName()));
                assertFalse(record.json().contains(p.getUniqueId().toString()));
                assertFalse(record.json().contains(p.getWorld().getUID().toString()));
            }
            String token=(String)captured.get(0).get("player");assertNotEquals(p.getUniqueId().toString(),token);
            service.quit(new org.bukkit.event.player.PlayerQuitEvent(p,"quit"));service.cancel(vd);
            assertNotEquals(token,new ArrayList<>(records(w)).get(records(w).size()-1).fields.get("player"));
        }
    }
    @Test public void externalFolderStartsWithWorkerLockAndDiskGuard() throws Exception {
        Path root = Files.createTempDirectory("ncp-worker-root").toRealPath();
        for (String configured : Arrays.asList("ncp-telemetry", root.resolve("ncp-telemetry").toString())) {
            Path folder = TelemetryService.resolveFolder(root, configured);
            assertEquals(root.resolve("ncp-telemetry"), folder);
            TelemetryWriter writer = new TelemetryWriter(folder, "fox-eu-1", 10, 0, Long.MAX_VALUE, () -> Long.MAX_VALUE, s -> {});
            try {
                writer.start();
                assertTrue(Files.isRegularFile(folder.resolve("fox-eu-1.lock")));
                assertTrue(writer.diskAllowed());
                TelemetryWriter duplicate = new TelemetryWriter(folder, "fox-eu-1", 10, 0, Long.MAX_VALUE, () -> Long.MAX_VALUE, s -> {});
                try { duplicate.start(); fail("Duplicate writer started"); }
                catch (java.nio.channels.OverlappingFileLockException expected) {}
            } finally { writer.close(); }
        }
    }
    @Test public void unsafeConfiguredFoldersAreRejected() throws Exception {
        Path root = Files.createTempDirectory("ncp-worker-root").toRealPath();
        for (String configured : Arrays.asList("", " ", ".", "..", "../escape", "plugins", "plugins/NoCheatPlus/telemetry", "safe/../../escape", root.getParent().resolve("escape").toString())) {
            try { TelemetryService.resolveFolder(root, configured); fail("Accepted " + configured); }
            catch (IllegalArgumentException expected) {}
        }
    }
    @Test public void configuredSymlinkFoldersAndAncestorsFailBeforeCreation() throws Exception {
        Path root = Files.createTempDirectory("ncp-worker-root").toRealPath();
        Path target = Files.createTempDirectory("ncp-outside").toRealPath();
        Files.createSymbolicLink(root.resolve("linked"), target);
        for (String configured : Arrays.asList("linked", "linked/telemetry")) {
            TelemetryWriter writer = new TelemetryWriter(TelemetryService.resolveFolder(root, configured), "fox-eu-1", 10, 0, Long.MAX_VALUE, () -> Long.MAX_VALUE, s -> {});
            try { writer.start(); fail("Accepted symlink " + configured); }
            catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("symlink")); }
        }
        assertFalse(Files.exists(target.resolve("telemetry")));
        assertFalse(Files.exists(target.resolve("fox-eu-1.lock")));
    }
    @Test public void defaultOffStartupDoesNotAccessPluginOrCreateFolders() {
        fr.neatmonster.nocheatplus.config.ConfigFile config = new fr.neatmonster.nocheatplus.config.ConfigFile();
        org.bukkit.plugin.java.JavaPlugin plugin = mock(org.bukkit.plugin.java.JavaPlugin.class);
        assertNull(TelemetryService.start(plugin, config));
        verifyNoInteractions(plugin);
        fr.neatmonster.nocheatplus.config.DefaultConfig defaults = new fr.neatmonster.nocheatplus.config.DefaultConfig();
        assertFalse(defaults.getBoolean("telemetry.enabled"));
        assertEquals("ncp-telemetry", defaults.getString("telemetry.folder"));
    }
    @SuppressWarnings("unchecked") private Queue<TelemetryRecord> records(TelemetryWriter writer) throws Exception {
        Field f=TelemetryWriter.class.getDeclaredField("queue"); f.setAccessible(true);return (Queue<TelemetryRecord>)f.get(writer);
    }
    private Player player() {
        Player p=mock(Player.class);World world=mock(World.class);when(world.getUID()).thenReturn(UUID.randomUUID());when(world.getName()).thenReturn("world");
        when(p.getWorld()).thenReturn(world);when(p.getUniqueId()).thenReturn(UUID.randomUUID());when(p.getName()).thenReturn("Real\"Player");when(p.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(p.getLocation()).thenReturn(new Location(world,1,64,1));when(p.getPing()).thenReturn(160);when(p.getTicksLived()).thenReturn(500);
        PlayerInventory inventory=mock(PlayerInventory.class);when(p.getInventory()).thenReturn(inventory);
        when(inventory.getItemInMainHand()).thenReturn(new ItemStack(Material.STONE));when(inventory.getItemInOffHand()).thenReturn(new ItemStack(Material.AIR));
        when(inventory.getArmorContents()).thenReturn(new ItemStack[4]);when(p.getActivePotionEffects()).thenReturn(Collections.emptyList());return p;
    }
    private TelemetryWriter writer() throws Exception {return new TelemetryWriter(Files.createTempDirectory("ncp-service").toRealPath(),"fox-eu-1",100,0,Long.MAX_VALUE,()->Long.MAX_VALUE,s->{});}
    private TelemetryService.BotCache bots() {return new TelemetryService.BotCache(){@Override Boolean lookup(UUID id){return true;}};}
    @Test public void setbackHasIdentityTagsStateAndExactlyTwentyPreviousAcceptedMoves() throws Exception {
        Player p=player();TelemetryWriter w=writer();TelemetryService service=new TelemetryService(w,"fox-eu-1",100,100,bots());
        IPlayerData pd=mock(IPlayerData.class); MovingData md=mock(MovingData.class);MoveTrace trace=mock(MoveTrace.class);PlayerMoveData current=new PlayerMoveData();
        current.from.extraPropertiesValid=true;current.from.inLiquid=true;current.from.inBubbleStream=true;
        TestClientNoFallSequences.field(md,MovingData.class,"playerMoves",trace);when(trace.getCurrentMove()).thenReturn(current);md.fireworksBoostDuration=10;md.fireworksBoostTickExpire=fr.neatmonster.nocheatplus.utilities.TickTask.getTick()+20;
        when(pd.getGenericInstance(MovingData.class)).thenReturn(md);
        try(var folia=mockStatic(Folia.class);var data=mockStatic(DataManager.class)) {
            folia.when(()->Folia.isOwnedByCurrentRegion(p)).thenReturn(true);data.when(()->DataManager.getPlayerData(p)).thenReturn(pd);
            for(int i=0;i<25;i++) {PlayerMoveEvent event=new PlayerMoveEvent(p,new Location(p.getWorld(),i,64,1),new Location(p.getWorld(),i+1,64,1));service.begin(event);service.end(event);}
            Check check=mock(Check.class);when(check.getType()).thenReturn(CheckType.MOVING_SURVIVALFLY);ViolationData vd=mock(ViolationData.class);
            TestClientNoFallSequences.field(vd,ViolationData.class,"check",check);TestClientNoFallSequences.field(vd,ViolationData.class,"player",p);TestClientNoFallSequences.field(vd,ViolationData.class,"vL",12d);
            when(vd.getTelemetryTags()).thenReturn("hdist+air");when(vd.getTelemetryFrom()).thenReturn(null);when(vd.getTelemetryTo()).thenReturn(null);when(vd.getTelemetryBlocksFrom()).thenReturn(null);when(vd.getTelemetryBlocksTo()).thenReturn(null);
            PlayerMoveEvent rejected=new PlayerMoveEvent(p,new Location(p.getWorld(),25,64,1),new Location(p.getWorld(),40,65,1));service.begin(rejected);service.activity(p,"wind-charge-impulse");service.cancel(vd);rejected.setCancelled(true);service.end(rejected);
            List<TelemetryRecord> setbacks=new ArrayList<>();for(TelemetryRecord r:records(w))if(r.fields.get("kind").equals("setback"))setbacks.add(r);
            assertEquals(1,setbacks.size());Map<String,Object> r=setbacks.get(0).fields;
            assertEquals("fox-eu-1",r.get("worker"));assertFalse(r.containsKey("uuid"));assertFalse(r.containsKey("name"));assertFalse(r.containsKey("world"));assertNotNull(r.get("player"));assertEquals(true,r.get("bot"));assertEquals("hdist+air",r.get("tags"));assertEquals(12d,r.get("vl"));assertEquals(160,r.get("ping"));
            List<?> history=(List<?>)r.get("history");assertEquals(20,history.size());assertEquals(-19d,((List<?>)history.get(0)).get(1));assertEquals(0d,((List<?>)history.get(19)).get(1));
            Map<?,?> flags=(Map<?,?>)r.get("flags");assertEquals(true,flags.get("in_liquid"));assertEquals(true,flags.get("in_bubble_column"));assertEquals(10,flags.get("rocket_remaining_moves"));assertEquals(20,flags.get("rocket_remaining_ticks"));assertEquals(0,flags.get("wind_impulse_age_ticks"));
            assertNull(r.get("blocks_from"));assertTrue(r.containsKey("blocks_skipped"));assertEquals(26,w.moves.get());
            TelemetryRecord sample=records(w).stream().filter(x->Boolean.FALSE.equals(x.fields.get("accepted"))).findFirst().get();assertEquals(15d,((List<?>)sample.fields.get("pos")).get(0));
        }
    }
    @Test public void primitiveEndpointSnapshotsPreserveCorrectionsAndAcceptedHistoryAfterEventMutation() throws Exception {
        Player p=player(); TelemetryWriter w=writer(); TelemetryService s=new TelemetryService(w,"fox-eu-1",100,100,bots());
        IPlayerData pd=mock(IPlayerData.class);
        try(var folia=mockStatic(Folia.class);var data=mockStatic(DataManager.class)) {
            folia.when(()->Folia.isOwnedByCurrentRegion(p)).thenReturn(true); data.when(()->DataManager.getPlayerData(p)).thenReturn(pd);
            PlayerMoveEvent corrected=new PlayerMoveEvent(p,new Location(p.getWorld(),1,64,1),new Location(p.getWorld(),2,64,1));
            s.begin(corrected);corrected.getTo().setX(99);s.end(corrected);
            TelemetryRecord rejectedSample=records(w).peek();assertEquals(false,rejectedSample.fields.get("accepted"));
            assertEquals(1d,((List<?>)rejectedSample.fields.get("pos")).get(0));
            PlayerMoveEvent accepted=new PlayerMoveEvent(p,new Location(p.getWorld(),1,64,1),new Location(p.getWorld(),3,64,1));
            s.begin(accepted);s.end(accepted);accepted.getTo().setX(100);
            s.setback(p,"test-correction");
            var setback=records(w).stream().filter(r->r.fields.get("kind").equals("setback")).findFirst().get();
            List<?> history=(List<?>)setback.fields.get("history");assertEquals(1,history.size());
            assertEquals(0d,((List<?>)history.get(0)).get(1));assertEquals(0d,((List<?>)setback.fields.get("from")).get(0));
        }
    }
    @Test public void anotherPluginsMoveCancellationIsSampledButNeverAttributedToNcp() throws Exception {
        Player p=player();TelemetryWriter w=writer();TelemetryService s=new TelemetryService(w,"fox-eu-1",100,100,bots());
        PlayerMoveEvent e=new PlayerMoveEvent(p,new Location(p.getWorld(),1,64,1),new Location(p.getWorld(),2,64,1));
        try(var folia=mockStatic(Folia.class)) {
            folia.when(()->Folia.isOwnedByCurrentRegion(p)).thenReturn(true);s.begin(e);e.setCancelled(true);s.end(e);
        }
        assertEquals(1,records(w).size());TelemetryRecord r=records(w).peek();assertEquals("move",r.fields.get("kind"));assertEquals(false,r.fields.get("accepted"));
    }
    @Test public void vehicleScopesRetainVehicleEndpointsAndSuppressDuplicateGenericCorrection() throws Exception {
        Player p=player();TelemetryWriter w=writer();TelemetryService s=new TelemetryService(w,"fox-eu-1",0,100,bots());
        IPlayerData pd=mock(IPlayerData.class); Check check=mock(Check.class);when(check.getType()).thenReturn(CheckType.MOVING_VEHICLE_ENVELOPE);
        ViolationData vd=mock(ViolationData.class);TestClientNoFallSequences.field(vd,ViolationData.class,"check",check);TestClientNoFallSequences.field(vd,ViolationData.class,"player",p);
        when(vd.getTelemetryTags()).thenReturn("entity.BOAT+hdist");when(vd.getTelemetryFrom()).thenReturn(null);when(vd.getTelemetryTo()).thenReturn(null);
        try(var folia=mockStatic(Folia.class);var data=mockStatic(DataManager.class)) {
            folia.when(()->Folia.isOwnedByCurrentRegion(p)).thenReturn(true);data.when(()->DataManager.getPlayerData(p)).thenReturn(pd);
            s.beginVehicle(p,new Location(p.getWorld(),40,65,40),new Location(p.getWorld(),50,65,50));s.cancel(vd);s.setback(p,"vehicle-setback");s.endVehicle(p);
        }
        assertEquals(1,records(w).size());Map<String,Object> r=records(w).peek().fields;assertEquals("vehicle",r.get("movement_kind"));
        assertEquals(0d,((List<?>)r.get("from")).get(0));assertEquals(10d,((List<?>)r.get("to")).get(0));assertEquals(1,w.moves.get());
    }
    @Test public void inheritedTempbotAndMetaBotAreCombinedWithoutAssumingUnknownPlayersHuman() {
        UUID id=UUID.randomUUID();TelemetryService.BotCache group=new TelemetryService.BotCache(){
            @Override Boolean lookup(UUID u){return false;} @Override Boolean lookupTempbot(UUID u){return true;}
        };
        assertEquals(true,group.bot(id,1));assertEquals(false,group.metaBot(id,1));assertEquals(true,group.tempbot(id,1));
        TelemetryService.BotCache human=new TelemetryService.BotCache(){@Override Boolean lookup(UUID u){return false;}@Override Boolean lookupTempbot(UUID u){return false;}};
        assertEquals(false,human.bot(id,1));
    }
    @Test public void blockNeighbourhoodReadsExistingNodesOnlyAndLeavesMissingCellsUnknown() {
        var cache=mock(fr.neatmonster.nocheatplus.utilities.map.BlockCache.class);
        var node=mock(fr.neatmonster.nocheatplus.utilities.map.BlockCache.IBlockCacheNode.class);
        when(node.getType()).thenReturn(Material.OBSIDIAN);when(node.isBoundsFetched()).thenReturn(true);when(node.getBounds()).thenReturn(new double[]{0,0,0,1,1,1});
        when(cache.getBlockCacheNode(0,64,0)).thenReturn(node);
        Map<String,Object> snapshot=fr.neatmonster.nocheatplus.telemetry.CachedBlockSnapshot.capture(cache,0,64,0);
        assertEquals(List.of(-1,63,-1),snapshot.get("origin"));assertEquals(List.of(3,4,3),snapshot.get("size"));
        List<?> cells=(List<?>)snapshot.get("cells");assertEquals(36,cells.size());assertNull(cells.get(0));assertEquals("minecraft:obsidian",((List<?>)cells.get(13)).get(0));assertEquals(2,((List<?>)cells.get(13)).get(2));
        verify(cache,never()).fetchTypeId(anyInt(),anyInt(),anyInt());verify(cache,never()).fetchBounds(anyInt(),anyInt(),anyInt());verify(cache,never()).fetchData(anyInt(),anyInt(),anyInt());
        verify(cache,times(36)).getBlockCacheNode(anyInt(),anyInt(),anyInt());
        assertNull(fr.neatmonster.nocheatplus.telemetry.CachedBlockSnapshot.capture(mock(fr.neatmonster.nocheatplus.utilities.map.BlockCache.class),0,64,0));
    }
    @Test public void ownerGuardNeverReadsPlayerStateFromOtherRegion() throws Exception {
        Player p=mock(Player.class); TelemetryWriter w=writer();TelemetryService s=new TelemetryService(w,"fox-eu-1",100,100,bots());
        PlayerMoveEvent event=new PlayerMoveEvent(p,new Location(null,1,1,1),new Location(null,2,1,1));
        try(var folia=mockStatic(Folia.class)) {folia.when(()->Folia.isOwnedByCurrentRegion(p)).thenReturn(false);s.begin(event);s.end(event);s.setback(p,"vehicle-setback");}
        verify(p,never()).getLocation();verify(p,never()).getInventory();verify(p,never()).getUniqueId();assertEquals(0,w.queueSize());assertTrue(w.captureErrors.get()>0);
    }
    @Test public void botLookupIsCachedAndUnavailableBotStateIsNullRatherThanFalse() {
        int[] lookups={0};UUID uuid=UUID.randomUUID();TelemetryService.BotCache cache=new TelemetryService.BotCache(){@Override Boolean lookup(UUID id){lookups[0]++;return true;}};
        assertEquals(true,cache.bot(uuid,1));assertEquals(true,cache.bot(uuid,29999));assertEquals(1,lookups[0]);assertEquals(true,cache.bot(uuid,30001));assertEquals(2,lookups[0]);
        cache.remove(uuid);cache.bot(uuid,30002);assertEquals(3,lookups[0]);
        assertNull(new TelemetryService.BotCache().bot(uuid,1));
    }
    @Test public void observerFailureCannotEscapeIntoChecksAndDefaultOffCreatesNoFolder() throws Exception {
        Telemetry.install(new Telemetry.Sink(){public void begin(PlayerMoveEvent e){throw new AssertionError();}public void end(PlayerMoveEvent e){throw new RuntimeException();}public void cancel(ViolationData d){throw new AssertionError();}public void setback(Player p,String reason){throw new AssertionError();}});
        try {Telemetry.begin(null);Telemetry.end(null);Telemetry.setback(null,"test");ViolationData vd=mock(ViolationData.class);when(vd.willCancel()).thenReturn(true);Telemetry.cancel(vd);}
        finally {Telemetry.install(null);}
        assertFalse(Telemetry.enabled());
        fr.neatmonster.nocheatplus.config.ConfigFile config=new fr.neatmonster.nocheatplus.config.ConfigFile();assertNull(TelemetryService.start(null,config));
    }
    @Test public void workerIdentityUsesRuntimeNameAndRejectsMismatchedEnvAndUnsafeFilenames() {
        assertEquals("eu-west", TelemetryService.validateWorkerIdentity("eu-west", null));
        assertEquals("eu-west", TelemetryService.validateWorkerIdentity("eu-west", "eu-west"));
        for (String name : new String[]{null,"", "../eu", "worker/name"}) {
            try { TelemetryService.validateWorkerIdentity(name, null); fail(); } catch (IllegalArgumentException expected) {}
        }
        try { TelemetryService.validateWorkerIdentity("eu-west", "node-east"); fail(); } catch (IllegalArgumentException expected) {}
    }
    @Test public void tagCollectionFailureIsIsolatedBeforeActionExecution() {
        int[] errors={0};Telemetry.install(new Telemetry.Sink(){
            public void begin(PlayerMoveEvent e){} public void end(PlayerMoveEvent e){} public void cancel(ViolationData d){}public void setback(Player p,String r){}
            @Override public void error(){errors[0]++;}
        });
        ViolationData vd=mock(ViolationData.class);
        try {
            java.util.Collection<String> tags=new java.util.AbstractCollection<String>(){
                public int size(){return 1;} public java.util.Iterator<String> iterator(){throw new java.util.ConcurrentModificationException();}
            };
            Telemetry.tags(vd,tags);assertEquals(1,errors[0]);verify(vd,never()).setTelemetryTags(anyString());
        } finally {Telemetry.install(null);}
    }
    @Test public void missingFoxIdentityFailsClosedInsteadOfUsingHostOrNodeName() {
        try {TelemetryService.workerIdentity();fail("No Fox runtime in unit test");}catch(ReflectiveOperationException expected){}
    }
}
