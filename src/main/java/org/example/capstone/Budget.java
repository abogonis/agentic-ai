package org.example.capstone;

import com.anthropic.models.messages.Usage;

import java.util.Optional;

/**
 * R4: the run's spending limits ($ and number of model calls). plan() runs before each call and shrinks
 * the request to what is still affordable (or refuses); record() runs after it and charges the real cost.
 */
public class Budget {

    static final double DEFAULT_MAX_COST_USD = 0.20;
    static final int DEFAULT_MAX_CALLS = 15;
    /** Below this a brief cannot fit: real runs used 2,000-2,250 output tokens (JSON plus thinking); 1,800 was truncated. */
    static final long MIN_OUTPUT_TOKENS = 2_500;

    /** What the next call is allowed to use. */
    public record CallPlan(long maxTokens, long maxSearches, double estimatedCost) {}

    private final double maxCostUsd;
    private final int maxCalls;
    private final long minOutputTokens;
    private final long minSearches;
    private final Pricing.Rates rates;
    private double spent;
    private int calls;
    private long inputTokens;
    private long outputTokens;
    private long searches;
    private long cacheReadTokens;
    private long cacheWriteTokens;

    public Budget() {
        this(DEFAULT_MAX_COST_USD, DEFAULT_MAX_CALLS);
    }

    public Budget(double maxCostUsd, int maxCalls) {
        this(maxCostUsd, maxCalls, MIN_OUTPUT_TOKENS, 1, Pricing.OPUS_5_5);
    }

    /**
     * @param minOutputTokens smallest answer worth paying for in this stage (below it, refuse)
     * @param minSearches     fewest searches the stage may be shrunk to (0 for stages without search)
     * @param rates           the prices of the model this stage runs on
     */
    public Budget(double maxCostUsd, int maxCalls, long minOutputTokens, long minSearches, Pricing.Rates rates) {
        this.maxCostUsd = maxCostUsd;
        this.maxCalls = maxCalls;
        this.minOutputTokens = minOutputTokens;
        this.minSearches = minSearches;
        this.rates = rates;
    }

    /**
     * Picks the largest request that still fits in the remaining money: fewer searches first, then fewer
     * output tokens. Returns empty when no call can fit (the run must then stop as INCOMPLETE).
     *
     * @param priorInputTokens input already in the conversation (earlier rounds of this run)
     */
    public Optional<CallPlan> plan(long wantedTokens, long wantedSearches, long priorInputTokens) {
        if (calls >= maxCalls) return Optional.empty();
        double remaining = remainingUsd();

        for (long s = wantedSearches; s >= minSearches; s--) {
            double fixed = rates.estimate(0, s, priorInputTokens);
            double perToken = rates.outputPerToken();
            long affordable = (long) Math.floor((remaining - fixed) / perToken);
            long tokens = Math.min(wantedTokens, affordable);
            if (tokens >= minOutputTokens) {
                return Optional.of(new CallPlan(tokens, s, rates.estimate(tokens, s, priorInputTokens)));
            }
        }
        return Optional.empty();
    }

    /** Charges the real cost of a finished call. */
    public void record(Usage usage) {
        calls++;
        spent += rates.cost(usage);
        inputTokens += usage.inputTokens();
        outputTokens += usage.outputTokens();
        searches += usage.serverToolUse().map(s -> s.webSearchRequests()).orElse(0L);
        cacheReadTokens += usage.cacheReadInputTokens().orElse(0L);
        cacheWriteTokens += usage.cacheCreationInputTokens().orElse(0L);
    }

    public double remainingUsd() { return Math.max(0, maxCostUsd - spent); }
    public double spentUsd() { return spent; }
    public int calls() { return calls; }
    public int callsLeft() { return maxCalls - calls; }
    public long inputTokens() { return inputTokens; }
    public long outputTokens() { return outputTokens; }
    public long searches() { return searches; }
    public long cacheReadTokens() { return cacheReadTokens; }
    public long cacheWriteTokens() { return cacheWriteTokens; }

    public String summary() {
        String over = spent > maxCostUsd ? "  ** OVER BUDGET (the estimate was too low) **" : "";
        return String.format("budget: spent $%.4f of $%.2f, %d of %d calls, %d searches, tokens in/out %d / %d, cache read/write %d / %d%s",
                spent, maxCostUsd, calls, maxCalls, searches, inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens, over);
    }
}
