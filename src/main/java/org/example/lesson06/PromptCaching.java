package org.example.lesson06;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlockParam;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Lesson 6a: prompt caching.
 *
 * Every call resends the whole prefix (tools + system prompt + history). Caching lets the
 * API reuse a prefix it has already processed, at a fraction of the input price.
 *
 * Rules of thumb:
 *   - It is a PREFIX match: any change before the cache breakpoint invalidates the cache.
 *     Put stable content first (system prompt, tools), volatile content last.
 *   - There is a minimum cacheable size (roughly 1-4K tokens depending on model). That is
 *     why this demo builds a big system prompt: a short one would silently not cache.
 *   - Verify with usage: cacheCreationInputTokens (written) and cacheReadInputTokens (hit).
 *
 * Expected: call 1 writes the cache, calls 2 and 3 read it.
 *
 * Run: mvn -q compile exec:java -Dexec.mainClass=org.example.lesson06.PromptCaching
 */
public class PromptCaching {

    public static void main(String[] args) {
        AnthropicClient client = AnthropicOkHttpClient.fromEnv();

        String bigSystemPrompt = "You are a support assistant for ACME Corp. Answer in one short sentence. "
                + "Company policy handbook:\n"
                + IntStream.rangeClosed(1, 700)
                .mapToObj(i -> "Policy " + i + ": for request category " + i
                        + ", staff must log the case, notify the team lead, and follow up within " + (i % 9 + 1)
                        + " business days.")
                .collect(Collectors.joining("\n"));

        String[] questions = {
                "What must staff do for request category 12?",
                "How many days to follow up for category 40?",
                "Who must be notified for category 300?"
        };

        for (String question : questions) {
            MessageCreateParams params = MessageCreateParams.builder()
                    .model("claude-opus-5-5")
                    .maxTokens(256L)
                    // The breakpoint: everything up to and including this block is cacheable.
                    .systemOfTextBlockParams(List.of(TextBlockParam.builder()
                            .text(bigSystemPrompt)
                            .cacheControl(CacheControlEphemeral.builder().build())
                            .build()))
                    .addUserMessage(question)
                    .build();

            Message response = client.messages().create(params);
            var usage = response.usage();
            System.out.println("Q: " + question);
            response.content().stream().flatMap(b -> b.text().stream())
                    .forEach(t -> System.out.println("A: " + t.text()));
            System.out.println("   uncached in=" + usage.inputTokens()
                    + "  cache written=" + usage.cacheCreationInputTokens().orElse(0L)
                    + "  cache read=" + usage.cacheReadInputTokens().orElse(0L)
                    + "  out=" + usage.outputTokens() + "\n");
        }
    }
}
