package ac.vigil.capture;

import java.util.List;

/**
 * Immutable snapshot of capture state for /vigilac capture status. Contains
 * counts and flags only — never identifiers.
 */
public record CaptureStatus(boolean enabled, boolean ready, boolean halted, String haltedReason,
                            long rowsWritten, long rowsSkippedInvalid, long rowsDroppedFull,
                            long quotaDropped, long bytesWritten, long maxTotalBytes,
                            int bufferSize, int consentedPlayers, int trackedSessions,
                            List<String> notes) {

    public List<String> lines() {
        var sb = new java.util.ArrayList<String>();
        sb.add("enabled=" + enabled + " ready=" + ready + (halted ? " HALTED (" + haltedReason + ")" : ""));
        sb.add("rows written=" + rowsWritten + " skipped-invalid=" + rowsSkippedInvalid
                + " dropped-full=" + rowsDroppedFull + " dropped-quota=" + quotaDropped);
        sb.add("bytes written=" + bytesWritten + " / " + maxTotalBytes);
        sb.add("buffer=" + bufferSize + " consented=" + consentedPlayers + " sessions=" + trackedSessions);
        sb.addAll(notes);
        return sb;
    }
}
