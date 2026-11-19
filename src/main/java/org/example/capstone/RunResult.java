package org.example.capstone;

import com.anthropic.models.messages.StopReason;

import java.util.Set;

/** What a whole run produced, whether it was one agent or the full pipeline. */
public record RunResult(
        Report report,
        String rawText,
        StopReason stopReason,
        Set<String> resultUrls,
        Set<String> citedUrls,
        String stoppedBy,
        String debug,
        RunSummary.Totals totals,
        double elapsedSec) {

    public boolean isComplete() {
        return report != null && StopReason.END_TURN.equals(stopReason);
    }
}
