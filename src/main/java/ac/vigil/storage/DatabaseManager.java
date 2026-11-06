package ac.vigil.storage;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Asynchronous SQLite persistence for violation events.
 *
 * <ul>
 *   <li>All database work happens on a single-threaded executor; the main
 *       thread never blocks on I/O.</li>
 *   <li>Rows are batched (default 25 rows or 5 seconds) into one transaction.</li>
 *   <li>WAL journal mode and a busy timeout avoid reader/writer stalls.</li>
 *   <li>On any unrecoverable failure the manager disables itself, logs one
 *       severe message, and drops further records instead of spamming the
 *       log or throwing into gameplay code.</li>
 * </ul>
 */
public final class DatabaseManager {

    private static final String DRIVER = "org.sqlite.JDBC";
    private static final int BATCH_SIZE = 25;
    private static final long FLUSH_INTERVAL_MILLIS = 5_000L;

    private final File file;
    private final boolean enabled;
    private final Logger logger;
    private final ExecutorService executor;

    private final List<ViolationRecord> pending = new ArrayList<>();
    private Connection connection;
    private volatile boolean broken;
    private long lastFlush = System.currentTimeMillis();

    public DatabaseManager(File file, boolean enabled, Logger logger) {
        this.file = file;
        this.enabled = enabled;
        this.logger = logger;
        this.executor = enabled
                ? Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "vigilac-storage");
                    thread.setDaemon(true);
                    return thread;
                })
                : null;
    }

    /** Opens the database and ensures the schema. Must be called once at boot. */
    public void start() {
        if (!enabled) {
            logger.info("Violation storage is disabled in config.yml");
            return;
        }
        try {
            Class.forName(DRIVER);
        } catch (ClassNotFoundException ex) {
            disable("SQLite JDBC driver (org.xerial:sqlite-jdbc) not available on the classpath");
            return;
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            logger.warning("Could not create storage directory " + parent);
        }
        try {
            connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA busy_timeout=5000");
                st.execute("""
                        CREATE TABLE IF NOT EXISTS violations (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            player_uuid TEXT NOT NULL,
                            check_name TEXT NOT NULL,
                            violation_level REAL NOT NULL,
                            detail TEXT,
                            created INTEGER NOT NULL
                        )""");
                st.execute("CREATE INDEX IF NOT EXISTS idx_violations_player ON violations(player_uuid)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_violations_check ON violations(check_name)");
            }
            logger.info("Violation storage ready at " + file.getAbsolutePath());
        } catch (SQLException ex) {
            disable("Failed to open SQLite database: " + ex.getMessage());
        }
    }

    /** Queues a violation record; safe to call from any thread, never blocks. */
    public void enqueue(ViolationRecord record) {
        if (!enabled || broken) {
            return;
        }
        executor.execute(() -> {
            pending.add(record);
            maybeFlush(false);
        });
    }

    /** Called periodically (async) and at shutdown to drain the buffer. */
    public void flushIfDue() {
        if (!enabled || broken) {
            return;
        }
        executor.execute(() -> maybeFlush(true));
    }

    private void maybeFlush(boolean force) {
        if (pending.isEmpty()) {
            lastFlush = System.currentTimeMillis();
            return;
        }
        long now = System.currentTimeMillis();
        if (!force && pending.size() < BATCH_SIZE && now - lastFlush < FLUSH_INTERVAL_MILLIS) {
            return;
        }
        List<ViolationRecord> batch = new ArrayList<>(pending);
        pending.clear();
        lastFlush = now;
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO violations(player_uuid, check_name, violation_level, detail, created) VALUES (?,?,?,?,?)")) {
                for (ViolationRecord record : batch) {
                    ps.setString(1, record.getPlayer().toString());
                    ps.setString(2, record.getCheckName());
                    ps.setDouble(3, record.getViolationLevel());
                    ps.setString(4, record.getDebug());
                    ps.setLong(5, record.getCreatedMillis());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            connection.commit();
            connection.setAutoCommit(true);
        } catch (SQLException ex) {
            try {
                connection.rollback();
                connection.setAutoCommit(true);
            } catch (SQLException ignored) {
                // connection is most likely broken; disable below
            }
            disable("Failed to write violation batch (" + batch.size() + " rows): " + ex.getMessage());
        }
    }

    /** Flushes and closes the database. Blocks briefly (max 10s) — call from onDisable. */
    public void close() {
        if (!enabled) {
            return;
        }
        try {
            executor.submit(() -> maybeFlush(true)).get(5, TimeUnit.SECONDS);
        } catch (Exception ex) {
            logger.warning("Timed out flushing violation storage: " + ex.getMessage());
        }
        try {
            executor.shutdown();
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
        try {
            if (connection != null) {
                connection.close();
            }
        } catch (SQLException ex) {
            logger.warning("Failed to close SQLite connection: " + ex.getMessage());
        }
    }

    private void disable(String reason) {
        if (!broken) {
            broken = true;
            logger.severe("VigilAC violation storage disabled: " + reason);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isBroken() {
        return broken;
    }
}
