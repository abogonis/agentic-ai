package org.example.capstone;

import com.anthropic.client.AnthropicClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Step 5: planner -> parallel researchers -> synthesizer. Each role is its own AgentBuilder; each stage has
 * its own Budget (so the parallel researchers never race on a shared counter) and money a stage does not
 * use rolls forward to the next one. A failed researcher is dropped and the run continues (R5).
 */
public class ResearchPipeline {

    static final int SUB_QUESTIONS = 3;
    /** Share of the total cap: the planner is tiny, the synthesizer's money is held back for it. */
    static final double PLANNER_SHARE = 0.06;
    static final double SYNTHESIZER_SHARE = 0.18;
    static final int PLANNER_CALLS = 2;
    static final int RESEARCHER_CALLS = 3;
    static final int SYNTHESIZER_CALLS = 3;
    /** A researcher that has not answered after this long is dropped (R5); the SDK request timeout is 90 s. */
    static final long RESEARCHER_TIMEOUT_SECONDS = 150;

    private final AnthropicClient client;
    private final ReportService reports;
    private final boolean verbose;
    private final PlannerAgentBuilder planner = new PlannerAgentBuilder();
    private final ResearcherAgentBuilder researcher = new ResearcherAgentBuilder();
    private final SynthesizerAgentBuilder synthesizer = new SynthesizerAgentBuilder();

    public ResearchPipeline(AnthropicClient client, ReportService reports, boolean verbose) {
        this.client = client;
        this.reports = reports;
        this.verbose = verbose;
    }

    public RunResult run(String question, double capUsd) {
        long startedAt = System.nanoTime();
        Map<String, Budget> stages = new LinkedHashMap<>();
        StringBuilder debug = new StringBuilder();
        // 1. Planner: no search, a small answer.
        Budget plannerBudget = planner.newBudget(capUsd * PLANNER_SHARE, PLANNER_CALLS);
        stages.put("planner", plannerBudget);
        StageResult<Plan> planned = StageRunner.run("planner", client, plannerBudget, planner, reports,
                planner.userMessage(question), 0, verbose);
        System.out.print(planned.log());
        debug.append(planned.debug());

        List<String> subQuestions = new ArrayList<>();
        if (planned.value() != null) {
            planned.value().subQuestions().stream().filter(s -> !s.isBlank()).limit(SUB_QUESTIONS).forEach(subQuestions::add);
        }
        if (subQuestions.isEmpty()) {
            System.out.println("[planner] no usable plan (" + describe(planned) + "); researching the question as it is");
            subQuestions.add(question);
        }
        for (int i = 0; i < subQuestions.size(); i++) {
            System.out.println("sub-question " + (i + 1) + ": " + subQuestions.get(i));
            debug.append("sub-question ").append(i + 1).append(": ").append(subQuestions.get(i)).append("\n");
        }

        // 2. Researchers, in parallel. Each gets an equal share of what is left after the synthesizer's reserve.
        double left = capUsd - plannerBudget.spentUsd();
        double each = Math.max(0, left - capUsd * SYNTHESIZER_SHARE) / subQuestions.size();
        List<Budget> researcherBudgets = new ArrayList<>();
        List<CompletableFuture<StageResult<Notes>>> futures = new ArrayList<>();
        for (int i = 0; i < subQuestions.size(); i++) {
            String name = "researcher " + (i + 1);
            String user = researcher.userMessage(question, subQuestions.get(i));
            Budget b = researcher.newBudget(each, RESEARCHER_CALLS);
            researcherBudgets.add(b);
            stages.put(name, b);
            futures.add(CompletableFuture.supplyAsync(() ->
                            StageRunner.run(name, client, b, researcher, reports, user, 0, verbose))
                    .orTimeout(RESEARCHER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    // R5: an unexpected exception or a timeout in one researcher must not kill the run.
                    .exceptionally(e -> failedStage(name, e)));
        }

        Set<String> resultUrls = new LinkedHashSet<>();
        StringBuilder notes = new StringBuilder();
        int usable = 0;
        double researchSpent = 0;
        for (int i = 0; i < futures.size(); i++) {
            StageResult<Notes> r = futures.get(i).join();
            System.out.print(r.log());
            debug.append(r.debug());
            researchSpent += researcherBudgets.get(i).spentUsd();
            notes.append("Sub-question ").append(i + 1).append(": ").append(subQuestions.get(i)).append("\n");
            // A researcher that never searched has no evidence: its claims and URLs would come from memory (R1).
            Notes clean = null;
            if (r.ok() && !r.resultUrls().isEmpty()) {
                List<String> removed = new ArrayList<>();
                clean = r.value().withoutUnknownUrls(r.resultUrls(), removed);
                for (String line : removed) {
                    System.out.println("[researcher " + (i + 1) + "] R1: " + line);
                    debug.append("R1: ").append(line).append("\n");
                }
            }
            if (clean != null && !clean.findings().isEmpty()) {
                usable++;
                resultUrls.addAll(r.resultUrls());
                notes.append(clean.toText()).append("\n");
                debug.append("notes kept from researcher ").append(i + 1).append(":\n").append(clean.toText()).append("\n");
            } else {
                System.out.println("[researcher " + (i + 1) + "] dropped (" + describe(r) + ")");
                debug.append("researcher ").append(i + 1).append(" dropped: ").append(describe(r)).append("\n");
                notes.append("- no result for this sub-question (").append(describe(r)).append(")\n\n");
            }
        }
        if (usable == 0) {
            double elapsed = (System.nanoTime() - startedAt) / 1e9;
            RunSummary.print(stages, capUsd, elapsed);
            return new RunResult(null, "", null, resultUrls, Set.of(), "no researcher produced usable notes",
                    debug.toString(), RunSummary.totals(stages), elapsed);
        }

        // 3. Synthesizer: no search; gets everything the earlier stages did not spend.
        double synthMoney = Math.max(0, capUsd - plannerBudget.spentUsd() - researchSpent);
        Budget synthBudget = synthesizer.newBudget(synthMoney, SYNTHESIZER_CALLS);
        stages.put("synthesizer", synthBudget);
        String user = synthesizer.userMessage(question, notes.toString());
        StageResult<Report> written = StageRunner.run("synthesizer", client, synthBudget, synthesizer, reports,
                user, user.length() / 3, verbose);
        System.out.print(written.log());
        debug.append(written.debug());

        // R5: one empty, cut-off or unparsable answer is retried once (it happened once in about 40 live runs).
        if (!isUsable(written)) {
            System.out.println("[synthesizer] unusable answer (" + describe(written) + "); retrying once");
            debug.append("synthesizer unusable (").append(describe(written)).append("); retrying once\n");
            StageResult<Report> second = StageRunner.run("synthesizer (retry)", client, synthBudget, synthesizer, reports,
                    user, user.length() / 3, verbose);
            System.out.print(second.log());
            debug.append(second.debug());
            if (isUsable(second) || written.value() == null) written = second;
        }

        double elapsed = (System.nanoTime() - startedAt) / 1e9;
        RunSummary.print(stages, capUsd, elapsed);
        if (usable < subQuestions.size()) {
            System.out.println("note: " + (subQuestions.size() - usable) + " of " + subQuestions.size()
                    + " researchers failed, so the brief is narrower than planned");
        }

        // The brief may only cite URLs that some researcher's search really returned (R1).
        return new RunResult(written.value(), written.rawText(), written.stopReason(), resultUrls,
                written.citedUrls(), written.stoppedBy(), debug.toString(), RunSummary.totals(stages), elapsed);
    }

    /** A brief is usable if the model finished normally and wrote a title, a summary and at least one finding. */
    static boolean isUsable(StageResult<Report> r) {
        Report report = r.value();
        return r.ok() && !report.title().isBlank() && !report.summary().isBlank() && !report.findings().isEmpty();
    }

    private static StageResult<Notes> failedStage(String name, Throwable e) {
        String why = "exception: " + (e.getCause() != null ? e.getCause() : e);
        return new StageResult<>(null, "", Set.of(), Set.of(), null, why,
                "[" + name + "] failed: " + why + "\n", "[" + name + "] failed: " + why + "\n");
    }

    private static String describe(StageResult<?> r) {
        if (r.stoppedBy() != null) return r.stoppedBy();
        if (r.value() != null && r.resultUrls().isEmpty()) return "it answered without searching";
        return "stop_reason=" + r.stopReason() + (r.value() == null ? ", no valid JSON" : "");
    }
}
