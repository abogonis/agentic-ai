package org.example.lesson06;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Lesson 6b: compaction, keeping long conversations under control.
 *
 * Without limits, history grows forever: cost rises every turn and eventually you hit the
 * context window. A simple strategy: when the history gets big, ask the model to summarize
 * the older part, then continue with [summary + recent turns].
 *
 * Trade-off: a summary is lossy. Details not in the summary are gone, so tell the
 * summarizer what must survive (names, decisions, open tasks).
 *
 * Watch "input tokens" climb, then drop right after a compaction.
 *
 * Run: mvn -q compile exec:java -Dexec.mainClass=org.example.lesson06.ManualCompaction
 */
public class ManualCompaction {

    static final String MODEL = "claude-opus-5-5";
    static final long COMPACT_WHEN_INPUT_TOKENS_ABOVE = 1_500;
    static final int KEEP_RECENT_MESSAGES = 2;

    record Turn(String role, String text) {}

    public static void main(String[] args) {
        AnthropicClient client = AnthropicOkHttpClient.fromEnv();
        List<Turn> history = new ArrayList<>();

        String[] userInputs = {
                "My name is Andrii and I'm learning to build AI agents in Java.",
                "Explain what a tool_use block is, in about 100 words.",
                "Explain what a tool_result block is, in about 100 words.",
                "Explain why agents need a step limit, in about 100 words.",
                "Explain prompt caching, in about 100 words.",
                "Explain human-in-the-loop approvals, in about 100 words.",
                "Quick check: what is my name and what am I learning?"
        };

        for (String input : userInputs) {
            history.add(new Turn("user", input));
            Message reply = client.messages().create(build(history));
            String text = reply.content().stream().flatMap(b -> b.text().stream())
                    .map(t -> t.text()).collect(Collectors.joining());
            history.add(new Turn("assistant", text));

            long inputTokens = reply.usage().inputTokens();
            System.out.println("you> " + input);
            System.out.println("claude> " + text.substring(0, Math.min(text.length(), 160)).replace('\n', ' ') + "...");
            System.out.println("   input tokens: " + inputTokens + "  messages: " + history.size());

            if (inputTokens > COMPACT_WHEN_INPUT_TOKENS_ABOVE && history.size() > KEEP_RECENT_MESSAGES + 2) {
                history = compact(client, history);
                System.out.println("   >>> compacted history to " + history.size() + " messages");
            }
            System.out.println();
        }
    }

    /** Replace everything except the most recent turns with one summary message. */
    static List<Turn> compact(AnthropicClient client, List<Turn> history) {
        int cut = history.size() - KEEP_RECENT_MESSAGES;
        // Keep the recent part starting on a user turn so roles keep alternating correctly.
        while (cut < history.size() && !history.get(cut).role().equals("user")) cut++;

        String transcript = history.subList(0, cut).stream()
                .map(t -> t.role() + ": " + t.text())
                .collect(Collectors.joining("\n\n"));

        Message summary = client.messages().create(MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(1024L)
                .system("You compress conversations. Write a dense summary that preserves: the user's "
                        + "name and goals, decisions made, facts they stated, and open questions. "
                        + "Omit pleasantries and anything that can be re-derived.")
                .addUserMessage("Summarize this conversation:\n\n" + transcript)
                .build());
        String summaryText = summary.content().stream().flatMap(b -> b.text().stream())
                .map(t -> t.text()).collect(Collectors.joining());

        List<Turn> compacted = new ArrayList<>();
        compacted.add(new Turn("user", "[Summary of the earlier conversation]\n" + summaryText));
        compacted.add(new Turn("assistant", "Understood, I'll continue from that context."));
        compacted.addAll(history.subList(cut, history.size()));
        return compacted;
    }

    static MessageCreateParams build(List<Turn> history) {
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(512L);
        for (Turn turn : history) {
            if (turn.role().equals("user")) builder.addUserMessage(turn.text());
            else builder.addAssistantMessage(turn.text());
        }
        return builder.build();
    }
}
