package fr.neatmonster.nocheatplus.telemetry;

import static org.junit.Assert.*;
import java.io.OutputStream;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;
import org.junit.Test;

public class TestTelemetryWriter {
    private TelemetryWriter writer(Path path, int capacity) {
        return new TelemetryWriter(path, "fox-eu-1", capacity, 0, Long.MAX_VALUE, () -> Long.MAX_VALUE, s -> {});
    }
    private TelemetryRecord record(long time) { return new TelemetryRecord(time, Map.of("time", time, "kind", "move", "worker", "fox-eu-1")); }
    @Test public void recordIsDeeplyImmutableAndJsonEscapesNamesAndNonfiniteNumbers() {
        List<Object> mutable = new ArrayList<>(Arrays.asList("name\"\n\\", Double.NaN, "😀"));
        Map<String, Object> fields = new LinkedHashMap<>(); fields.put("history", mutable);
        TelemetryRecord r = new TelemetryRecord(1, fields); mutable.clear(); fields.clear();
        assertEquals("{\"history\":[\"name\\\"\\n\\\\\",null,\"\\ud83d\\ude00\"]}", r.json());
        try { r.fields.put("bad", "change"); fail(); } catch (UnsupportedOperationException expected) {}
        try { new TelemetryRecord(1, Map.of("world", new Object())); fail(); } catch (IllegalArgumentException expected) {}
    }
    @Test(timeout = 5000) public void fullQueueDropsWithoutBlockingAndCountersRemainExactUnderContention() throws Exception {
        TelemetryWriter w = writer(Files.createTempDirectory("ncp-drop").toRealPath(), 17); TelemetryRecord r = record(1);
        ExecutorService executor = Executors.newFixedThreadPool(8); List<Future<?>> work = new ArrayList<>();
        long started = System.nanoTime();
        try {
            for (int p = 0; p < 8; p++) work.add(executor.submit(() -> {
                for (int i = 0; i < 10000; i++) { w.moves.incrementAndGet(); w.countSetback("moving.survivalfly"); w.offer(r); }
            }));
            for (Future<?> f : work) f.get(4, TimeUnit.SECONDS);
        } finally { executor.shutdownNow(); }
        assertEquals(17, w.queueSize()); assertEquals(80000 - 17, w.drops.get()); assertEquals(80000, w.moves.get());
        System.out.println("BENCH full-queue parallel 80000 offers ns/op=" + (System.nanoTime() - started) / 80000);
    }
    @Test public void samplingStickyWithinHourAndUnbiasedAcrossUUIDsAndWorkers() {
        int count = 0, changed = 0; long hour = 1777777200000L / 3600000 * 3600000;
        for (int i = 0; i < 100000; i++) {
            UUID id = new UUID(i * 7123456789L, i);
            boolean a = TelemetryRecord.sampled(id, hour, 1);
            assertEquals(a, TelemetryRecord.sampled(id, hour + 3599999, 1));
            assertFalse(TelemetryRecord.sampled(id, hour, 0)); assertTrue(TelemetryRecord.sampled(id, hour, 100));
            if (a) count++; if (a != TelemetryRecord.sampled(id, hour + 3600000, 1)) changed++;
        }
        assertTrue("sample count " + count, count > 850 && count < 1150); assertTrue(changed > 1500);
    }
    @Test public void rotatesUtcHourGzipsOnlyOwnFileAndPreservesRestartedHourSegments() throws Exception {
        Path folder = Files.createTempDirectory("ncp-rotation").toRealPath(); Path unrelated = folder.resolve("other-worker-20260101-00.jsonl");
        Files.writeString(unrelated, "untouched");
        long now = java.time.Instant.parse("2026-01-01T00:59:59Z").toEpochMilli();
        TelemetryWriter w = writer(folder, 4); w.write(record(1), now); w.write(record(2), now + 1000); w.close();
        Path gz = folder.resolve("fox-eu-1-20260101-00.jsonl.gz");
        try (GZIPInputStream in = new GZIPInputStream(Files.newInputStream(gz))) { assertEquals(record(1).json()+"\n", new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)); }
        assertFalse(Files.exists(folder.resolve("fox-eu-1-20260101-00.jsonl"))); assertEquals("untouched", Files.readString(unrelated));
        w = writer(folder, 4); w.write(record(3), now); w.close();
        try (GZIPInputStream in = new GZIPInputStream(Files.newInputStream(gz))) { assertEquals(record(1).json()+"\n"+record(3).json()+"\n", new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)); }
    }
    @Test public void diskGuardLogsOncePerPauseAndAutomaticallyResumesForBothThresholds() throws Exception {
        Path folder = Files.createTempDirectory("ncp-guard").toRealPath(); AtomicLong free = new AtomicLong(10); List<String> logs = new ArrayList<>();
        TelemetryWriter w = new TelemetryWriter(folder, "worker", 10, 20, 50, free::get, logs::add);
        assertFalse(w.diskAllowed(0)); assertFalse(w.diskAllowed(1)); assertEquals(1, logs.size());
        free.set(20); assertTrue(w.diskAllowed(2)); assertEquals(2, logs.size());
        Path file = folder.resolve("foreign.jsonl"); Files.write(file, new byte[50]); assertFalse(w.diskAllowed(60_000_000_000L)); assertFalse(w.diskAllowed(60_000_000_001L)); assertEquals(3, logs.size());
        Files.delete(file); assertTrue(w.diskAllowed(120_000_000_000L)); assertEquals(4, logs.size());
    }
    @Test public void hardCapRejectsOversizedRecordsAndExternalChangesWaitForMinuteRescan() throws Exception {
        Path folder = Files.createTempDirectory("ncp-size").toRealPath();
        TelemetryWriter w = new TelemetryWriter(folder,"fox-eu-1",10,0,100,()->Long.MAX_VALUE,s->{});
        assertTrue(w.diskAllowed(0));
        w.write(record(1),1);
        TelemetryRecord large = new TelemetryRecord(1,Map.of("payload","x".repeat(20000)));
        w.write(large,1);
        assertEquals("Oversized record is counted as a drop", 1, w.drops.get());
        try (var files = Files.list(folder)) {
            Path current = files.filter(p -> p.toString().endsWith(".jsonl")).findFirst().get();
            assertEquals(record(1).json()+System.lineSeparator(), Files.readString(current));
        }
        w.close();
        TelemetryWriter external = new TelemetryWriter(folder,"worker",10,0,1000,()->Long.MAX_VALUE,s->{});
        assertTrue(external.diskAllowed(0));
        Path added = folder.resolve("external"); Files.write(added,new byte[1000]);
        assertTrue(external.diskAllowed(59_999_999_999L));
        assertFalse(external.diskAllowed(60_000_000_000L));
        Files.delete(added);
        assertFalse(external.diskAllowed(119_999_999_999L));
        assertTrue(external.diskAllowed(120_000_000_000L));
    }
    @Test public void hardCapIncludesUtf8NewlineAndAppliesWithinBatchAndShutdown() throws Exception {
        Path folder = Files.createTempDirectory("ncp-hard-cap").toRealPath();
        TelemetryRecord r = new TelemetryRecord(1, Map.of("payload", "ą😀"));
        int bytes = (r.json()+System.lineSeparator()).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        TelemetryWriter w = new TelemetryWriter(folder, "worker", 300, 0, bytes * 2, () -> Long.MAX_VALUE, s -> {});
        for (int i=0; i<256; i++) assertTrue(w.offer(r));
        w.start(); w.close();
        assertEquals(bytes * 2, Files.size(folder.resolve("worker-" + java.time.format.DateTimeFormatter
                .ofPattern("yyyyMMdd-HH").withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.now()) + ".jsonl")));
        assertFalse(w.diskAllowed());
        var count = TelemetryWriter.class.getDeclaredField("folderBytes"); count.setAccessible(true);
        assertEquals(bytes * 2, count.getLong(w));
        assertTrue("Unwritten records must be counted as drops or remain queued", w.drops.get()+w.queueSize() >= 254);
    }
    @Test public void partialFailedWriteCountsActualBytesWithoutMinuteRescanOrReplay() throws Exception {
        Path folder = Files.createTempDirectory("ncp-partial-write").toRealPath();
        TelemetryWriter w = new TelemetryWriter(folder, "fox-eu-1", 10, 0, 500, () -> Long.MAX_VALUE, s -> {});
        assertTrue(w.diskAllowed(0)); w.write(record(1), 1);
        var outputField = TelemetryWriter.class.getDeclaredField("output"); outputField.setAccessible(true);
        var currentField = TelemetryWriter.class.getDeclaredField("current"); currentField.setAccessible(true);
        ((OutputStream) outputField.get(w)).close();
        Path current = (Path) currentField.get(w); long initialBytes = Files.size(current);
        AtomicLong attempts = new AtomicLong();
        outputField.set(w, new OutputStream() {
            @Override public void write(int value) { throw new AssertionError("Expected byte array write"); }
            @Override public void write(byte[] bytes) throws IOException {
                attempts.incrementAndGet(); Files.write(current, Arrays.copyOf(bytes, 128), StandardOpenOption.APPEND);
                throw new IOException("Injected partial write");
            }
        });
        try {
            try { w.write(new TelemetryRecord(1, Map.of("payload", "x".repeat(400))), 1); fail(); }
            catch (IOException expected) { assertEquals("Injected partial write", expected.getMessage()); }
            assertEquals(initialBytes + 128, Files.size(current));
            var count = TelemetryWriter.class.getDeclaredField("folderBytes"); count.setAccessible(true);
            assertEquals("Only actual partial bytes are charged", Files.size(current), count.getLong(w));
            assertTrue(w.diskAllowed(1)); assertTrue(w.diskAllowed(60_000_000_000L));
            assertEquals("Guards and minute scans must never replay failed output", 1, attempts.get());
        } finally { w.close(); }
        assertEquals("Close must not replay failed output", 1, attempts.get());
    }
    @Test(timeout = 10000) public void realLoopRetriesDoNotGrowOverCapFileAndResumeAfterSpaceIsFreed() throws Exception {
        Path folder = Files.createTempDirectory("ncp-partial-retry").toRealPath();
        AtomicLong guards = new AtomicLong(), attempts = new AtomicLong();
        List<String> logs = new CopyOnWriteArrayList<>();
        CountDownLatch failed = new CountDownLatch(1);
        TelemetryWriter w = new TelemetryWriter(folder, "fox-eu-1", 10, 0, 500, () -> {
            guards.incrementAndGet(); return Long.MAX_VALUE;
        }, logs::add);
        assertTrue(w.diskAllowed()); w.write(record(1), System.currentTimeMillis());
        var outputField = TelemetryWriter.class.getDeclaredField("output"); outputField.setAccessible(true);
        var currentField = TelemetryWriter.class.getDeclaredField("current"); currentField.setAccessible(true);
        ((OutputStream) outputField.get(w)).close(); Path current = (Path) currentField.get(w);
        var scan = TelemetryWriter.class.getDeclaredField("lastFolderScan"); scan.setAccessible(true);
        long initialScan = scan.getLong(w);
        outputField.set(w, new OutputStream() {
            @Override public void write(int value) { throw new AssertionError("Expected byte array write"); }
            @Override public void write(byte[] bytes) throws IOException {
                attempts.incrementAndGet(); Files.write(current, Arrays.copyOf(bytes, 128), StandardOpenOption.APPEND);
                // Model concurrent external growth while the underlying write fails.
                Files.write(current, new byte[500], StandardOpenOption.APPEND);
                failed.countDown(); throw new IOException("Injected partial write");
            }
        });
        w.offer(new TelemetryRecord(1, Map.of("payload", "x".repeat(400))));
        w.offer(record(2)); w.offer(record(3));
        w.start();
        try {
            assertTrue(failed.await(2, TimeUnit.SECONDS));
            await(() -> logs.stream().anyMatch(s -> s.startsWith("Telemetry IO paused:")));
            long overCapBytes = Files.size(current), retryStart = guards.get();
            assertTrue(overCapBytes > 500);
            await(() -> guards.get() >= retryStart + 3);
            assertEquals("Retries must retain all subsequent queued records", 2, w.queueSize());
            assertEquals("Already-over-cap file must stop growing", overCapBytes, Files.size(current));
            assertEquals(1, attempts.get()); assertEquals(1, w.drops.get());
            assertEquals("IO failure must not trigger another directory scan", initialScan, scan.getLong(w));
            assertEquals(1, logs.stream().filter(s -> s.startsWith("Telemetry paused:")).count());
            synchronized (w) {
                Files.delete(current);
                // Advance the normal minute-rescan deadline without waiting a minute.
                scan.setLong(w, System.nanoTime() - 60_000_000_000L);
            }
            await(() -> w.queueSize() == 0);
            await(() -> {
                try { return Files.readString(current).contains(record(3).json()); }
                catch (IOException e) { return false; }
            });
            assertTrue(Files.readString(current).contains(record(2).json()));
            assertTrue(Files.readString(current).contains(record(3).json()));
            assertTrue(logs.stream().anyMatch(s -> s.startsWith("Telemetry resumed:")));
            assertEquals("Recovery opens fresh output rather than replaying failed bytes", 1, attempts.get());
        } finally { w.close(); }
        assertTrue("Shutdown must also respect the cap", Files.size(current) <= 500);
        assertEquals(1, attempts.get());
    }
    private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue("Timed out waiting for writer", condition.getAsBoolean());
    }
    @Test public void runningBytesMatchUtf8WritesAndGzipReplacementIncludingRestartSegments() throws Exception {
        Path folder=Files.createTempDirectory("ncp-accounting").toRealPath();
        long now=java.time.Instant.parse("2026-01-01T00:59:59Z").toEpochMilli();
        var bytes=TelemetryWriter.class.getDeclaredField("folderBytes");bytes.setAccessible(true);
        TelemetryRecord record=new TelemetryRecord(1,Map.of("name","ą"));
        for(int restart=0;restart<2;restart++){
            TelemetryWriter w=writer(folder,10);assertTrue(w.diskAllowed(0));long initial=bytes.getLong(w);
            w.write(record,now);
            assertEquals(initial+record.json().getBytes(java.nio.charset.StandardCharsets.UTF_8).length+System.lineSeparator().length(),bytes.getLong(w));
            w.write(record,now+1000);w.close();
            try(var files=Files.list(folder)){
                long actual=files.mapToLong(p->{try{return Files.size(p);}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}}).sum();
                assertEquals(actual,bytes.getLong(w));
            }
        }
    }
    @Test public void startupRecoversAllOwnClosedHoursAndRecoveryRunsOnlyHourly() throws Exception {
        Path folder = Files.createTempDirectory("ncp-recovery").toRealPath();
        long now = java.time.Instant.parse("2026-01-01T03:00:00Z").toEpochMilli();
        for (int i=0;i<3;i++) Files.writeString(folder.resolve("fox-eu-1-20260101-0"+i+".jsonl"),"record\n");
        Path other=folder.resolve("other-20260101-00.jsonl"); Files.writeString(other,"untouched");
        var recovery=TelemetryWriter.class.getDeclaredMethod("recoverClosedHour",long.class); recovery.setAccessible(true);
        TelemetryWriter w=writer(folder,10); assertTrue(w.diskAllowed()); recovery.invoke(w,now);
        for(int i=0;i<3;i++) assertTrue(Files.exists(folder.resolve("fox-eu-1-20260101-0"+i+".jsonl.gz")));
        Path late=folder.resolve("fox-eu-1-20251231-23.jsonl"); Files.writeString(late,"late\n");
        recovery.invoke(w,now+1000); assertTrue(Files.exists(late));
        recovery.invoke(w,now+3600000); assertFalse(Files.exists(late));
        assertEquals("untouched",Files.readString(other));
    }
    @Test public void refusesUnsafeWorkerNamesAndSymlinkTargets() throws Exception {
        Path folder = Files.createTempDirectory("ncp-path").toRealPath();
        try { new TelemetryWriter(folder, "../other", 1, 0, 100, () -> 100L, s -> {}); fail(); } catch (IllegalArgumentException expected) {}
        Path victim = folder.resolve("victim"); Files.writeString(victim,"unchanged"); Files.createSymbolicLink(folder.resolve("worker-19700101-00.jsonl"), victim);
        TelemetryWriter w = new TelemetryWriter(folder, "worker",1,0,100,()->100L,s->{});
        try { w.write(record(1), 1); fail(); } catch (java.io.IOException expected) {}
        assertEquals("unchanged", Files.readString(victim));
    }
    @Test public void singleWriterDrainsQueueAndWritesUnsampledCountersEvenWhenQueueFull() throws Exception {
        Path folder = Files.createTempDirectory("ncp-drain").toRealPath(); TelemetryWriter w = writer(folder,2);
        w.moves.set(1000000); for (int i = 0; i < 99; i++) w.countSetback("moving.nofall");
        w.offer(record(1)); w.offer(record(2)); assertFalse(w.offer(record(3))); w.start(); w.close();
        String all; try (java.util.stream.Stream<Path> paths=Files.list(folder)) {
            Path json = paths.filter(p -> p.toString().endsWith(".jsonl")).findFirst().get(); all=Files.readString(json);
        }
        assertTrue(all.contains("\"moves_total\":1000000")); assertTrue(all.contains("\"moving.nofall\":99")); assertTrue(all.contains("\"drops_total\":1"));
        assertEquals(0,w.queueSize());
    }
}
