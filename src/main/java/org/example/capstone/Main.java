package org.example.capstone;

import com.anthropic.client.AnthropicClient;

import java.io.IOException;
import java.nio.file.Path;

/**
 * How to run it:
 *   mvn -q compile exec:java -Dexec.mainClass=org.example.capstone.Main -Dexec.args="Should a small team adopt microservices?"
 * Options (JVM -D flags):
 *   -Dcapstone.maxCostUsd=0.50   total cost cap
 *   -Dcapstone.mode=pipeline     planner + 3 researchers + synthesizer (default); "single" = one agent
 *   -Dcapstone.verbose=true      print the entire API responses as JSON
 */
public class Main {

    public static void main(String[] args) throws IOException {

        String question = String.join(" ", args).trim();
        if (question.isEmpty()) {
            System.err.println("Usage: Main \"<research question>\"");
            System.exit(1);
        }
        System.out.println("Question = " + question);

        double cap = Double.parseDouble(System.getProperty("capstone.maxCostUsd", String.valueOf(Budget.DEFAULT_MAX_COST_USD)));
        String mode = System.getProperty("capstone.mode", "pipeline");
        boolean verbose = Boolean.getBoolean("capstone.verbose");

        ReportService reportService = new ReportService();
        ValidationService validationService = new ValidationService();

        // Reads ANTHROPIC_API_KEY from the environment.
        AnthropicClient client = Clients.fromEnv();

        RunResult result = mode.equals("single")
                ? SingleAgentRunner.run(client, reportService, question, cap, verbose)
                : new ResearchPipeline(client, reportService, verbose).run(question, cap);

        String outcome = report(question, result, reportService, validationService);
        writeDebugLog(question, result, outcome);
    }

    /** Always written: the exact requests, every response block, removals and the final raw text. */
    private static void writeDebugLog(String question, RunResult result, String outcome) throws IOException {
        String stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path file = Path.of("logs", stamp + "-" + ReportService.slug(question) + ".log");
        java.nio.file.Files.createDirectories(file.getParent());
        String text = "question: " + question + "\n\n" + result.debug()
                + "\n===== final =====\nstop_reason=" + result.stopReason() + " stoppedBy=" + result.stoppedBy() + "\n"
                + "outcome: " + outcome + "\n"
                + "result URLs (" + result.resultUrls().size() + "):\n" + String.join("\n", result.resultUrls()) + "\n";
        java.nio.file.Files.writeString(file, text, java.nio.charset.StandardCharsets.UTF_8);
        System.out.println("debug log = " + file);
    }

    /** Prints the brief and the checks; returns a one-line outcome for the debug log. */
    private static String report(String question, RunResult result, ReportService reportService,
                               ValidationService validationService) throws IOException {
        if (result.stoppedBy() != null) System.out.println("\nRun stopped early: " + result.stoppedBy());

        if (result.report() == null) {
            System.out.println("\nINCOMPLETE (stop_reason=" + result.stopReason() + "): no valid report was produced.");
            String raw = result.rawText();
            System.out.println(raw == null || raw.isBlank() ? "(no text was produced before the run stopped)" : raw);
            System.out.println("\nstop_reason = " + result.stopReason());
            return "no valid report";
        }

        boolean incomplete = !result.isComplete();
        String markdown = reportService.render(result.report(), incomplete, result.stopReason());
        ValidationService.ValidationResult validation = validationService.validate(
                markdown, result.report(), result.resultUrls(), result.citedUrls());

        System.out.println("\nwords = " + validation.words());
        System.out.println("\nbrief =\n" + markdown);

        validation.violations().forEach(System.err::println);
        if (!validation.isValid()) {
            String raw = result.rawText() == null ? "" : result.rawText();
            System.out.println("raw model output (" + raw.length() + " chars) = "
                    + (raw.length() > 1500 ? raw.substring(0, 1500) + "..." : raw));
        }
        System.out.println("R1 check: " + validation.resultUrlCount() + " result URLs, "
                + validation.reportUrlCount() + " distinct URLs in the report, "
                + validation.citedUrlCount() + " distinct cited URLs, "
                + validation.inventedUrlCount() + " invented");

        if (incomplete) {
            System.out.println("Not saved: the run is incomplete.");
        } else if (!validation.isValid()) {
            System.out.println("Not saved: the brief breaks " + validation.violations().size() + " rule(s), see above.");
        } else {
            Path file = reportService.save(question, markdown);
            System.out.println("saved = " + file);
        }
        System.out.println("\nstop_reason = " + result.stopReason());
        return incomplete ? "incomplete, not saved"
                : validation.isValid() ? "saved, no violations" : "not saved, violations: " + validation.violations();
    }
}
