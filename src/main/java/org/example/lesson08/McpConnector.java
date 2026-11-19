package org.example.lesson08;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.beta.messages.BetaMcpToolset;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaRequestMcpServerUrlDefinition;
import com.anthropic.models.beta.messages.MessageCreateParams;

/**
 * Lesson 8a: MCP (Model Context Protocol) via the API's MCP connector.
 *
 * MCP is a standard way for tool providers to expose tools to any AI app. Instead of writing
 * a tool schema and handler yourself (lessons 3-5), you point at a server and Claude
 * discovers its tools.
 *
 * With the MCP connector, ANTHROPIC's servers talk to the MCP server for you, so:
 *   - the server must be a public HTTPS URL (not localhost)
 *   - there is no loop for you to write: tool calls and results come back inside ONE response
 *     as mcp_tool_use / mcp_tool_result blocks
 * (To use local/stdio MCP servers you would run an MCP client in your own process instead.)
 *
 * DeepWiki's public server (no auth) answers questions about GitHub repos. If the default
 * URL no longer works, pass another public MCP server URL as the first argument.
 *
 * Run: mvn -q compile exec:java -Dexec.mainClass=org.example.lesson08.McpConnector
 */
public class McpConnector {

    public static void main(String[] args) {
        String url = args.length > 0 ? args[0] : "https://mcp.deepwiki.com/mcp";
        AnthropicClient client = AnthropicOkHttpClient.fromEnv();

        MessageCreateParams params = MessageCreateParams.builder()
                .model("claude-opus-5-5")
                .maxTokens(2048L)
                .addBeta("mcp-client-2025-11-20")
                // Half 1: where the server is.
                .addMcpServer(BetaRequestMcpServerUrlDefinition.builder()
                        .name("docs")
                        .url(url)
                        .build())
                // Half 2: which of its tools Claude may use. Both are required.
                .addTool(BetaMcpToolset.builder().mcpServerName("docs").build())
                .addUserMessage("Using the docs server, what is the anthropics/anthropic-sdk-java "
                        + "repository and what does it provide? Answer in 3 sentences.")
                .build();

        BetaMessage response = client.beta().messages().create(params);

        response.content().forEach(block -> {
            block.mcpToolUse().ifPresent(use -> System.out.println(
                    "[mcp call] " + use.serverName() + "/" + use.name() + " " + use._input()));
            block.mcpToolResult().ifPresent(result -> System.out.println(
                    "[mcp result] received (" + result.toString().length() + " chars)"));
            block.text().ifPresent(text -> System.out.println("\nclaude> " + text.text()));
        });
        System.out.println("\nstop_reason = " + response.stopReason().orElse(null));
    }
}
