package org.example.capstone;

import com.anthropic.models.messages.StopReason;

import java.util.Set;

/**
 * The outcome of one stage (planner, one researcher, or synthesizer): the typed value (null if the stage
 * produced nothing usable), the URLs its searches returned, why it stopped, a short log to print in order,
 * and a compact debug record (the exact request text and every response block) for the debug log file.
 */
public record StageResult<T>(
        T value,
        String rawText,
        Set<String> resultUrls,
        Set<String> citedUrls,
        StopReason stopReason,
        String stoppedBy,
        String log,
        String debug) {

    /** A stage is complete only if it finished normally and its JSON parsed (R4). */
    public boolean ok() {
        return value != null && StopReason.END_TURN.equals(stopReason);
    }
}
