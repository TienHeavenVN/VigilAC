package ac.vigil.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Storage round-trip on a real SQLite file: start, enqueue, flush, close,
 * then read the row back with a fresh connection. Exercises the async
 * pipeline and the shutdown path end to end.
 */
class DatabaseManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void recordsPersistAcrossRestart() throws Exception {
        File db = tempDir.resolve("vigilac-test.db").toFile();
        Logger logger = Logger.getLogger("vigilac-test");

        DatabaseManager manager = new DatabaseManager(db, true, logger);
        manager.start();
        assertTrue(manager.isEnabled() && !manager.isBroken(), "storage must open cleanly");

        UUID player = UUID.randomUUID();
        manager.enqueue(new ViolationRecord(player, "speed", 6.5D, "ema=6.1 cap=4.4", 1_700_000_000_000L));
        manager.flushIfDue();
        manager.close(); // blocks until the batch is drained

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db.getAbsolutePath());
             Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT player_uuid, check_name, violation_level, detail FROM violations")) {
            assertTrue(rs.next(), "the enqueued record must be persisted");
            assertEquals(player.toString(), rs.getString(1));
            assertEquals("speed", rs.getString(2));
            assertEquals(6.5D, rs.getDouble(3), 0.001D);
            assertEquals("ema=6.1 cap=4.4", rs.getString(4));
            assertFalse(rs.next(), "exactly one row expected");
        }
    }

    @Test
    void disabledStorageIsInert() {
        DatabaseManager manager = new DatabaseManager(tempDir.resolve("never.db").toFile(), false, Logger.getLogger("vigilac-test-2"));
        manager.start();
        manager.enqueue(new ViolationRecord(UUID.randomUUID(), "reach", 1.0D, "debug", 1L));
        manager.flushIfDue();
        manager.close();
        assertFalse(tempDir.resolve("never.db").toFile().exists(), "disabled storage must not create files");
    }

    @Test
    void enqueueAndFlushAfterCloseAreSafeNoOps() throws Exception {
        // A maintenance tick racing plugin shutdown must never throw into the
        // scheduler (RejectedExecutionException) nor write after close.
        File db = tempDir.resolve("vigilac-test-close.db").toFile();
        Logger logger = Logger.getLogger("vigilac-test-3");

        DatabaseManager manager = new DatabaseManager(db, true, logger);
        manager.start();
        manager.enqueue(new ViolationRecord(UUID.randomUUID(), "speed", 2.0D, "debug", 1L));
        manager.close();
        assertDoesNotThrow(() -> {
            manager.enqueue(new ViolationRecord(UUID.randomUUID(), "speed", 2.0D, "debug", 2L));
            manager.flushIfDue();
            manager.close(); // double close must be safe too
        });
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db.getAbsolutePath());
             Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM violations")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1), "only the pre-close record may be persisted");
        }
    }

    @Test
    void flushDiagnosticsAreExposed() throws Exception {
        File db = tempDir.resolve("vigilac-test-flush.db").toFile();
        DatabaseManager manager = new DatabaseManager(db, true, Logger.getLogger("vigilac-test-4"));
        manager.start();
        assertEquals(0L, manager.getTotalFlushedRows());
        for (int i = 0; i < 3; i++) {
            manager.enqueue(new ViolationRecord(UUID.randomUUID(), "reach", 1.0D, "debug", (long) i));
        }
        manager.flushIfDue();
        manager.close();
        assertEquals(3L, manager.getTotalFlushedRows(), "all queued rows must be accounted for");
        assertEquals(3, manager.getLastBatchRows());
        assertTrue(manager.getLastFlushNanos() > 0L, "flush latency must be measured");
    }
}
