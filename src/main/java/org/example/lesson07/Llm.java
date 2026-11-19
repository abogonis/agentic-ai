package org.example.lesson07;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;

import java.util.stream.Collectors;

/** Minimal helper for the workflow lessons: one system prompt + one user prompt -> text. */
public final class Llm {
    // Tip: routing and worker steps are good candidates for a cheaper/faster model.
    static final String MODEL = "claude-opus-5-5";
    private static final AnthropicClient CLIENT = AnthropicOkHttpClient.fromEnv();

    private Llm() {}

    public static String ask(String system, String user) {
        Message response = CLIENT.messages().create(MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(1024L)
                .system(system)
                .addUserMessage(user)
                .build());
        return response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(text -> text.text())
                .collect(Collectors.joining())
                .trim();
    }
}
