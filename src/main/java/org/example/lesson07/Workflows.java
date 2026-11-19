package org.example.lesson07;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Lesson 7: workflow patterns.
 *
 * A workflow = YOUR code decides the control flow; the model fills in each step.
 * An agent = the MODEL decides the control flow. Prefer workflows when the steps are known:
 * they are cheaper, faster, easier to test and more predictable.
 *
 *   1. Prompt chaining        step A's output feeds step B (with a gate in between)
 *   2. Routing                classify the input, then send it to a specialised prompt
 *   3. Parallelization        independent calls run at the same time, results are combined
 *   4. Orchestrator-workers   a model plans subtasks, workers do them, a final call merges
 *
 * Run: mvn -q compile exec:java -Dexec.mainClass=org.example.lesson07.Workflows
 */
public class Workflows {

    public static void main(String[] args) {
//        chain();
//        route("My invoice shows a charge twice and I want my money back.");
//        route("The app crashes with a NullPointerException when I tap Save.");
        parallelize("def total(items): return sum(i.price for i in items)  # no error handling");
//        orchestrate("Should a small team adopt microservices?");
    }

    // 1. Prompt chaining -------------------------------------------------------------------

    static void chain() {
        header("1. Prompt chaining");
        String outline = Llm.ask("You write terse outlines.",
                "Outline a 3-point blog post on why AI agents need step limits. Bullets only.");
        System.out.println("outline:\n" + outline + "\n");

        // Gate: plain Java code checks the intermediate result before spending more tokens.
        long bullets = outline.lines().filter(l -> l.trim().matches("^[-*•\\d].*")).count();
        if (bullets < 3) {
            System.out.println("Gate failed: outline too short, stopping.");
            return;
        }
        String post = Llm.ask("You are a clear technical writer. Under 120 words.",
                "Write the post from this outline:\n" + outline);
        System.out.println("post:\n" + post + "\n");
    }

    // 2. Routing ---------------------------------------------------------------------------

    static void route(String message) {
        header("2. Routing: \"" + message + "\"");
        // The classifier returns ONE word that our code switches on.
        String category = Llm.ask(
                "Classify the support message. Reply with exactly one word: BILLING, TECHNICAL or OTHER.",
                message).toUpperCase();
        System.out.println("category: " + category);

        String specialist = switch (category.replaceAll("[^A-Z]", "")) {
            case "BILLING" -> "You are a billing specialist. Be empathetic, mention refund timelines.";
            case "TECHNICAL" -> "You are a senior engineer. Ask for logs and give one concrete next step.";
            default -> "You are a friendly general support agent.";
        };
        System.out.println("reply: " + Llm.ask(specialist + " Max 3 sentences.", message) + "\n");
    }

    // 3. Parallelization -------------------------------------------------------------------

    static void parallelize(String code) {
        header("3. Parallelization (3 reviewers at once)");
        List<String> aspects = List.of("security", "readability", "performance");
        long start = System.currentTimeMillis();

        List<CompletableFuture<String>> futures = aspects.stream()
                .map(aspect -> CompletableFuture.supplyAsync(() -> aspect + ": " + Llm.ask(
                        "You review code for " + aspect + " only. One sentence.", code)))
                .toList();
        List<String> reviews = futures.stream().map(CompletableFuture::join).toList();

        reviews.forEach(System.out::println);
        System.out.println("(took " + (System.currentTimeMillis() - start) + " ms; "
                + "sequentially it would be roughly 3x longer)\n");

        long start1 = System.currentTimeMillis();

        List<String> reviews1 = new ArrayList<>();
        for(String aspect: aspects){
            reviews1.add(Llm.ask("You review code for " + aspect + " only. One sentence.", code));
        }

        reviews1.forEach(System.out::println);
        System.out.println("(took " + (System.currentTimeMillis() - start1) + " ms; ");
    }

    // 4. Orchestrator-workers --------------------------------------------------------------

    static void orchestrate(String question) {
        header("4. Orchestrator-workers: " + question);
        // The orchestrator decides the subtasks at runtime, so the number of workers varies.
        String plan = Llm.ask("You break questions into 3 independent research angles. "
                + "Reply with one angle per line, no numbering, no extra text.", question);
        List<String> angles = Arrays.stream(plan.split("\n")).map(String::trim)
                .filter(s -> !s.isEmpty()).limit(5).toList();
        System.out.println("angles: " + angles);

        List<String> findings = angles.stream()
                .map(angle -> CompletableFuture.supplyAsync(() -> Llm.ask(
                        "You are a focused researcher. Answer in 2 sentences.", angle)))
                .toList().stream().map(CompletableFuture::join).toList();

        String joined = java.util.stream.IntStream.range(0, angles.size())
                .mapToObj(i -> "Angle: " + angles.get(i) + "\nFinding: " + findings.get(i))
                .collect(Collectors.joining("\n\n"));
        System.out.println("\nfinal:\n" + Llm.ask(
                "Synthesize the findings into a balanced 3-sentence recommendation.",
                "Question: " + question + "\n\n" + joined));
    }

    static void header(String title) {
        System.out.println("\n==== " + title + " ====");
    }
}
