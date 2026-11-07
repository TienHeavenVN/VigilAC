package ac.vigil.packet;

import ac.vigil.detection.AttackAttemptSnapshot;

/**
 * Receive side of the attack-attempt hand-off. Implementations must be safe
 * to call from netty threads: non-blocking, bounded, and free of any
 * Bukkit/Paper world or entity API access.
 */
public interface AttackAttemptSink {

    void submit(AttackAttemptSnapshot snapshot);
}
