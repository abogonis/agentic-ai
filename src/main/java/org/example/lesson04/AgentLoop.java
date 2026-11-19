package org.example.lesson04;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lesson 4: the agent loop.
 *
 * Concepts: an agent is just a loop. Call the model; if it asks for tools, run ALL of them,
 * send back ALL the results in ONE user message, and call again. Stop when it says end_turn.
 * A step limit protects you from runaway loops (and runaway bills).
 *
 * The weather tool is flaky: each city fails twice before succeeding. Watch Claude retry.
 * Try the question "Compare the weather in Paris and Tokyo" to see parallel tool calls.
 *
 * Run: mvn -q compile exec:java -Dexec.mainClass=org.example.lesson04.AgentLoop
 */
public class AgentLoop {

    static final int MAX_STEPS = 10;
    static final String QUESTION = "Compare the weather in Paris and Tokyo. Which one is warmer?";

    public static void main(String[] args) {
        AnthropicClient client = AnthropicOkHttpClient.fromEnv();

        Tool weatherTool = Tool.builder()
                .name("get_weather")
                .description("Get the current weather in a given city. "
                        + "The service is unreliable; if it returns an error, you may retry.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("location", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "City name, e.g. Paris")))
                                .build())
                        .required(List.of("location"))
                        .build())
                .build();

        MessageCreateParams.Builder conversation = MessageCreateParams.builder()
                .model("claude-opus-5-5")
                .maxTokens(4096L)
                .addTool(weatherTool)
                .addUserMessage(QUESTION);

        for (int step = 1; step <= MAX_STEPS; step++) {
            Message response = client.messages().create(conversation.build());
            System.out.println("\n--- step " + step + ": stop_reason = "
                    + response.stopReason().orElse(null) + " ---");

            // Always keep the full assistant turn (including tool_use and thinking blocks).
            conversation.addMessage(response);

            if (!response.stopReason().equals(java.util.Optional.of(StopReason.TOOL_USE))) {
                response.content().stream()
                        .flatMap(block -> block.text().stream())
                        .forEach(text -> System.out.println("\nclaude> " + text.text()));
                return;
            }

            // Run every requested tool and collect all results into a single user message.
            List<ContentBlockParam> results = new ArrayList<>();
            for (ToolUseBlock call : response.content().stream()
                    .flatMap(block -> block.toolUse().stream()).toList()) {
                Map<?, ?> input = call._input().convert(Map.class);
                String output;
                boolean isError;
                try {
                    output = runTool(call.name(), input);
                    isError = false;
                } catch (RuntimeException e) {
                    output = "Error: " + e.getMessage();
                    isError = true;
                }
                System.out.println("tool " + call.name() + " " + input
                        + (isError ? " -> ERROR " : " -> ok ") + output);
                results.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                        .toolUseId(call.id())
                        .content(output)
                        .isError(isError)
                        .build()));
            }
            conversation.addMessage(MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .contentOfBlockParams(results)
                    .build());
        }
        System.out.println("\nStopped: hit MAX_STEPS (" + MAX_STEPS + ") without a final answer.");
    }

    // --- Tools -------------------------------------------------------------------------

    static final Map<String, Integer> attempts = new HashMap<>();

    /** Dispatch by tool name. Unknown tools throw, which becomes an is_error result. */
    static String runTool(String name, Map<?, ?> input) {
        return switch (name) {
            case "get_weather" -> flakyWeather(String.valueOf(input.get("location")));
            default -> throw new IllegalArgumentException("Unknown tool: " + name);
        };
    }

    static String flakyWeather(String location) {
        int n = attempts.merge(location, 1, Integer::sum);
        if (n <= 2) {
            throw new IllegalStateException("weather service unavailable (HTTP 503), attempt " + n);
        }
        int temp = location.equalsIgnoreCase("Tokyo") ? 24 : 18;
        return "{\"location\": \"" + location + "\", \"temp_c\": " + temp + ", \"conditions\": \"partly cloudy\"}";
    }
}
