package org.example.capstone;

import com.anthropic.client.AnthropicClient;
import com.anthropic.errors.AnthropicException;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextCitation;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Runs one stage for one agent role: asks the budget how big the next call may be, sends it, charges the real
 * cost, and continues while the model answers pause_turn. An API failure or an exhausted budget ends the
 * stage with a StageResult that says why; nothing is thrown (R4, R5).
 */
public final class StageRunner {

    private static final ObjectMapper LOG_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .registerModule(new Jdk8Module())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private StageRunner() {}

    public static <T> StageResult<T> run(
            String name,
            AnthropicClient client,
            Budget budget,
            AgentBuilder<T> agent,
            ReportService parser,
            String user,
            long extraInputTokens,
            boolean verbose) {

        long startedAt = System.nanoTime();
        StringBuilder log = new StringBuilder();
        StringBuilder debug = new StringBuilder();
        debug.append("===== ").append(name).append(" (").append(agent.model()).append(") =====\n")
                .append("user message:\n").append(user).append("\n");
        List<Message> turns = new ArrayList<>();
        Set<String> resultUrls = new LinkedHashSet<>();
        Set<String> citedUrls = new LinkedHashSet<>();
        Message last = null;
        String stoppedBy = null;

        while (true) {
            long prior = extraInputTokens + (last == null ? 0 : last.usage().inputTokens());
            Optional<Budget.CallPlan> plan = budget.plan(agent.maxTokens(), agent.maxSearches(), prior);
            if (plan.isEmpty()) {
                stoppedBy = String.format("budget: no call fits in the remaining $%.4f / %d calls",
                        budget.remainingUsd(), budget.callsLeft());
                break;
            }
            log.append(String.format("[%s] call %d: maxTokens=%d maxSearches=%d, worst case $%.4f, remaining $%.4f%n",
                    name, budget.calls() + 1, plan.get().maxTokens(), plan.get().maxSearches(),
                    plan.get().estimatedCost(), budget.remainingUsd()));

            Message m;
            try {
                m = client.messages().create(agent.request(user, plan.get().maxTokens(), plan.get().maxSearches(), turns))
                        .rawMessage();
            } catch (AnthropicException e) {
                stoppedBy = "the API call failed: " + e.getMessage();
                debug.append("API error: ").append(e.getMessage()).append("\n");
                break;
            }

            budget.record(m.usage());
            logMessage(name, m, verbose, log);
            debugMessage(m, debug);
            collectUrls(m, resultUrls, citedUrls);
            last = m;

            if (!StopReason.PAUSE_TURN.equals(m.stopReason().orElse(null))) break;
            turns.add(m);
        }

        log.append(String.format("[%s] took %.1f s%n", name, (System.nanoTime() - startedAt) / 1e9));
        if (last == null) {
            debug.append("stopped: ").append(stoppedBy).append("\n");
            return new StageResult<>(null, "", resultUrls, citedUrls, null, stoppedBy, log.toString(), debug.toString());
        }
        String text = textOf(last);
        T value = parser.parse(text, agent.outputType());
        debug.append("parsed: ").append(value == null ? "NO (invalid or cut-off JSON)" : "yes").append("\n");
        if (stoppedBy != null) debug.append("stopped: ").append(stoppedBy).append("\n");
        return new StageResult<>(value, text, resultUrls, citedUrls,
                last.stopReason().orElse(null), stoppedBy, log.toString(), debug.toString());
    }

    private static String textOf(Message m) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : m.content()) {
            if (b.isText()) sb.append(b.asText().text());
        }
        return sb.toString();
    }

    /** Result URLs are what the search tool really returned (the R1 allow-list); cited URLs are inline citations. */
    private static void collectUrls(Message m, Set<String> resultUrls, Set<String> citedUrls) {
        for (ContentBlock b : m.content()) {
            if (b.isWebSearchToolResult()) {
                b.asWebSearchToolResult().content().resultBlocks().ifPresent(results ->
                        results.forEach(r -> resultUrls.add(ValidationService.normalizeUrl(r.url()))));
            } else if (b.isText()) {
                for (TextCitation c : b.asText().citations().orElse(List.of())) {
                    if (c.isWebSearchResultLocation()) {
                        citedUrls.add(ValidationService.normalizeUrl(c.asWebSearchResultLocation().url()));
                    }
                }
            }
        }
    }

    /** One response, compact: every block in order, without the encrypted search payloads. */
    private static void debugMessage(Message m, StringBuilder d) {
        d.append("--- response: stop_reason=").append(m.stopReason().orElse(null)).append(" ---\n");
        for (ContentBlock b : m.content()) {
            if (b.isThinking()) {
                d.append("[thinking block]\n");
            } else if (b.isServerToolUse()) {
                d.append("[search] ").append(b.asServerToolUse()._input()).append("\n");
            } else if (b.isWebSearchToolResult()) {
                b.asWebSearchToolResult().content().resultBlocks().ifPresentOrElse(
                        results -> results.forEach(r -> d.append("[result] ").append(r.title()).append(" -> ")
                                .append(r.url()).append("\n")),
                        () -> d.append("[search error] ").append(b.asWebSearchToolResult().content()).append("\n"));
            } else if (b.isText()) {
                d.append("[text]\n").append(b.asText().text()).append("\n");
            } else {
                d.append("[other block]\n");
            }
        }
        d.append("usage: in=").append(m.usage().inputTokens()).append(" out=").append(m.usage().outputTokens())
                .append(" cache_read=").append(m.usage().cacheReadInputTokens().orElse(0L))
                .append(" cache_write=").append(m.usage().cacheCreationInputTokens().orElse(0L))
                .append(" extra=").append(m.usage()._additionalProperties()).append("\n");
    }

    private static void logMessage(String name, Message m, boolean verbose, StringBuilder log) {
        if (verbose) {
            try {
                log.append("[").append(name).append("] entire response = ")
                        .append(LOG_MAPPER.writeValueAsString(m)).append("\n");
            } catch (Exception e) {
                log.append("[").append(name).append("] (could not serialize the response: ").append(e.getMessage()).append(")\n");
            }
        }
        for (ContentBlock b : m.content()) {
            if (b.isServerToolUse()) {
                log.append("[").append(name).append("] search: ").append(b.asServerToolUse()._input()).append("\n");
            } else if (b.isWebSearchToolResult()) {
                b.asWebSearchToolResult().content().resultBlocks().ifPresentOrElse(
                        results -> log.append("[").append(name).append("] ").append(results.size()).append(" results\n"),
                        () -> log.append("[").append(name).append("] search error: ")
                                .append(b.asWebSearchToolResult().content()).append("\n"));
            }
        }
        log.append(String.format("[%s] stop_reason=%s tokens in/out=%d/%d cache read/write=%d/%d%n", name,
                m.stopReason().orElse(null), m.usage().inputTokens(), m.usage().outputTokens(),
                m.usage().cacheReadInputTokens().orElse(0L), m.usage().cacheCreationInputTokens().orElse(0L)));
    }
}
