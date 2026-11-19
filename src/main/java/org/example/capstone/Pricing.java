package org.example.capstone;

import com.anthropic.models.messages.Usage;

/**
 * What a call costs, in US dollars, per model. Prices are per million tokens, from the Claude API skill's
 * model table (cached 2026-10-06): check them against the pricing page before relying on them. Cache reads
 * are 0.1x input (0.05x on Opus 5.5) and 5-minute cache writes 1.25x input. Haiku 5.5 has a second, dearer
 * rate card for prompts over 100K tokens; our prompts are far below that, so it is ignored here.
 * The web search fee ($10 per 1,000 searches) is confirmed against the real bill: on 2026-11-15 nine Haiku
 * runs (about 42 searches) were estimated at $0.564 and the credit balance fell by $0.57.
 */
public final class Pricing {

    /** Per-search fee, the same for every model (unverified). */
    static final double PER_SEARCH = 0.010;

    /**
     * Input size of a call with the search tool, fitted to two live Opus runs: 1 search = 15.0k tokens,
     * 2 searches = 22.8k tokens, so about 8k per search plus about 7k of fixed input (the search tool's own
     * instructions are large). Token counts do not depend on price, so one fit serves all models.
     */
    static final long INPUT_TOKENS_PER_SEARCH = 8_000;
    static final long BASE_INPUT_TOKENS = 7_500;
    /** Without the search tool the fixed input is much smaller. */
    static final long BASE_INPUT_TOKENS_NO_TOOL = 1_500;
    /** maxTokens is not a hard cap (seen: 1800 -> 1952 out, 300 -> 456 out), so reserve extra output. */
    static final double OUTPUT_SAFETY = 1.25;

    /** Dollars per million tokens for one model. */
    public record Rates(double input, double output, double cacheRead, double cacheWrite) {

        /** The real cost of a finished call, from its usage block. */
        public double cost(Usage u) {
            long searches = u.serverToolUse().map(s -> s.webSearchRequests()).orElse(0L);
            return cost(u.inputTokens(), u.outputTokens(),
                    u.cacheReadInputTokens().orElse(0L), u.cacheCreationInputTokens().orElse(0L), searches);
        }

        public double cost(long inputTokens, long outputTokens, long cacheRead, long cacheWrite, long searches) {
            return (inputTokens * input + outputTokens * output
                    + cacheRead * this.cacheRead + cacheWrite * this.cacheWrite) / 1_000_000.0
                    + searches * PER_SEARCH;
        }

        /**
         * The worst-case cost of a call before it is sent: it uses every search, every search adds its usual
         * amount of input, and the model writes up to maxTokens. One request cannot be stopped halfway, so
         * the cap has to be applied with this number before the call.
         */
        public double estimate(long maxTokens, long maxSearches, long priorInputTokens) {
            long base = maxSearches == 0 ? BASE_INPUT_TOKENS_NO_TOOL : BASE_INPUT_TOKENS;
            long in = base + priorInputTokens + maxSearches * INPUT_TOKENS_PER_SEARCH;
            return cost(in, Math.round(maxTokens * OUTPUT_SAFETY), 0, 0, maxSearches);
        }

        /** Dollars for one output token, with the safety margin. */
        public double outputPerToken() {
            return output * OUTPUT_SAFETY / 1_000_000.0;
        }
    }

    public static final Rates OPUS_5_5 = new Rates(4.00, 20.00, 0.20, 5.00);
    public static final Rates SONNET_5_5 = new Rates(2.00, 10.00, 0.20, 2.50);
    public static final Rates HAIKU_5_5 = new Rates(0.10, 0.50, 0.01, 0.125);

    private Pricing() {}

    public static Rates forModel(String model) {
        return switch (model) {
            case "claude-opus-5-5" -> OPUS_5_5;
            case "claude-sonnet-5-5" -> SONNET_5_5;
            case "claude-haiku-5-5" -> HAIKU_5_5;
            default -> throw new IllegalArgumentException("No price known for model " + model);
        };
    }
}
