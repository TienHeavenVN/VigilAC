package ac.vigil.ai.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;
import ac.vigil.ai.ml.AnomalyModel;
import ac.vigil.ai.ml.ModelManifest;
import ac.vigil.ai.telemetry.AiFeatureSchema;
import ac.vigil.ai.telemetry.AiFeatureSnapshot;

class AiShadowWorkerPoolTest {

    private static final Logger NOOP = Logger.getAnonymousLogger();

    private static AiFeatureSnapshot validSnapshot() {
        double[] v = new double[AiFeatureSchema.FEATURE_COUNT];
        java.util.Arrays.fill(v, 0.5D);
        return new AiFeatureSnapshot(1_000L, v);
    }

    /** Test double: programmable score, latency, or exception; counts invocations. */
    private static final class FakeModel implements AnomalyModel {
        private final String id;
        private volatile double score = 0.42D;
        private volatile RuntimeException failure;
        private volatile long sleepMillis;
        private final AtomicInteger calls = new AtomicInteger();

        private FakeModel(String id) {
            this.id = id;
        }

        @Override
        public ModelManifest manifest() {
            return null; // unused by the pool
        }

        @Override
        public String modelId() {
            return id;
        }

        @Override
        public double anomalyScore(AiFeatureSnapshot snapshot) {
            calls.incrementAndGet();
            if (sleepMillis > 0L) {
                try {
                    Thread.sleep(sleepMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("interrupted", e);
                }
            }
            if (failure != null) {
                throw failure;
            }
            return score;
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public String status() {
            return "loaded (fake)";
        }
    }

    private static LongSupplier clock() {
        return System::currentTimeMillis;
    }

    private static AiInferenceSink collectingSink(AtomicInteger scored, AtomicInteger failed,
                                                  CountDownLatch scoredLatch) {
        return new AiInferenceSink() {
            @Override
            public void onScored(UUID playerId, String modelId, double score, long latencyMillis) {
                scored.incrementAndGet();
                if (scoredLatch != null) {
                    scoredLatch.countDown();
                }
            }

            @Override
            public void onFailed(UUID playerId, String modelId, String reason) {
                failed.incrementAndGet();
            }
        };
    }

    @Test
    void submitAfterStopIsRejected() {
        AiShadowWorkerPool pool = new AiShadowWorkerPool(4, 1, 250L, List.of(new FakeModel("m1")),
                collectingSink(new AtomicInteger(), new AtomicInteger(), null), NOOP, clock());
        pool.start();
        pool.stop();
        assertFalse(pool.isRunning());
        assertFalse(pool.submit(UUID.randomUUID(), validSnapshot()));
        pool.stop(); // idempotent
    }

    @Test
    void scoredJobsReachSinkAndCounters() throws Exception {
        FakeModel model = new FakeModel("m1");
        model.score = 0.77D;
        AtomicInteger scored = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicLong observedScore = new AtomicLong();
        AiInferenceSink sink = new AiInferenceSink() {
            @Override
            public void onScored(UUID playerId, String modelId, double score, long latencyMillis) {
                scored.incrementAndGet();
                observedScore.set(Math.round(score * 1000.0D));
                latch.countDown();
            }

            @Override
            public void onFailed(UUID playerId, String modelId, String reason) {
                failed.incrementAndGet();
            }
        };
        AiShadowWorkerPool pool = new AiShadowWorkerPool(4, 1, 250L, List.of(model), sink, NOOP, clock());
        pool.start();
        assertTrue(pool.submit(UUID.randomUUID(), validSnapshot()));
        assertTrue(latch.await(3, TimeUnit.SECONDS), "worker must process the job");
        pool.stop();
        assertEquals(1, scored.get());
        assertEquals(0, failed.get());
        assertEquals(770L, observedScore.get());
        assertEquals(1, pool.getCompleted());
        assertEquals(0, pool.getFailed());
        assertTrue(pool.getAverageLatencyMillis() >= 0.0D);
    }

    @Test
    void queueFullDropsNewestAndCountsIt() throws Exception {
        FakeModel model = new FakeModel("slow");
        model.sleepMillis = 600L; // occupies the worker so the queue fills deterministically enough
        AtomicInteger scored = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        AiShadowWorkerPool pool = new AiShadowWorkerPool(1, 1, 5_000L, List.of(model),
                collectingSink(scored, failed, null), NOOP, clock());
        pool.start();
        UUID id = UUID.randomUUID();
        pool.submit(id, validSnapshot());   // picked up by worker (or queued)
        Thread.sleep(50L);                  // let the worker enter the job
        pool.submit(id, validSnapshot());   // fills capacity-1 queue
        boolean accepted = pool.submit(id, validSnapshot()); // must be dropped
        assertFalse(accepted, "overflow job must be rejected, never block");
        assertTrue(pool.getDroppedFull() >= 1, "drop must be counted");
        pool.stop();
    }

    @Test
    void overlongInferenceIsCountedAsTimeoutAndDiscarded() throws Exception {
        FakeModel model = new FakeModel("sleepy");
        model.sleepMillis = 400L;
        AtomicInteger scored = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        CountDownLatch failedLatch = new CountDownLatch(1);
        AiInferenceSink sink = new AiInferenceSink() {
            @Override
            public void onScored(UUID playerId, String modelId, double score, long latencyMillis) {
                scored.incrementAndGet();
            }

            @Override
            public void onFailed(UUID playerId, String modelId, String reason) {
                failed.incrementAndGet();
                failedLatch.countDown();
            }
        };
        AiShadowWorkerPool pool = new AiShadowWorkerPool(2, 1, 30L, // 30 ms budget vs 400 ms job
                List.of(model), sink, NOOP, clock());
        pool.start();
        pool.submit(UUID.randomUUID(), validSnapshot());
        assertTrue(failedLatch.await(3, TimeUnit.SECONDS), "timeout must be reported as failed");
        pool.stop();
        assertEquals(0, scored.get(), "over-budget result must be discarded");
        assertEquals(1, pool.getTimedOut());
        assertTrue(pool.getFailed() >= 1);
        assertEquals(0, pool.getCompleted());
    }

    @Test
    void modelExceptionIsCaughtCountedAndReported() throws Exception {
        FakeModel model = new FakeModel("broken");
        model.failure = new RuntimeException("boom");
        AtomicInteger scored = new AtomicInteger();
        CountDownLatch failedLatch = new CountDownLatch(1);
        AtomicBoolean reasonSeen = new AtomicBoolean();
        AiInferenceSink sink = new AiInferenceSink() {
            @Override
            public void onScored(UUID playerId, String modelId, double score, long latencyMillis) {
                scored.incrementAndGet();
            }

            @Override
            public void onFailed(UUID playerId, String modelId, String reason) {
                reasonSeen.set(reason.contains("boom"));
                failedLatch.countDown();
            }
        };
        AiShadowWorkerPool pool = new AiShadowWorkerPool(2, 1, 250L, List.of(model), sink, NOOP, clock());
        pool.start();
        pool.submit(UUID.randomUUID(), validSnapshot());
        assertTrue(failedLatch.await(3, TimeUnit.SECONDS));
        pool.stop();
        assertEquals(0, scored.get(), "failed inference must never produce a score");
        assertTrue(reasonSeen.get());
        assertEquals(1, pool.getFailed());
    }

    @Test
    void invalidSnapshotIsRejectedBeforeInference() {
        FakeModel model = new FakeModel("never-called");
        AiShadowWorkerPool pool = new AiShadowWorkerPool(2, 1, 250L, List.of(model),
                collectingSink(new AtomicInteger(), new AtomicInteger(), null), NOOP, clock());
        pool.start();
        double[] zero = new double[AiFeatureSchema.FEATURE_COUNT]; // all zeros ⇒ EMPTY_SIGNAL
        pool.submit(UUID.randomUUID(), new AiFeatureSnapshot(1_000L, zero));
        // give the worker a moment; validation is effectively synchronous in the loop
        for (int i = 0; i < 50 && model.calls.get() == 0 && pool.getInvalidSnapshots() == 0; i++) {
            try {
                Thread.sleep(10L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        assertEquals(0, model.calls.get(), "invalid snapshots must not reach the model");
        assertEquals(1, pool.getInvalidSnapshots());
        pool.stop();
    }

    @Test
    void constructorRejectsDegenerateConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new AiShadowWorkerPool(0, 1, 250L, List.of(),
                collectingSink(new AtomicInteger(), new AtomicInteger(), null), NOOP, clock()));
        assertThrows(IllegalArgumentException.class, () -> new AiShadowWorkerPool(1, 0, 250L, List.of(),
                collectingSink(new AtomicInteger(), new AtomicInteger(), null), NOOP, clock()));
        assertThrows(IllegalArgumentException.class, () -> new AiShadowWorkerPool(1, 1, 0L, List.of(),
                collectingSink(new AtomicInteger(), new AtomicInteger(), null), NOOP, clock()));
    }

    @Test
    void countersSnapshotIsConsistent() {
        AiShadowWorkerPool pool = new AiShadowWorkerPool(4, 1, 250L, List.of(new FakeModel("m1")),
                collectingSink(new AtomicInteger(), new AtomicInteger(), null), NOOP, clock());
        var counters = pool.countersSnapshot();
        assertEquals(0L, counters.get("submitted"));
        assertEquals(0L, counters.get("droppedFull"));
        assertEquals(0L, counters.get("failed"));
    }
}
