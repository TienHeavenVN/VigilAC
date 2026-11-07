package ac.vigil.capture;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Single dedicated writer thread draining the buffer into daily UTC JSONL files:
 * {@code <outputDir>/capture-YYYYMMDD.jsonl}.
 *
 * <p>Disk discipline:</p>
 * <ul>
 *   <li>{@code maxTotalBytes} is a hard cap on the whole corpus directory; when the
 *       next row would exceed it, the writer HALTS (fail safe), future rows are dropped
 *       and counted, and the halt is visible in status until an admin raises the cap
 *       or purges. Quota accounting is recomputed from disk on startup, so a crash
 *       never resets the counter.</li>
 *   <li>Rows are flushed line-by-line: a crash can at worst truncate the in-flight
 *       line, which the Stage 3 validator quarantines by rejection count.</li>
 *   <li>{@link #purgeOlderThan(int)} deletes day-files older than the retention
 *       window (based on the date embedded in the file NAME, not mtime).</li>
 *   <li>{@link #deleteAll()} stops the writer, deletes every corpus file and resets
 *       accounting. Never throws on the hot path; IO errors surface as halt reasons.</li>
 * </ul>
 */
public final class CaptureWriter {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
    private static final Pattern DAY_FILE = Pattern.compile("capture-(\\d{8})\\.jsonl");
    private static final int DRAIN_POLL_MILLIS = 50;

    private final Path outputDir;
    private final long maxTotalBytes;
    private final CaptureBuffer buffer;
    private final LongSupplier clockMillis;
    private final List<String> ioProblems = new ArrayList<>();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread writerThread;
    private volatile BufferedWriter currentWriter;
    private volatile String currentDay;
    private long bytesWritten;
    private long rowsWritten;
    private long quotaDropped;
    private volatile String haltedReason; // null while healthy

    public CaptureWriter(Path outputDir, long maxTotalBytes, CaptureBuffer buffer, LongSupplier clockMillis) {
        this.outputDir = outputDir;
        this.maxTotalBytes = maxTotalBytes;
        this.buffer = buffer;
        this.clockMillis = clockMillis;
    }

    /** Recomputes quota accounting from disk; called once before {@link #start()}. */
    public void scanExistingFiles() {
        bytesWritten = 0L;
        try {
            if (!Files.isDirectory(outputDir)) {
                return;
            }
            try (Stream<Path> files = Files.list(outputDir)) {
                for (Path file : files.filter(f -> f.getFileName().toString().matches("capture-\\d{8}\\.jsonl")).toList()) {
                    bytesWritten += Files.size(file);
                }
            }
        } catch (IOException e) {
            ioProblems.add("could not scan corpus directory " + outputDir + ": " + e);
        }
        if (bytesWritten > maxTotalBytes) {
            haltedReason = "corpus already exceeds max-total-bytes on startup (" + bytesWritten + " > " + maxTotalBytes + ")";
        }
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        if (haltedReason == null) {
            scanExistingFiles();
        }
        writerThread = new Thread(this::writerLoop, "VigilAC-CaptureWriter");
        writerThread.setDaemon(true);
        writerThread.start();
    }

    /** Stops the writer; drains remaining rows (bounded), flushes, closes. Idempotent. */
    public void close() {
        running.set(false);
        Thread thread = writerThread;
        if (thread != null) {
            try {
                thread.join(5_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (thread.isAlive()) {
                thread.interrupt();
            }
            writerThread = null;
        }
        closeCurrent();
    }

    private void writerLoop() {
        while (running.get() || buffer.size() > 0) {
            CaptureRecord record = buffer.poll();
            if (record == null) {
                try {
                    Thread.sleep(DRAIN_POLL_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                continue;
            }
            writeRow(record);
        }
    }

    private void writeRow(CaptureRecord record) {
        if (haltedReason != null) {
            quotaDropped++;
            return;
        }
        String line = record.toJsonLine();
        long lineBytes = StandardCharsets.UTF_8.encode(line + "\n").remaining();
        if (bytesWritten + lineBytes > maxTotalBytes) {
            haltedReason = "max-total-bytes reached (" + bytesWritten + " + " + lineBytes + " > " + maxTotalBytes + ")";
            quotaDropped++;
            return;
        }
        try {
            BufferedWriter writer = writerFor(DAY.format(Instant.ofEpochMilli(clockMillis.getAsLong())));
            writer.write(line);
            writer.newLine();
            writer.flush();
            bytesWritten += lineBytes;
            rowsWritten++;
        } catch (IOException e) {
            haltedReason = "write failed: " + e;
            ioProblems.add(String.valueOf(e));
        }
    }

    private BufferedWriter writerFor(String day) throws IOException {
        if (day.equals(currentDay) && currentWriter != null) {
            return currentWriter;
        }
        closeCurrent();
        Files.createDirectories(outputDir);
        Path file = outputDir.resolve("capture-" + day + ".jsonl");
        currentWriter = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        currentDay = day;
        return currentWriter;
    }

    private void closeCurrent() {
        BufferedWriter writer = currentWriter;
        currentWriter = null;
        currentDay = null;
        if (writer != null) {
            try {
                writer.flush();
                writer.close();
            } catch (IOException e) {
                ioProblems.add("close failed: " + e);
            }
        }
    }

    /** Deletes day-files older than retentionDays; unparseable names are kept. */
    public int purgeOlderThan(int retentionDays, long nowMillis) {
        int purged = 0;
        if (!Files.isDirectory(outputDir)) {
            return 0; // fresh server: nothing to purge yet
        }
        long cutoff = LocalDate.ofInstant(Instant.ofEpochMilli(nowMillis), ZoneOffset.UTC)
                .minusDays(retentionDays).toEpochDay();
        try (Stream<Path> files = Files.list(outputDir)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Matcher matcher = DAY_FILE.matcher(file.getFileName().toString());
                if (!matcher.matches()) {
                    continue;
                }
                LocalDate day;
                try {
                    day = LocalDate.parse(matcher.group(1), DateTimeFormatter.BASIC_ISO_DATE);
                } catch (RuntimeException e) {
                    continue; // not a date we understand — never delete blindly
                }
                if (day.toEpochDay() < cutoff) {
                    long size = Files.size(file);
                    Files.delete(file);
                    bytesWritten = Math.max(0L, bytesWritten - size);
                    purged++;
                }
            }
        } catch (IOException e) {
            ioProblems.add("purge failed: " + e);
        }
        return purged;
    }

    /** Stops the writer and deletes every corpus file. */
    public int deleteAll() {
        close();
        int deleted = 0;
        if (!Files.isDirectory(outputDir)) {
            bytesWritten = 0L;
            rowsWritten = 0L;
            quotaDropped = 0L;
            haltedReason = null;
            return 0;
        }
        try (Stream<Path> files = Files.list(outputDir)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Files.delete(file);
                deleted++;
            }
        } catch (IOException e) {
            ioProblems.add("delete-all failed: " + e);
        }
        bytesWritten = 0L;
        rowsWritten = 0L;
        quotaDropped = 0L;
        haltedReason = null;
        return deleted;
    }

    public long bytesWritten() {
        return bytesWritten;
    }

    public long rowsWritten() {
        return rowsWritten;
    }

    public long quotaDropped() {
        return quotaDropped;
    }

    public String haltedReason() {
        return haltedReason;
    }

    public Path outputDir() {
        return outputDir;
    }

    public List<String> ioProblems() {
        return List.copyOf(ioProblems);
    }
}
