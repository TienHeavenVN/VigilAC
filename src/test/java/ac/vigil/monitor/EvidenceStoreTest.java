package ac.vigil.monitor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class EvidenceStoreTest {

    private final AtomicLong now = new AtomicLong(1_000_000L);

    private EvidenceStore store(int capacity, long ttl) {
        return new EvidenceStore(capacity, ttl, now::get);
    }

    @Test
    void evictsOldestBeyondCapacity() {
        EvidenceStore store = store(8, 60_000L);
        for (int i = 0; i < 10; i++) {
            now.set(1_000_000L + i * 10L);
            store.add(now.get(), EvidenceType.CHECK_EVIDENCE, 1.0D, "test", "e" + i);
        }
        assertEquals(8, store.size(), "ring must hold at most capacity entries");
        var snapshot = store.snapshot();
        assertEquals("e2", snapshot.get(0).detail(), "oldest entries must be evicted first");
        assertEquals("e9", snapshot.get(snapshot.size() - 1).detail());
    }

    @Test
    void ttlExpiresEntries() {
        EvidenceStore store = store(16, 1_000L);
        store.add(now.get(), EvidenceType.LANDED, 1.0D, "test", "landed");
        assertEquals(1, store.size());
        now.addAndGet(1_001L);
        assertEquals(0, store.size(), "entry older than TTL must be invisible");
        assertNull(store.latestOfType(EvidenceType.LANDED));
    }

    @Test
    void countByTypeSinceFiltersByTypeAndWindow() {
        EvidenceStore store = store(16, 60_000L);
        long base = now.get();
        store.add(base, EvidenceType.LANDED, 1.0D, "test", "a");
        store.add(base + 100, EvidenceType.NOFALL_SUSPICIOUS_LANDING, 0.8D, "test", "b");
        store.add(base + 200, EvidenceType.NOFALL_SUSPICIOUS_LANDING, 0.8D, "test", "c");
        store.add(base + 300, EvidenceType.LANDED, 1.0D, "test", "d");
        assertEquals(2, store.countByTypeSince(EvidenceType.NOFALL_SUSPICIOUS_LANDING, base));
        assertEquals(1, store.countByTypeSince(EvidenceType.NOFALL_SUSPICIOUS_LANDING, base + 150));
        assertEquals(2, store.countByTypeSince(EvidenceType.LANDED, base));
    }

    @Test
    void latestOfTypeReturnsNewestUnexpired() {
        EvidenceStore store = store(16, 60_000L);
        long base = now.get();
        store.add(base, EvidenceType.TELEPORT, 1.0D, "test", "first");
        store.add(base + 500, EvidenceType.TELEPORT, 1.0D, "test", "second");
        assertEquals("second", store.latestOfType(EvidenceType.TELEPORT).detail());
        assertNull(store.latestOfType(EvidenceType.WORLD_CHANGE));
    }

    @Test
    void concurrentAddsStayBoundedAndConsistent() throws InterruptedException {
        EvidenceStore store = store(32, 60_000L);
        int threads = 4, perThread = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        for (int t = 0; t < threads; t++) {
            int id = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        store.add(now.get() + i, EvidenceType.CHECK_EVIDENCE, 1.0D, "t" + id, "i" + i);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertTrue(store.size() <= 32, "concurrent writers must never exceed capacity");
    }
}
