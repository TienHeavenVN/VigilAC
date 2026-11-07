package ac.vigil.ai.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.logging.Logger;
import ac.vigil.ai.ml.AnomalyModel;
import ac.vigil.ai.telemetry.AiFeatureSnapshot;
import ac.vigil.ai.telemetry.FeatureValidator;

/**
 * Bounded queue + fixed worker pool for shadow ML inference.
 *
 * <h2>Guarantees</h2>
 * <ul>
 *   <li>{@link #submit} is O(1) and never blocks the caller (scheduler or
 *       command thread). When the queue is full the job is DROPPED and counted
 *       — backpressure, never unbounded growth, never main-thread work.</li>
 *   <li>Work runs on dedicated daemon threads. Netty threads and the main
 *       server thread never run inference.</li>
 *   <li>Each job is timed; overruns are counted in {@code timedOut} and the
 *       result is discarded. (Java cannot forcibly preempt arbitrary native
 *       code inside ONNX; the timeout is a watchdog + accounting boundary, and
 *       the pure-Java forest path completes in microseconds.)</li>
 *   <li>Any Throwable inside a job is caught, counted as {@code failed} and
 *       reported to the sink — the pool never dies silently.</li>
 *   <li>{@link #stop()} interrupts workers; callers should stop() at plugin
 *       disable so no inference outlives the plugin.</li>
 * </ul>
 *
 * <p>The pool deliberately owns NO Bukkit references beyond UUIDs — it is a
 * pure Java component, unit-testable without a server.</p>
 */
public final class AiShadowWorkerPool {

    private static final class Job {
        private final UUID playerId;
        private final AiFeatureSnapshot snapshot;

        private Job(UUID playerId, AiFeatureSnapshot snapshot) {
            this.playerId = playerId;
            this.snapshot = snapshot;
        }
    }

    private final int capacity;
    private final int workerThreads;
    private final long timeoutMillis;
    private final List<AnomalyModel> models;
    private final AiInferenceSink sink;
    private final Logger logger;
    private final LongSupplier clock;
    private final BlockingQueue<Job> queue;
    private final List<Thread> workers = new ArrayList<>();
    private volatile boolean running;

    private long submitted;
    private long droppedFull;
    private long completed;
    private long failed;
    private long timedOut;
    private long invalidSnapshots;
    private double latencyEmaMillis;
    private static final double LATENCY_ALPHA = 0.2D;

    public AiShadowWorkerPool(int capacity, int workerThreads, long timeoutMillis,
                              List<AnomalyModel> models, AiInferenceSink sink,
                              Logger logger, LongSupplier clock) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1");
        }
        if (workerThreads < 1) {
            throw new IllegalArgumentException("workerThreads must be >= 1");
        }
        if (timeoutMillis < 1) {
            throw new IllegalArgumentException("timeoutMillis must be >= 1");
        }
        this.capacity = capacity;
        this.workerThreads = workerThreads;
        this.timeoutMillis = timeoutMillis;
        this.models = List.copyOf(models);
        this.sink = sink;
        this.logger = logger;
        this.clock = clock;
        this.queue = new ArrayBlockingQueue<>(capacity);
    }

    /** Starts workers. Idempotent. */
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        int threads = Math.min(workerThreads, 4);
        for (int i = 0; i < threads; i++) {
            Thread worker = new Thread(this::workerLoop, "VigilAC-AI-worker-" + i);
            worker.setDaemon(true);
            workers.add(worker);
            worker.start();
        }
    }

    /** @return true when the job was enqueued; false when it was dropped (queue full) — never blocks. */
    public boolean submit(UUID playerId, AiFeatureSnapshot snapshot) {
        if (!running) {
            return false;
        }
        submitted++;
        boolean accepted = queue.offer(new Job(playerId, snapshot));
        if (!accepted) {
            droppedFull++;
        }
        return accepted;
    }

    private void workerLoop() {
        while (running) {
            Job job;
            try {
                job = queue.poll(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return; // stop() was called
            }
            if (job == null) {
                continue;
            }
            process(job);
        }
    }

    private void process(Job job) {
        if (FeatureValidator.check(job.snapshot) != FeatureValidator.Result.OK) {
            invalidSnapshots++;
            failed++;
            return;
        }
        for (AnomalyModel model : models) {
            long start = System.nanoTime();
            try {
                double score = model.anomalyScore(job.snapshot);
                long latencyMillis = (System.nanoTime() - start) / 1_000_000L;
                latencyEmaMillis = latencyEmaMillis == 0.0D ? latencyMillis
                        : LATENCY_ALPHA * latencyMillis + (1.0D - LATENCY_ALPHA) * latencyEmaMillis;
                if (latencyMillis > timeoutMillis) {
                    timedOut++;
                    failed++;
                    try {
                        sink.onFailed(job.playerId, model.modelId(), "timeout after " + latencyMillis + " ms");
                    } catch (Throwable sinkError) {
                        logger().warning("AI sink threw on timeout path: " + sinkError);
                    }
                    continue;
                }
                completed++;
                try {
                    sink.onScored(job.playerId, model.modelId(), score, latencyMillis);
                } catch (Throwable sinkError) {
                    logger().warning("AI sink threw on score path: " + sinkError);
                }
            } catch (Throwable t) {
                failed++;
                try {
                    sink.onFailed(job.playerId, model.modelId(), t.toString());
                } catch (Throwable sinkError) {
                    logger().warning("AI sink threw on failure path: " + sinkError);
                }
            }
        }
    }

    private Logger logger() {
        return logger == null ? Logger.getAnonymousLogger() : logger;
    }

    public int getPendingCount() { return queue.size(); }
    public long getSubmitted() { return submitted; }
    public long getDroppedFull() { return droppedFull; }
    public long getCompleted() { return completed; }
    public long getFailed() { return failed; }
    public long getTimedOut() { return timedOut; }
    public long getInvalidSnapshots() { return invalidSnapshots; }
    public double getAverageLatencyMillis() { return latencyEmaMillis; }
    public boolean isRunning() { return running; }

    /** Snapshot of current counters (counters keep advancing while running). */
    public Map<String, Long> countersSnapshot() {
        Map<String, Long> map = new java.util.LinkedHashMap<>();
        map.put("submitted", submitted);
        map.put("droppedFull", droppedFull);
        map.put("completed", completed);
        map.put("failed", failed);
        map.put("timedOut", timedOut);
        map.put("invalidSnapshots", invalidSnapshots);
        return map;
    }

    /** Stops workers; pending jobs are discarded. Idempotent. */
    public synchronized void stop() {
        running = false;
        for (Thread worker : workers) {
            worker.interrupt();
        }
        workers.clear();
        queue.clear();
    }
}
