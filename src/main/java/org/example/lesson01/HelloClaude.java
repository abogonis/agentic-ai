package org.example.lesson01;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;

/**
 * Lesson 1: your first LLM API call.
 *
 * Concepts: client, model, max tokens, messages, response content blocks, token usage.
 * Run: mvn -q compile exec:java -Dexec.mainClass=org.example.lesson01.HelloClaude
 */
public class HelloClaude {
    public static void main(String[] args) {
        // Reads ANTHROPIC_API_KEY from the environment.
        AnthropicClient client = AnthropicOkHttpClient.fromEnv();

        MessageCreateParams params = MessageCreateParams.builder()
                .model("claude-opus-5-5")
                .maxTokens(1024L)
                .system("You are a concise tutor.")
                .addUserMessage("In three sentences, what is an AI agent?")
                .build();

        Message response = client.messages().create(params);

        // A response is a list of content blocks (text, tool_use, thinking, ...).
        response.content().stream()
                .flatMap(block -> block.text().stream())
                .forEach(text -> System.out.println(text.text()));

        System.out.println("\nstop_reason = " + response.stopReason().orElse(null));
        System.out.println("tokens in/out = " + response.usage().inputTokens()
                + " / " + response.usage().outputTokens());
    }
}
