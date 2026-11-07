package ac.vigil.capture;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded buffer between the scheduler and the writer thread. Drop-newest with a
 * counter; this class never blocks, never throws on the producer path and never
 * grows beyond capacity.
 */
public final class CaptureBuffer {

    private final ArrayBlockingQueue<CaptureRecord> queue;
    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong droppedFull = new AtomicLong();
    private final AtomicLong drained = new AtomicLong();

    public CaptureBuffer(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1");
        }
        this.queue = new ArrayBlockingQueue<>(capacity);
    }

    /** @return true when the record was enqueued; false when the buffer is full (dropped, counted). */
    public boolean offer(CaptureRecord record) {
        if (record == null) {
            return false;
        }
        submitted.incrementAndGet();
        boolean accepted = queue.offer(record);
        if (!accepted) {
            droppedFull.incrementAndGet();
        }
        return accepted;
    }

    /** Polls one record or null. Writer-thread use only. */
    public CaptureRecord poll() {
        CaptureRecord record = queue.poll();
        if (record != null) {
            drained.incrementAndGet();
        }
        return record;
    }

    public int size() {
        return queue.size();
    }

    public long submitted() {
        return submitted.get();
    }

    public long droppedFull() {
        return droppedFull.get();
    }

    public long drained() {
        return drained.get();
    }
}
