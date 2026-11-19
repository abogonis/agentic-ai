package org.example.capstone;

import java.util.Map;

/** R6: the run summary: calls, tokens, cache hits, estimated cost and elapsed time, per stage and in total. */
public final class RunSummary {

    /** The summed numbers of a run (all stages). */
    public record Totals(int calls, long searches, long inputTokens, long outputTokens,
                         long cacheReadTokens, long cacheWriteTokens, double costUsd) {}

    private RunSummary() {}

    public static Totals totals(Map<String, Budget> stages) {
        int calls = 0;
        long searches = 0, in = 0, out = 0, read = 0, write = 0;
        double cost = 0;
        for (Budget b : stages.values()) {
            calls += b.calls();
            searches += b.searches();
            in += b.inputTokens();
            out += b.outputTokens();
            read += b.cacheReadTokens();
            write += b.cacheWriteTokens();
            cost += b.spentUsd();
        }
        return new Totals(calls, searches, in, out, read, write, cost);
    }

    /**
     * @param stages     the budgets of the stages that ran, in order (the cost is estimated from usage and the price table)
     * @param capUsd     the total cost cap
     * @param elapsedSec wall-clock time of the whole run
     */
    public static void print(Map<String, Budget> stages, double capUsd, double elapsedSec) {
        System.out.println();
        System.out.println("run summary");
        System.out.printf("  %-12s %5s %8s %9s %8s %11s %12s %9s%n",
                "stage", "calls", "searches", "tokens in", "out", "cache read", "cache write", "cost");
        int calls = 0;
        long searches = 0, in = 0, out = 0, read = 0, write = 0;
        double cost = 0;
        for (Map.Entry<String, Budget> e : stages.entrySet()) {
            Budget b = e.getValue();
            System.out.printf("  %-12s %5d %8d %9d %8d %11d %12d  $%.4f%n", e.getKey(),
                    b.calls(), b.searches(), b.inputTokens(), b.outputTokens(),
                    b.cacheReadTokens(), b.cacheWriteTokens(), b.spentUsd());
            calls += b.calls();
            searches += b.searches();
            in += b.inputTokens();
            out += b.outputTokens();
            read += b.cacheReadTokens();
            write += b.cacheWriteTokens();
            cost += b.spentUsd();
        }
        System.out.printf("  %-12s %5d %8d %9d %8d %11d %12d  $%.4f%n", "total",
                calls, searches, in, out, read, write, cost);
        System.out.printf("  estimated cost $%.4f of the $%.2f cap, %d model calls, elapsed %.1f s, cache reads %s%n",
                cost, capUsd, calls, elapsedSec, read > 0 ? "> 0 (cache hit)" : "= 0 (no cache hit)");
        if (cost > capUsd) System.out.println("  ** OVER BUDGET (the estimate was too low) **");
    }
}
