package fr.neatmonster.nocheatplus.telemetry;

import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.zip.GZIPOutputStream;
import java.util.regex.Pattern;

/** One writer owns all IO and serialization. Producers use a bounded lock-free queue. */
public final class TelemetryWriter implements AutoCloseable {
    public final String session = UUID.randomUUID().toString();
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyyMMdd-HH", Locale.ROOT).withZone(ZoneOffset.UTC);
    private final ConcurrentLinkedQueue<TelemetryRecord> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queued = new AtomicInteger();
    public final AtomicLong drops = new AtomicLong(), captureErrors = new AtomicLong(), moves = new AtomicLong();
    private final Map<String, AtomicLong> setbacks = new java.util.concurrent.ConcurrentHashMap<>();
    private final int capacity;
    private final Path folder;
    private final String worker;
    private final long minFree, maxFolder;
    private final LongSupplier freeSpace;
    private final Consumer<String> log;
    private final Pattern closedHourPattern;
    private FileStore fileStore;
    private long folderBytes, lastFolderScan;
    private boolean diskInitialized;
    private String recoveredHour;
    private static final long RESCAN_NANOS = 60_000_000_000L;
    private OutputStream output;
    private long outputBytes;
    private Path current;
    private String hour;
    private Path pendingCompression;
    private boolean paused;
    private volatile boolean running;
    private Thread thread;
    private FileChannel lockChannel;
    private FileLock lock;
    private long lastCounters, previousMoves, previousDrops, previousErrors;
    private final Map<String, Long> previousSetbacks = new HashMap<>();

    public TelemetryWriter(Path folder, String worker, int capacity, long minFree, long maxFolder,
                           LongSupplier freeSpace, Consumer<String> log) {
        if (!worker.matches("[A-Za-z0-9_-]{1,100}") || capacity < 1 || minFree < 0 || maxFolder < 1) throw new IllegalArgumentException("Invalid telemetry settings");
        this.folder = folder.toAbsolutePath().normalize(); this.worker = worker; this.capacity = capacity;
        this.minFree = minFree; this.maxFolder = maxFolder; this.freeSpace = freeSpace; this.log = log;
        this.closedHourPattern = Pattern.compile(Pattern.quote(worker) + "-\\d{8}-\\d{2}\\.jsonl");
    }
    public boolean offer(TelemetryRecord record) {
        int size;
        do { size = queued.get(); if (size >= capacity) { drops.incrementAndGet(); return false; } }
        while (!queued.compareAndSet(size, size + 1));
        queue.add(record); return true;
    }
    public void countSetback(String check) { setbacks.computeIfAbsent(check, key -> new AtomicLong()).incrementAndGet(); }
    public int queueSize() { return queued.get(); }
    public synchronized void start() throws IOException {
        prepareFolder();
        Path lockPath = folder.resolve(worker + ".lock"); rejectSymlink(lockPath);
        lockChannel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try { lock = lockChannel.tryLock(); } catch (IOException | RuntimeException e) { lockChannel.close(); throw e; }
        if (lock == null) { lockChannel.close(); throw new IOException("Worker telemetry already has a writer"); }
        running = true; lastCounters = System.currentTimeMillis();
        thread = new Thread(this::run, "NCP-telemetry-" + worker); thread.setDaemon(true); thread.start();
    }
    private void prepareFolder() throws IOException {
        for (Path p = folder; p != null; p = p.getParent()) rejectSymlink(p);
        Files.createDirectories(folder);
    }
    private static void rejectSymlink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("Telemetry refuses symlink: " + path);
    }
    public synchronized boolean diskAllowed() throws IOException { return diskAllowed(System.nanoTime()); }
    // Injectable monotonic time for deterministic rescan tests; production uses nanoTime.
    synchronized boolean diskAllowed(long now) throws IOException {
        if (!diskInitialized) {
            prepareFolder(); fileStore = Files.getFileStore(folder);
        }
        if (!diskInitialized || now - lastFolderScan >= RESCAN_NANOS) {
            folderBytes = scanFolderBytes(); lastFolderScan = now; diskInitialized = true;
            if (output != null) outputBytes = Files.size(current);
        }
        boolean allow = (freeSpace == null ? fileStore.getUsableSpace() : freeSpace.getAsLong()) >= minFree && folderBytes < maxFolder;
        if (!allow && !paused) log.accept("Telemetry paused: disk free-space/folder guard");
        if (allow && paused) log.accept("Telemetry resumed: disk guard cleared");
        paused = !allow; return allow;
    }
    private long scanFolderBytes() throws IOException {
        try (java.util.stream.Stream<Path> files = Files.walk(folder)) {
            return files.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).mapToLong(p -> {
                try { return Files.size(p); } catch (IOException e) { throw new UncheckedIOException(e); }
            }).sum();
        } catch (UncheckedIOException e) { throw e.getCause(); }
    }
    private void run() {
        boolean ioWarned = false;
        try {
            while (running) {
                try {
                    if (!diskAllowed()) { closeOutput(false); Thread.sleep(1000); continue; }
                    long now = System.currentTimeMillis();
                    recoverClosedHour(now);
                    if (now - lastCounters >= 60000) counters(now);
                    int batch = 0;
                    TelemetryRecord record;
                    while (batch++ < 256 && (record = queue.poll()) != null) {
                        queued.decrementAndGet();
                        try { write(record, System.currentTimeMillis()); } catch (IOException e) { drops.incrementAndGet(); throw e; }
                    }
                    ioWarned = false;
                    if (batch == 1) Thread.sleep(100);
                } catch (IOException | RuntimeException e) {
                    try { closeOutput(false); } catch (IOException close) { e.addSuppressed(close); }
                    if (!ioWarned) log.accept("Telemetry IO paused: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                    ioWarned = true; Thread.sleep(1000);
                }
            }
            // Shutdown remains bounded even if a saturated queue contains large snapshots.
            long deadline = System.nanoTime() + 2_000_000_000L;
            if (diskAllowed()) {
                TelemetryRecord record;
                while (System.nanoTime() < deadline && (record = queue.poll()) != null) {
                    queued.decrementAndGet(); write(record, System.currentTimeMillis());
                }
                int remaining = queued.get(); drops.addAndGet(remaining);
                counters(System.currentTimeMillis());
            }
        } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
          catch (IOException | RuntimeException e) { log.accept("Telemetry shutdown IO: " + e.getMessage()); }
        finally {
            try { closeOutput(false); } catch (IOException e) { log.accept("Telemetry close IO: " + e.getMessage()); }
            try { if (lock != null) lock.release(); if (lockChannel != null) lockChannel.close(); }
            catch (IOException e) { log.accept("Telemetry lock close: " + e.getMessage()); }
        }
    }
    // Package-visible for deterministic rotation and throughput tests. Writer thread only in production.
    synchronized void write(TelemetryRecord record, long now) throws IOException {
        if (!diskInitialized && !diskAllowed()) { drops.incrementAndGet(); return; }
        String next = HOUR.format(Instant.ofEpochMilli(now));
        if (!next.equals(hour) || output == null) {
            closeOutput(true);
            if (pendingCompression != null) { gzip(pendingCompression); pendingCompression = null; }
            prepareFolder(); hour = next; current = folder.resolve(worker + "-" + hour + ".jsonl"); rejectSymlink(current);
            outputBytes = sizeIfPresent(current);
            output = Files.newOutputStream(current, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        byte[] bytes = (record.json() + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxFolder - folderBytes) { drops.incrementAndGet(); return; }
        // Reserve before IO; successful writes are exact and failures retain a conservative
        // charge if the actual file size cannot be read. No buffered bytes can be replayed.
        folderBytes += bytes.length;
        try { output.write(bytes); outputBytes += bytes.length; }
        catch (IOException | RuntimeException e) {
            try {
                long actual = Files.size(current);
                folderBytes += actual - outputBytes - bytes.length; outputBytes = actual;
            } catch (IOException size) { e.addSuppressed(size); }
            throw e;
        }
    }
    private synchronized void closeOutput(boolean compress) throws IOException {
        if (output == null) return;
        OutputStream closing = output; output = null; closing.close();
        if (compress) {
            pendingCompression = current;
            gzip(current); pendingCompression = null;
        }
    }
    private synchronized void recoverClosedHour(long now) throws IOException {
        String active = HOUR.format(Instant.ofEpochMilli(now));
        if (active.equals(recoveredHour)) return;
        prepareFolder();
        // Startup and hour changes only; recover all closed segments in a single listing.
        List<Path> old;
        try (java.util.stream.Stream<Path> files = Files.list(folder)) {
            old = files.filter(p -> closedHourPattern.matcher(p.getFileName().toString()).matches())
                .filter(p -> !p.equals(current) || output == null)
                .filter(p -> p.getFileName().toString().substring(worker.length() + 1, worker.length() + 12).compareTo(active) < 0)
                .sorted().collect(java.util.stream.Collectors.toList());
        }
        for (Path path : old) { gzip(path); if (path.equals(pendingCompression)) pendingCompression = null; }
        recoveredHour = active;
    }
    private void gzip(Path source) throws IOException {
        // Only a file opened by this writer is eligible; never sweep/delete other workers' files.
        Path gzip = source.resolveSibling(source.getFileName() + ".gz");
        Path tmp = source.resolveSibling(source.getFileName() + ".gz.tmp");
        rejectSymlink(source); rejectSymlink(gzip); rejectSymlink(tmp);
        long sourceBytes = Files.size(source), oldGzipBytes = Files.exists(gzip) ? Files.size(gzip) : 0;
        long oldTmpBytes = Files.exists(tmp) ? Files.size(tmp) : 0;
        try {
            try (OutputStream raw = Files.newOutputStream(tmp)) {
                // Preserve an earlier compressed segment when this worker restarted in the same hour.
                if (Files.exists(gzip)) Files.copy(gzip, raw);
                try (GZIPOutputStream zipped = new GZIPOutputStream(raw)) { Files.copy(source, zipped); }
            }
            Files.move(tmp, gzip, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            Files.delete(source);
        } finally {
            // Include partial temporary files after IO errors until recovery or the next rescan.
            folderBytes += sizeIfPresent(source) + sizeIfPresent(gzip) + sizeIfPresent(tmp)
                - sourceBytes - oldGzipBytes - oldTmpBytes;
        }
    }
    private static long sizeIfPresent(Path path) throws IOException { return Files.exists(path) ? Files.size(path) : 0; }
    private void counters(long now) throws IOException {
        long moveTotal = moves.get(), dropTotal = drops.get(), errorTotal = captureErrors.get();
        Map<String, Object> totals = new TreeMap<>(), delta = new TreeMap<>();
        for (Map.Entry<String, AtomicLong> e : setbacks.entrySet()) {
            long value = e.getValue().get(); totals.put(e.getKey(), value);
            delta.put(e.getKey(), value - previousSetbacks.getOrDefault(e.getKey(), 0L));
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("v", 1); fields.put("kind", "counter"); fields.put("time", now); fields.put("worker", worker); fields.put("session", session);
        fields.put("interval_start", lastCounters); fields.put("moves", moveTotal - previousMoves); fields.put("moves_total", moveTotal);
        fields.put("setbacks", delta); fields.put("setbacks_total", totals);
        fields.put("drops", dropTotal - previousDrops); fields.put("drops_total", dropTotal);
        fields.put("capture_errors", errorTotal - previousErrors); fields.put("capture_errors_total", errorTotal); fields.put("queue_depth", queued.get());
        write(new TelemetryRecord(now, fields), now);
        for (Map.Entry<String, Object> e : totals.entrySet()) previousSetbacks.put(e.getKey(), (Long) e.getValue());
        previousMoves = moveTotal; previousDrops = dropTotal; previousErrors = errorTotal; lastCounters = now;
    }
    @Override public void close() {
        running = false;
        if (thread != null) try { thread.join(4000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        else try { closeOutput(true); } catch (IOException e) { throw new UncheckedIOException(e); }
    }
}
