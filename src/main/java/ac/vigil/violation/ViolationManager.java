package ac.vigil.violation;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import ac.vigil.config.CheckSettings;
import ac.vigil.data.PlayerData;

/**
 * Central violation pipeline: turns check evidence into VL + a verdict.
 *
 * <p>Server-agnostic (no Bukkit imports) so the full pipeline is unit
 * testable. Listeners are notified synchronously on the calling thread;
 * keep them cheap or re-dispatch manually.</p>
 */
public final class ViolationManager {

    public interface ViolationListener {
        void onViolation(Violation violation, Verdict verdict);
    }

    private final Function<String, CheckSettings> settings;
    private final List<ViolationListener> listeners = new CopyOnWriteArrayList<>();

    public ViolationManager(Function<String, CheckSettings> settings) {
        this.settings = settings;
    }

    /**
     * Records evidence and returns the resulting verdict. Returns
     * {@link Verdict#NONE} without side effects when the check is disabled
     * or the evidence delta is non-positive.
     */
    public Verdict record(java.util.UUID playerId, String checkName, double delta, String debug, PlayerData data) {
        if (delta <= 0.0D || Double.isNaN(delta) || Double.isInfinite(delta)) {
            return Verdict.NONE;
        }
        CheckSettings s = settings.apply(checkName);
        if (s == null || !s.isEnabled()) {
            return Verdict.NONE;
        }
        double vl = data.addViolation(checkName, delta, s.getMaxVl());
        Verdict verdict;
        if (s.getKickVl() > 0.0D && vl >= s.getKickVl()) {
            verdict = Verdict.KICK;
        } else if (vl >= s.getWarnVl()) {
            verdict = Verdict.WARN;
        } else if (vl >= s.getAlertVl()) {
            verdict = Verdict.ALERT;
        } else {
            verdict = Verdict.NONE;
        }
        Violation violation = new Violation(playerId, checkName, vl, delta, debug, System.currentTimeMillis());
        for (ViolationListener listener : listeners) {
            listener.onViolation(violation, verdict);
        }
        return verdict;
    }

    public void addListener(ViolationListener listener) {
        listeners.add(listener);
    }

    /** Periodic VL decay for one player; delegates to {@link PlayerData#decay}. */
    public boolean decay(PlayerData data, double amountPerTick, long decayDelayMillis) {
        return data.decay(amountPerTick, decayDelayMillis);
    }
}
