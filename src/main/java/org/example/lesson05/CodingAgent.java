package org.example.lesson05;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Lesson 5: a coding-style agent with file and shell tools.
 *
 * Same loop as lesson 4, but the tools are now dangerous, so we add guardrails:
 *   1. Sandbox: file paths are resolved inside ./sandbox and may not escape it.
 *   2. Permissions: read-only tools run freely; write_file and run_shell ask YOU first.
 *      A denial is returned to Claude as an error result so it can adapt.
 *   3. Limits: shell timeout, output truncation, and a step limit.
 *
 * NOTE: approval prompts are a human-in-the-loop check, not a real security boundary.
 * A shell command can still do anything your user can. For untrusted tasks use a container.
 *
 * Run: mvn -q compile exec:java -Dexec.mainClass=org.example.lesson05.CodingAgent
 * (or pass your own task: -Dexec.args="your task here")
 */
public class CodingAgent {

    static final int MAX_STEPS = 20;
    static final int MAX_OUTPUT_CHARS = 8_000;
    static final long SHELL_TIMEOUT_SECONDS = 30;
    static final String DEFAULT_TASK =
            "Create Hello.java that prints the first 10 Fibonacci numbers, then compile and run it.";

    static final Path SANDBOX = Path.of("sandbox").toAbsolutePath().normalize();
    static final Scanner STDIN = new Scanner(System.in);

    public static void main(String[] args) throws IOException {
        Files.createDirectories(SANDBOX);
        String task = args.length > 0 ? String.join(" ", args) : DEFAULT_TASK;
        System.out.println("Sandbox: " + SANDBOX + "\nTask: " + task);

        AnthropicClient client = AnthropicOkHttpClient.fromEnv();

        MessageCreateParams.Builder conversation = MessageCreateParams.builder()
                .model("claude-opus-5-5")
                .maxTokens(8192L)
                .system("You are a coding agent working in a sandbox directory. All paths are relative "
                        + "to it. Inspect before you change things, verify your work by running it, "
                        + "and keep your final answer short.")
                .addTool(tool("list_dir", "List files in a directory (use '.' for the sandbox root).",
                        Map.of("path", "Directory path"), List.of("path")))
                .addTool(tool("read_file", "Read a text file.",
                        Map.of("path", "File path"), List.of("path")))
                .addTool(tool("write_file", "Create or overwrite a text file. Requires user approval.",
                        orderedMap("path", "File path", "content", "Full file contents"),
                        List.of("path", "content")))
                .addTool(tool("run_shell", "Run a shell command in the sandbox directory. "
                                + "Requires user approval.",
                        Map.of("command", "The command to run"), List.of("command")))
                .addUserMessage(task);

        for (int step = 1; step <= MAX_STEPS; step++) {
            Message response = client.messages().create(conversation.build());
            conversation.addMessage(response);

            if (!response.stopReason().equals(Optional.of(StopReason.TOOL_USE))) {
                System.out.println("\n=== done (" + response.stopReason().orElse(null) + ") ===");
                response.content().stream()
                        .flatMap(block -> block.text().stream())
                        .forEach(text -> System.out.println(text.text()));
                return;
            }

            List<ContentBlockParam> results = new ArrayList<>();
            for (ToolUseBlock call : response.content().stream()
                    .flatMap(block -> block.toolUse().stream()).toList()) {
                Map<?, ?> input = call._input().convert(Map.class);
                String output;
                boolean isError = false;
                try {
                    output = runTool(call.name(), input);
                } catch (Exception e) {
                    output = "Error: " + e.getMessage();
                    isError = true;
                }
                System.out.println("  [" + call.name() + "] " + (isError ? "ERROR " : "ok ")
                        + abbreviate(output, 120));
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
        System.out.println("\nStopped: hit MAX_STEPS (" + MAX_STEPS + ").");
    }

    // --- Tool implementations ----------------------------------------------------------

    static String runTool(String name, Map<?, ?> in) throws Exception {
        return switch (name) {
            case "list_dir" -> listDir(str(in, "path"));
            case "read_file" -> truncate(Files.readString(safePath(str(in, "path"))));
            case "write_file" -> writeFile(str(in, "path"), str(in, "content"));
            case "run_shell" -> runShell(str(in, "command"));
            default -> throw new IllegalArgumentException("Unknown tool: " + name);
        };
    }

    static String listDir(String path) throws IOException {
        try (Stream<Path> files = Files.list(safePath(path))) {
            return files.map(p -> p.getFileName() + (Files.isDirectory(p) ? "/" : ""))
                    .sorted().reduce("", (a, b) -> a + b + "\n");
        }
    }

    static String writeFile(String path, String content) throws IOException {
        Path target = safePath(path);
        requireApproval("WRITE " + path + " (" + content.length() + " chars)\n" + abbreviate(content, 400));
        if (target.getParent() != null) Files.createDirectories(target.getParent());
        Files.writeString(target, content);
        return "Wrote " + content.length() + " chars to " + path;
    }

    static String runShell(String command) throws Exception {
        requireApproval("RUN   " + command);
        Process process = new ProcessBuilder("/bin/sh", "-c", command)
                .directory(SANDBOX.toFile())
                .redirectErrorStream(true)
                .start();
        if (!process.waitFor(SHELL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Command timed out after " + SHELL_TIMEOUT_SECONDS + "s");
        }
        String output = new String(process.getInputStream().readAllBytes());
        return "exit code " + process.exitValue() + "\n" + truncate(output);
    }

    // --- Guardrails --------------------------------------------------------------------

    /** Resolve a path inside the sandbox; reject anything that escapes it (../, absolute paths). */
    static Path safePath(String relative) {
        Path resolved = SANDBOX.resolve(relative).normalize();
        if (!resolved.startsWith(SANDBOX)) {
            throw new SecurityException("Path escapes the sandbox: " + relative);
        }
        return resolved;
    }

    /** Human-in-the-loop gate. Throwing turns a denial into an error result for Claude. */
    static void requireApproval(String description) {
        System.out.println("\n  >>> Claude wants to:\n" + indent(description));
        System.out.print("  Allow? [y/N] ");
        String answer = STDIN.hasNextLine() ? STDIN.nextLine().trim() : "";
        if (!answer.equalsIgnoreCase("y")) {
            throw new SecurityException("The user denied this action. Do not retry it unchanged; "
                    + "explain what you need or try another approach.");
        }
    }

    // --- Helpers -----------------------------------------------------------------------

    static Tool tool(String name, String description, Map<String, String> props, List<String> required) {
        Tool.InputSchema.Properties.Builder properties = Tool.InputSchema.Properties.builder();
        props.forEach((prop, desc) -> properties.putAdditionalProperty(prop,
                JsonValue.from(Map.of("type", "string", "description", desc))));
        return Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(Tool.InputSchema.builder().properties(properties.build()).required(required).build())
                .build();
    }

    static Map<String, String> orderedMap(String k1, String v1, String k2, String v2) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put(k1, v1);
        map.put(k2, v2);
        return map;
    }

    static String str(Map<?, ?> input, String key) {
        Object value = input.get(key);
        if (value == null) throw new IllegalArgumentException("Missing required argument: " + key);
        return String.valueOf(value);
    }

    static String truncate(String text) {
        return text.length() <= MAX_OUTPUT_CHARS ? text
                : text.substring(0, MAX_OUTPUT_CHARS) + "\n...[truncated " + (text.length() - MAX_OUTPUT_CHARS) + " chars]";
    }

    static String abbreviate(String text, int max) {
        String oneLine = text.replace('\n', ' ');
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "...";
    }

    static String indent(String text) {
        return "      " + text.replace("\n", "\n      ");
    }
}
