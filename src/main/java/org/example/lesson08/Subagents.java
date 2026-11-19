package org.example.lesson08;

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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Lesson 8b: subagents.
 *
 * A subagent is just ANOTHER agent loop, started by a tool call. It gets its own fresh
 * conversation, its own system prompt and its own tools. When it finishes, only its final
 * answer goes back to the parent as the tool result.
 *
 * Why: the subagent can read many files and make many tool calls, and none of that bulk
 * enters the parent's context. The parent stays small, cheap, and focused.
 *
 *   parent agent --ask_explorer("how do the lessons work?")--> explorer subagent
 *                                                               (list_dir, read_file x N)
 *   parent agent <-------------- short final answer ---------- explorer subagent
 *
 * Compare the printed token totals: the explorer reads far more than the parent ever sees.
 *
 * Run: mvn -q compile exec:java -Dexec.mainClass=org.example.lesson08.Subagents
 */
public class Subagents {

    static final String MODEL = "claude-opus-5-5";
    static final Path ROOT = Path.of(".").toAbsolutePath().normalize();
    static final AnthropicClient CLIENT = AnthropicOkHttpClient.fromEnv();

    public static void main(String[] args) {
        // The parent's only tool is "delegate to the explorer".
        Tool askExplorer = Tool.builder()
                .name("ask_explorer")
                .description("Delegate a question about the project's files to an explorer agent that "
                        + "can list and read files. It returns a concise answer. Be specific.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("question", JsonValue.from(Map.of(
                                        "type", "string", "description", "What to find out")))
                                .build())
                        .required(List.of("question"))
                        .build())
                .build();

        String answer = runAgent(
                "parent",
                "You answer questions about a Java project. You cannot see files yourself; "
                        + "use ask_explorer. Keep the final answer under 120 words.",
                "Which lessons exist in this project, and what does each one teach?",
                List.of(askExplorer),
                (call) -> {
                    String question = String.valueOf(call._input().convert(Map.class).get("question"));
                    System.out.println("  parent -> explorer: " + question);
                    return runAgent("explorer",
                            "You explore a project directory (read-only) and report findings. "
                                    + "Be efficient: read only what you need. Final answer under 150 words.",
                            question,
                            List.of(fileTool("list_dir", "List a directory ('.' = project root)."),
                                    fileTool("read_file", "Read a text file.")),
                            Subagents::runFileTool);
                });

        System.out.println("\n=== final answer ===\n" + answer);
    }

    /** A generic agent loop. 'handler' executes tool calls; subagents are just handlers that recurse. */
    static String runAgent(String name, String system, String task, List<Tool> tools,
                           Function<ToolUseBlock, String> handler) {
        MessageCreateParams.Builder conversation = MessageCreateParams.builder()
                .model(MODEL).maxTokens(4096L).system(system).addUserMessage(task);
        tools.forEach(conversation::addTool);

        long totalIn = 0, totalOut = 0;
        for (int step = 1; step <= 12; step++) {
            Message response = CLIENT.messages().create(conversation.build());
            totalIn += response.usage().inputTokens();
            totalOut += response.usage().outputTokens();
            conversation.addMessage(response);

            if (!response.stopReason().equals(Optional.of(StopReason.TOOL_USE))) {
                System.out.println("  [" + name + "] finished in " + step + " steps; total tokens in="
                        + totalIn + " out=" + totalOut);
                return response.content().stream().flatMap(b -> b.text().stream())
                        .map(t -> t.text()).reduce("", String::concat);
            }
            List<ContentBlockParam> results = new ArrayList<>();
            for (ToolUseBlock call : response.content().stream()
                    .flatMap(b -> b.toolUse().stream()).toList()) {
                String output;
                boolean isError = false;
                try {
                    output = handler.apply(call);
                } catch (Exception e) {
                    output = "Error: " + e.getMessage();
                    isError = true;
                }
                results.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                        .toolUseId(call.id()).content(output).isError(isError).build()));
            }
            conversation.addMessage(MessageParam.builder().role(MessageParam.Role.USER)
                    .contentOfBlockParams(results).build());
        }
        return "[" + name + " hit its step limit]";
    }

    // --- Explorer tools (read-only, confined to the project root) ----------------------------

    static Tool fileTool(String name, String description) {
        return Tool.builder().name(name).description(description)
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("path", JsonValue.from(Map.of(
                                        "type", "string", "description", "Relative path")))
                                .build())
                        .required(List.of("path")).build())
                .build();
    }

    static String runFileTool(ToolUseBlock call) {
        try {
            Path path = ROOT.resolve(String.valueOf(call._input().convert(Map.class).get("path"))).normalize();
            if (!path.startsWith(ROOT)) throw new SecurityException("Path escapes the project");
            String output = switch (call.name()) {
                case "list_dir" -> {
                    try (Stream<Path> files = Files.list(path)) {
                        yield files.map(p -> p.getFileName() + (Files.isDirectory(p) ? "/" : ""))
                                .filter(n -> !n.startsWith(".") && !n.equals("target/"))
                                .sorted().reduce("", (a, b) -> a + b + "\n");
                    }
                }
                case "read_file" -> Files.readString(path);
                default -> throw new IllegalArgumentException("Unknown tool " + call.name());
            };
            System.out.println("    explorer: " + call.name() + " " + ROOT.relativize(path)
                    + " (" + output.length() + " chars)");
            return output.length() > 20_000 ? output.substring(0, 20_000) + "\n...[truncated]" : output;
        } catch (IOException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }
}
