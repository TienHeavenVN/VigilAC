package ac.vigil.capture;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CaptureWriterTest {

    @TempDir
    Path dir;

    private static CaptureRecord record(String collectedUtc) {
        double[] features = new double[22];
        features[0] = 1.0D;
        return new CaptureRecord("a".repeat(32), "b".repeat(32), "1.21.8", "VANILLA", "MOUSE",
                20.0D, 31.0D, "2f2b0a3eb4563552b5c995c285f7179ce34490c450c46b63e9dbc022916ee688",
                "LEGITIMATE", "SERVER_TELEMETRY_CURATION", 0.9D,
                Instant.parse(collectedUtc), "test", features, "consent-capture/1");
    }

    @Test
    void writesRowsToDailyFileAndCountsBytes() {
        Path corpus = dir.resolve("corpus");
        CaptureBuffer buffer = new CaptureBuffer(16);
        long now = Instant.parse("2026-11-08T10:15:30Z").toEpochMilli();
        CaptureWriter writer = new CaptureWriter(corpus, 10_000_000L, buffer, () -> now);
        writer.start();
        assertTrue(buffer.offer(record("2026-11-08T10:15:30Z")));
        writer.close();
        Path file = corpus.resolve("capture-20261108.jsonl");
        assertTrue(Files.exists(file));
        assertEquals(1, writer.rowsWritten());
        assertTrue(writer.bytesWritten() > 0);
        assertNull(writer.haltedReason());
    }

    @Test
    void quotaHaltStopsWritesAndCountsDrops() {
        Path corpus = dir.resolve("corpus");
        CaptureBuffer buffer = new CaptureBuffer(16);
        long now = Instant.parse("2026-11-08T10:15:30Z").toEpochMilli();
        // 1-byte cap: the first row exceeds it, everything after is counted, nothing lands on disk.
        CaptureWriter writer = new CaptureWriter(corpus, 1L, buffer, () -> now);
        writer.start();
        buffer.offer(record("2026-11-08T10:15:30Z"));
        buffer.offer(record("2026-11-08T10:15:31Z"));
        writer.close();
        assertNotNull(writer.haltedReason());
        assertEquals(0, writer.rowsWritten());
        assertTrue(writer.quotaDropped() >= 1);
    }

    @Test
    void diskBytesAreReScannedAtStartup() throws Exception {
        Path corpus = dir.resolve("corpus");
        Files.createDirectories(corpus);
        Files.write(corpus.resolve("capture-20261107.jsonl"), "x".repeat(512).getBytes(StandardCharsets.UTF_8));
        CaptureBuffer buffer = new CaptureBuffer(4);
        CaptureWriter writer = new CaptureWriter(corpus, 100L, buffer, System::currentTimeMillis);
        writer.start();
        assertNotNull(writer.haltedReason(), "existing bytes over quota must halt capture at start");
        writer.close();
    }

    @Test
    void purgeOlderThanDeletesOnlyOldDayFiles() throws Exception {
        Path corpus = dir.resolve("corpus");
        Files.createDirectories(corpus);
        Path old = corpus.resolve("capture-20000101.jsonl");
        Path recent = corpus.resolve("capture-29990101.jsonl");
        Files.writeString(old, "{}\n");
        Files.writeString(recent, "{}\n");
        CaptureBuffer buffer = new CaptureBuffer(4);
        CaptureWriter writer = new CaptureWriter(corpus, 10_000_000L, buffer, System::currentTimeMillis);
        writer.start();
        int purged = writer.purgeOlderThan(30, System.currentTimeMillis());
        writer.close();
        assertEquals(1, purged);
        assertFalse(Files.exists(old));
        assertTrue(Files.exists(recent));
    }

    @Test
    void deleteAllWipesCorpusAndResetsCounters() throws Exception {
        Path corpus = dir.resolve("corpus");
        CaptureBuffer buffer = new CaptureBuffer(16);
        long now = Instant.parse("2026-11-08T10:15:30Z").toEpochMilli();
        CaptureWriter writer = new CaptureWriter(corpus, 10_000_000L, buffer, () -> now);
        writer.start();
        buffer.offer(record("2026-11-08T10:15:30Z"));
        writer.close();
        int deleted = writer.deleteAll();
        assertEquals(1, deleted);
        assertEquals(0, writer.rowsWritten());
        assertEquals(0, writer.bytesWritten());
        assertFalse(Files.exists(corpus.resolve("capture-20261108.jsonl")));
    }

    @Test
    void unwritableOutputFailsSafeWithoutThrowing() throws Exception {
        Path notADirectory = dir.resolve("notadir");
        Files.writeString(notADirectory, "file");
        CaptureBuffer buffer = new CaptureBuffer(16);
        long now = Instant.parse("2026-11-08T10:15:30Z").toEpochMilli();
        CaptureWriter writer = new CaptureWriter(notADirectory, 10_000_000L, buffer, () -> now);
        writer.start(); // must not throw
        buffer.offer(record("2026-11-08T10:15:30Z"));
        writer.close(); // must not throw either
        assertEquals(0, writer.rowsWritten());
        assertFalse(writer.ioProblems().isEmpty());
    }
}
