package org.example.lesson09;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Tool;
import org.example.lesson07.Llm;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Lesson 9a: evals.
 *
 * "It worked when I tried it" is not evidence. An eval is a fixed set of test cases with an
 * automatic grader, so every prompt/model/tool change gets a number you can compare.
 *
 * Three grader types, from most to least trustworthy:
 *   1. Exact match (code)       classification: is the label correct?
 *   2. Behavior check (code)    did the agent call the right tool(s) with the right arguments?
 *   3. LLM-as-judge (a model)   open-ended quality; flexible, but the judge can be wrong, so
 *                               spot-check its verdicts against your own before trusting it
 *
 * Models are non-deterministic, so run each case several times and report a pass RATE.
 * Keep the cases fixed and add every real-world failure you find as a new case.
 *
 * Run: mvn -q compile exec:java -Dexec.mainClass=org.example.lesson09.Evals
 */
public class Evals {

    static final AnthropicClient CLIENT = AnthropicOkHttpClient.fromEnv();
    static final int RUNS_PER_CASE = 3;

    public static void main(String[] args) {
        classifierEval();
        toolUseEval();
        judgeEval();
    }

    // 1. Exact-match eval --------------------------------------------------------------------

    record LabeledMessage(String text, String expected) {}

    static void classifierEval() {
        header("1. Classifier eval (exact match)");
        List<LabeledMessage> cases = List.of(
                new LabeledMessage("I was charged twice this month.", "BILLING"),
                new LabeledMessage("Please send me a copy of my last invoice.", "BILLING"),
                new LabeledMessage("The app crashes whenever I upload a photo.", "TECHNICAL"),
                new LabeledMessage("I get a 500 error on the login page.", "TECHNICAL"),
                new LabeledMessage("Do you have an office in Berlin?", "OTHER"),
                new LabeledMessage("I can't log in and I was also charged.", "TECHNICAL"));  // ambiguous on purpose

        List<CompletableFuture<Boolean>> runs = cases.stream()
                .map(c -> CompletableFuture.supplyAsync(() -> {
                    String actual = Llm.ask("Classify the support message. Reply with exactly one word: "
                            + "BILLING, TECHNICAL or OTHER.", c.text()).toUpperCase().replaceAll("[^A-Z]", "");
                    boolean pass = actual.equals(c.expected());
                    System.out.println((pass ? "  PASS " : "  FAIL ") + c.text()
                            + "  expected=" + c.expected() + " actual=" + actual);
                    return pass;
                })).toList();
        report(runs.stream().map(CompletableFuture::join).toList());
    }

    // 2. Behavior eval: tool selection -------------------------------------------------------

    /** expectedCities empty = Claude should NOT call the tool. */
    record ToolCase(String prompt, Set<String> expectedCities) {}

    static void toolUseEval() {
        header("2. Tool-use eval (behavior check, " + RUNS_PER_CASE + " runs per case)");
        Tool weather = Tool.builder()
                .name("get_weather")
                .description("Get the current weather in a given city.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("location", JsonValue.from(Map.of(
                                        "type", "string", "description", "City name")))
                                .build())
                        .required(List.of("location")).build())
                .build();

        List<ToolCase> cases = List.of(
                new ToolCase("What's the weather in Paris?", Set.of("paris")),
                new ToolCase("Is it raining in Tokyo and Rome right now?", Set.of("tokyo", "rome")),
                new ToolCase("What is 17 times 3?", Set.of()),
                new ToolCase("Write a haiku about autumn.", Set.of()),
                new ToolCase("Should I bring an umbrella to Berlin today?", Set.of("berlin")));

        List<CompletableFuture<Boolean>> runs = cases.stream()
                .flatMap(c -> java.util.stream.IntStream.range(0, RUNS_PER_CASE).mapToObj(i -> c))
                .map(c -> CompletableFuture.supplyAsync(() -> {
                    Message first = CLIENT.messages().create(MessageCreateParams.builder()
                            .model("claude-opus-5-5").maxTokens(1024L)
                            .addTool(weather).addUserMessage(c.prompt()).build());
                    Set<String> actual = first.content().stream()
                            .flatMap(b -> b.toolUse().stream())
                            .map(t -> String.valueOf(t._input().convert(Map.class).get("location"))
                                    .toLowerCase(Locale.ROOT))
                            .collect(Collectors.toSet());
                    boolean pass = actual.equals(c.expectedCities());
                    if (!pass) System.out.println("  FAIL \"" + c.prompt() + "\" expected="
                            + c.expectedCities() + " actual=" + actual);
                    return pass;
                })).toList();
        report(runs.stream().map(CompletableFuture::join).toList());
    }

    // 3. LLM-as-judge ------------------------------------------------------------------------

    static void judgeEval() {
        header("3. LLM-as-judge eval");
        String rubric = "PASS only if the answer (a) is factually correct, (b) is at most 2 sentences, "
                + "and (c) uses no jargon a beginner wouldn't know. Reply 'PASS' or 'FAIL' then one short reason.";
        List<String> questions = List.of(
                "What is an API?", "Why is the sky blue?", "What does a database index do?");

        List<CompletableFuture<Boolean>> runs = questions.stream()
                .map(q -> CompletableFuture.supplyAsync(() -> {
                    String answer = Llm.ask("Answer concisely.", q);
                    String verdict = Llm.ask("You are a strict grader. " + rubric,
                            "Question: " + q + "\n\nAnswer: " + answer);
                    boolean pass = verdict.toUpperCase().startsWith("PASS");
                    System.out.println((pass ? "  PASS " : "  FAIL ") + q + "\n       judge: " + verdict);
                    return pass;
                })).toList();
        report(runs.stream().map(CompletableFuture::join).toList());
        System.out.println("  (Read the judge's reasons. If you disagree with some, fix the rubric first.)");
    }

    // ---------------------------------------------------------------------------------------

    static void report(List<Boolean> results) {
        long passed = results.stream().filter(b -> b).count();
        System.out.printf("  => %d/%d passed (%.0f%%)%n", passed, results.size(), 100.0 * passed / results.size());
    }

    static void header(String title) {
        System.out.println("\n==== " + title + " ====");
    }
}
