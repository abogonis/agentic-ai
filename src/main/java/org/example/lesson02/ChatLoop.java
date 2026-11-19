package org.example.lesson02;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;

import java.util.Scanner;
import java.util.stream.Collectors;

/**
 * Lesson 2: multi-turn chat.
 *
 * Concepts: the API is stateless. Claude only "remembers" what you resend, so the
 * conversation history lives in YOUR code and grows with every turn.
 *
 * Experiments:
 *   1. Tell it your name, then ask "what's my name?" -> it knows (history resent).
 *   2. Set RESEND_HISTORY to false and repeat -> it has no idea (stateless).
 *   3. Watch "tokens in" grow each turn: you pay for the whole history every call.
 *
 * Run: mvn -q compile exec:java -Dexec.mainClass=org.example.lesson02.ChatLoop
 */
public class ChatLoop {
    static final boolean RESEND_HISTORY = false;

    public static void main(String[] args) {
        AnthropicClient client = AnthropicOkHttpClient.fromEnv();
        Scanner in = new Scanner(System.in);

        // The conversation state. Each turn we append the user message, call the API,
        // then append the assistant reply.
        MessageCreateParams.Builder conversation = MessageCreateParams.builder()
                .model("claude-opus-5-5")
                .maxTokens(1024L)
                .system("You are a friendly, concise assistant.");

        System.out.println("Chat with Claude. Type 'quit' to exit.");
        while (true) {
            System.out.print("\nyou> ");
            if (!in.hasNextLine()) break;
            String input = in.nextLine().trim();
            if (input.equalsIgnoreCase("quit")) break;
            if (input.isEmpty()) continue;

            conversation.addUserMessage(input);
            Message reply = client.messages().create(conversation.build());

            String text = reply.content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(t -> t.text())
                    .collect(Collectors.joining());
            System.out.println("claude> " + text);
            System.out.println("  [tokens in/out: " + reply.usage().inputTokens()
                    + " / " + reply.usage().outputTokens() + "]");

            if (RESEND_HISTORY) {
                conversation.addAssistantMessage(text);
            } else {
                // Stateless demo: drop the user message too, so every call is brand new.
                conversation = MessageCreateParams.builder()
                        .model("claude-opus-5-5")
                        .maxTokens(1024L)
                        .system("You are a friendly, concise assistant.");
            }
        }
    }
}
