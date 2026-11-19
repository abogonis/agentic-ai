package org.example.capstone;

import com.anthropic.client.AnthropicClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Step 7: runs every eval question through the baseline (one agent) and the pipeline (planner, researchers,
 * synthesizer), one after another, grades each brief, and writes everything to eval/runs/&lt;timestamp&gt;/:
 * one log per run (the captured console, the debug record, the brief, the grade), results.md and results.json.
 *
 * How to run it (optional argument: the question ids, e.g. "1,9"):
 *   mvn -q compile exec:java -Dexec.mainClass=org.example.capstone.EvalRunner -Dexec.args="1,9"
 * Options (JVM -D flags):
 *   -Dcapstone.eval.modes=baseline,pipeline   which modes to run (default: DEFAULT_MODES below)
 *   -Dcapstone.maxCostUsd=0.20                cost cap of each single run
 *   -Dcapstone.eval.maxTotalUsd=0.60          stop starting new runs once this much has been spent (default below)
 */
public class EvalRunner {

    /**
     * Defaults used when no -D flag is given. Set for the re-run of the baseline with search (the pipeline half
     * of the first eval is valid and is not repeated). Change to "baseline,pipeline" for a full comparison.
     */
    static final String DEFAULT_MODES = "baseline";
    /** Which questions to run when no program argument is given ("" = all 10). Now: #3 (thinking ran away twice) and #8 (comparison) to check the low-effort fix. */
    static final String DEFAULT_IDS = "3,8";
    static final String DEFAULT_MAX_TOTAL_USD = "0.60";

    public record EvalResult(int id, String type, String question, String mode, boolean complete, boolean valid,
                             int words, int invented, int factsFound, int factsTotal, List<String> missingFacts,
                             double costUsd, int calls, double elapsedSec, String stopReason, String note) {
        double score() {
            return factsTotal == 0 ? 0 : (double) factsFound / factsTotal;
        }
    }

    public static void main(String[] args) throws Exception {
        List<EvalCase> cases = EvalCase.load(Path.of(System.getProperty("capstone.eval.questions", "eval/questions.json")));
        String ids = args.length > 0 ? args[0] : DEFAULT_IDS;
        if (!ids.isBlank()) {
            Set<Integer> only = Arrays.stream(ids.split(",")).map(String::trim).map(Integer::parseInt).collect(Collectors.toSet());
            cases = cases.stream().filter(c -> only.contains(c.id())).toList();
        }
        List<String> modes = Arrays.asList(System.getProperty("capstone.eval.modes", DEFAULT_MODES).split(","));
        double cap = Double.parseDouble(System.getProperty("capstone.maxCostUsd", "0.20"));
        double totalLimit = Double.parseDouble(System.getProperty("capstone.eval.maxTotalUsd", DEFAULT_MAX_TOTAL_USD));
        boolean verbose = Boolean.getBoolean("capstone.verbose");

        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path dir = Path.of("eval", "runs", stamp);
        Files.createDirectories(dir);

        ReportService reports = new ReportService();
        ValidationService validation = new ValidationService();
        AnthropicClient client = Clients.fromEnv();

        PrintStream realOut = System.out;
        PrintStream realErr = System.err;
        List<EvalResult> results = new ArrayList<>();
        double spent = 0;
        int run = 0, totalRuns = cases.size() * modes.size();

        realOut.printf("eval: %d questions x %s, cap $%.2f per run, stop after $%.2f in total, logs in %s%n",
                cases.size(), modes, cap, totalLimit, dir);

        for (EvalCase c : cases) {
            for (String mode : modes) {
                run++;
                String label = String.format("[%d/%d] #%d %-8s", run, totalRuns, c.id(), mode);
                if (spent >= totalLimit) {
                    results.add(new EvalResult(c.id(), c.type(), c.question(), mode, false, false, 0, 0, 0,
                            c.facts().size(), c.facts().stream().map(EvalCase.Fact::name).toList(), 0, 0, 0, null,
                            "skipped: eval cost limit reached"));
                    realOut.println(label + " skipped (eval cost limit $" + totalLimit + " reached)");
                    continue;
                }

                // Everything the run prints goes into a buffer that is saved in the run's own log.
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                PrintStream capture = new PrintStream(buffer, true, StandardCharsets.UTF_8);
                System.setOut(capture);
                System.setErr(capture);
                EvalResult result;
                String brief = "";
                String debug = "";
                try {
                    RunResult r = mode.equals("baseline")
                            ? SingleAgentRunner.run(client, reports, c.question(), cap, verbose)
                            : new ResearchPipeline(client, reports, verbose).run(c.question(), cap);
                    debug = r.debug();
                    boolean complete = r.isComplete();
                    int words = 0, invented = 0;
                    boolean valid = false;
                    List<String> violations = List.of();
                    if (r.report() != null) {
                        brief = reports.render(r.report(), !complete, r.stopReason());
                        ValidationService.ValidationResult v = validation.validate(brief, r.report(), r.resultUrls(), r.citedUrls());
                        words = v.words();
                        invented = v.inventedUrlCount();
                        valid = v.isValid();
                        violations = v.violations();
                    }
                    Grader.Grade g = Grader.grade(c, brief);
                    String note = r.stoppedBy() != null ? r.stoppedBy()
                            : r.report() == null ? "no valid report" : violations.isEmpty() ? "" : "violations: " + violations;
                    result = new EvalResult(c.id(), c.type(), c.question(), mode, complete, valid, words, invented,
                            g.found(), g.total(), g.missing(), r.totals().costUsd(), r.totals().calls(), r.elapsedSec(),
                            String.valueOf(r.stopReason()), note);
                } catch (Exception e) {
                    result = new EvalResult(c.id(), c.type(), c.question(), mode, false, false, 0, 0, 0,
                            c.facts().size(), c.facts().stream().map(EvalCase.Fact::name).toList(), 0, 0, 0, null,
                            "exception: " + e);
                } finally {
                    System.setOut(realOut);
                    System.setErr(realErr);
                }

                spent += result.costUsd();
                results.add(result);
                writeRunLog(dir.resolve(String.format("%02d-%s.log", c.id(), mode)), c, result, buffer.toString(StandardCharsets.UTF_8), debug, brief);
                realOut.printf(Locale.ROOT, "%s facts %d/%d  %s  words %3d  invented %d  $%.4f  %4.1f s  %s%n", label,
                        result.factsFound(), result.factsTotal(), result.valid() ? "valid  " : "INVALID", result.words(),
                        result.invented(), result.costUsd(), result.elapsedSec(), result.note());
            }
        }

        String table = summary(results, spent);
        Files.writeString(dir.resolve("results.md"), table, StandardCharsets.UTF_8);
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(dir.resolve("results.json").toFile(), results);
        realOut.println();
        realOut.println(table);
        realOut.println("results = " + dir.resolve("results.md"));
    }

    private static void writeRunLog(Path file, EvalCase c, EvalResult r, String console, String debug, String brief) throws java.io.IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("question #").append(c.id()).append(" (").append(c.type()).append("): ").append(c.question()).append("\n");
        sb.append("mode: ").append(r.mode()).append("\n");
        sb.append("facts expected: ").append(c.facts().stream().map(EvalCase.Fact::name).toList()).append("\n");
        sb.append("facts found ").append(r.factsFound()).append("/").append(r.factsTotal())
                .append(", missing ").append(r.missingFacts()).append("\n");
        sb.append("complete=").append(r.complete()).append(" valid=").append(r.valid()).append(" words=").append(r.words())
                .append(" invented=").append(r.invented()).append(" cost=$").append(String.format(Locale.ROOT, "%.4f", r.costUsd()))
                .append(" calls=").append(r.calls()).append(" elapsed=").append(String.format(Locale.ROOT, "%.1f", r.elapsedSec()))
                .append(" s stop=").append(r.stopReason()).append("\nnote: ").append(r.note()).append("\n\n");
        sb.append("===== brief =====\n").append(brief).append("\n\n");
        sb.append("===== console output of the run =====\n").append(console).append("\n");
        sb.append("===== debug record =====\n").append(debug).append("\n");
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    static String summary(List<EvalResult> results, double spent) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Eval results\n\n");
        sb.append("| # | type | mode | facts | valid | words | invented | cost | time | calls | note |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (EvalResult r : results) {
            sb.append(String.format(Locale.ROOT, "| %d | %s | %s | %d/%d | %s | %d | %d | $%.4f | %.1f s | %d | %s |%n",
                    r.id(), r.type(), r.mode(), r.factsFound(), r.factsTotal(), r.valid() ? "yes" : "NO", r.words(),
                    r.invented(), r.costUsd(), r.elapsedSec(), r.calls(), r.note().replace("|", "/")));
        }
        sb.append("\n## Per mode\n\n| mode | runs | avg facts | valid | avg words | avg cost | avg time |\n|---|---|---|---|---|---|---|\n");
        for (String mode : results.stream().map(EvalResult::mode).distinct().toList()) {
            List<EvalResult> rs = results.stream().filter(r -> r.mode().equals(mode) && !r.note().startsWith("skipped")).toList();
            if (rs.isEmpty()) continue;
            sb.append(String.format(Locale.ROOT, "| %s | %d | %.0f%% | %d/%d | %.0f | $%.4f | %.1f s |%n", mode, rs.size(),
                    100 * rs.stream().mapToDouble(EvalResult::score).average().orElse(0),
                    rs.stream().filter(EvalResult::valid).count(), rs.size(),
                    rs.stream().mapToInt(EvalResult::words).average().orElse(0),
                    rs.stream().mapToDouble(EvalResult::costUsd).average().orElse(0),
                    rs.stream().mapToDouble(EvalResult::elapsedSec).average().orElse(0)));
        }
        sb.append(String.format(Locale.ROOT, "%nTotal spent (estimated): $%.4f%n", spent));
        return sb.toString();
    }
}
