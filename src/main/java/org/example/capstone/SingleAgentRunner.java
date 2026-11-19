package org.example.capstone;

import com.anthropic.client.AnthropicClient;

import java.util.Map;

/** The single-agent run (Step 1-4 behaviour; the baseline of the eval): one agent searches and writes the brief. */
public final class SingleAgentRunner {

    private SingleAgentRunner() {}

    public static RunResult run(AnthropicClient client, ReportService reports, String question, double cap, boolean verbose) {
        BaselineAgentBuilder agent = new BaselineAgentBuilder();
        Budget budget = agent.newBudget(cap, Budget.DEFAULT_MAX_CALLS);
        long startedAt = System.nanoTime();
        StageResult<Report> r = StageRunner.run("agent", client, budget, agent, reports,
                agent.userMessage(question), 0, verbose);
        System.out.print(r.log());
        double elapsed = (System.nanoTime() - startedAt) / 1e9;
        Map<String, Budget> stages = Map.of("agent", budget);
        RunSummary.print(stages, cap, elapsed);
        return new RunResult(r.value(), r.rawText(), r.stopReason(), r.resultUrls(), r.citedUrls(), r.stoppedBy(),
                r.debug(), RunSummary.totals(stages), elapsed);
    }
}
