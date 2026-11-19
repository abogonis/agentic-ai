package org.example.lesson03;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Lesson 3: tool use, one round trip.
 *
 * Concepts: Claude never runs your code. It returns a tool_use block saying "please call
 * get_weather with {location: Paris}". YOUR code runs it and sends back a tool_result.
 *
 *   user question -> Claude (stop_reason = tool_use) -> you run the tool
 *                 -> you send tool_result -> Claude (stop_reason = end_turn) -> final answer
 *
 * Run: mvn -q compile exec:java -Dexec.mainClass=org.example.lesson03.ToolRoundTrip
 */
public class ToolRoundTrip {

    /** Experiment: make the tool fail and watch how Claude reports it. */
    static final boolean SIMULATE_ERROR = true;

    public static void main(String[] args) {
        AnthropicClient client = AnthropicOkHttpClient.fromEnv();

        // 1. Describe the tool. The description is the most important part: it is how
        //    Claude decides WHEN to use the tool.
        Tool weatherTool = Tool.builder()
                .name("get_weather")
                .description("Get the current weather in a given city.")
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
                .maxTokens(1024L)
                .addTool(weatherTool)
                .addUserMessage("What's the weather like in Paris right now?");

        // 2. First call: Claude decides it needs the tool.
        Message first = client.messages().create(conversation.build());
        System.out.println("first response = " + first);
        System.out.println("stop_reason #1 = " + first.stopReason().orElse(null));

        Optional<ToolUseBlock> toolUse = first.content().stream()
                .flatMap(block -> block.toolUse().stream())
                .findFirst();

        if (toolUse.isEmpty()) {
            System.out.println("Claude answered directly, no tool needed.");
            return;
        }
        ToolUseBlock call = toolUse.get();
        System.out.println("Claude wants: " + call.name() + " " + call._input());

        // 3. Run the tool ourselves. Here it is fake; in a real agent this is an API call,
        //    a database query, a file read, etc.
        Map<?, ?> input = call._input().convert(Map.class);
        String location = String.valueOf(input.get("location"));
        String result = fakeWeather(location);
        System.out.println((SIMULATE_ERROR ? "Tool error: " : "Tool result: ") + result);

        // 4. Send back the assistant turn (with its tool_use block) and our tool_result.
        conversation.addMessage(first);
        conversation.addMessage(MessageParam.builder()
                .role(MessageParam.Role.USER)
                .contentOfBlockParams(List.of(ContentBlockParam.ofToolResult(
                        ToolResultBlockParam.builder()
                                .toolUseId(call.id())
                                .content(result)
                                // Tells Claude the tool failed, so it can explain, retry or adapt.
                                .isError(SIMULATE_ERROR)
                                .build())))
                .build());

        // 5. Second call: Claude turns the raw tool output into a final answer.
        Message second = client.messages().create(conversation.build());
        System.out.println("Second response = " + second);
        System.out.println("stop_reason #2 = " + second.stopReason().orElse(null));
        second.content().stream()
                .flatMap(block -> block.text().stream())
                .forEach(text -> System.out.println("\nclaude> " + text.text()));
    }

    static String fakeWeather(String location) {
        if (SIMULATE_ERROR) {
            return "Error: weather service unavailable (HTTP 503). Please try again later.";
        }
        return "{\"location\": \"" + location + "\", \"temp_c\": 18, \"conditions\": \"light rain\"}";
    }
}
