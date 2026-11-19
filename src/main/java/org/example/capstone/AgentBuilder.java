package org.example.capstone;

import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingConfigDisabled;
import com.anthropic.models.messages.WebSearchTool20250305;

import java.time.LocalDate;
import java.util.List;

/**
 * Base of every agent role. A role describes itself (system prompt, typed output, token and search
 * limits, the smallest answer worth paying for) and builds its request. It makes no API calls and reads
 * no responses; StageRunner sends the request and handles the answer.
 *
 * @param <T> the typed result the role must answer with (Plan, Notes or Report)
 */
public abstract class AgentBuilder<T> {

    static final String OPUS = "claude-opus-5-5";
    static final String SONNET = "claude-sonnet-5-5";
    static final String HAIKU = "claude-haiku-5-5";

    private final String model;

    protected AgentBuilder(String model) {
        this.model = model;
        Pricing.forModel(model); // fail at start-up, not mid-run, if the model has no known price
    }

    public String model() {
        return model;
    }

    /** The shape of the JSON the model must answer with. */
    public abstract Class<T> outputType();

    public abstract String system();

    /** The most output tokens this role asks for. */
    public abstract long maxTokens();

    /** The most web searches this role may run (0 = no search tool). */
    public abstract long maxSearches();

    /** Below this many output tokens the role cannot produce a usable answer, so the budget refuses. */
    public abstract long minOutputTokens();

    /**
     * Whether the model may think before answering. Thinking tokens are billed as output and count against
     * maxTokens (a Haiku run used about 2,000 of its 4,000 on hidden reasoning and was cut off before the
     * JSON finished). The roles here are extraction and formatting, so it is off unless a role asks for it.
     */
    public boolean thinking() {
        return false;
    }

    /**
     * How hard the model thinks when thinking is on (low, medium, high). Empty = the model's default (medium on
     * Haiku 5.5). A low effort bounds the reasoning: one baseline run spent 8,242 of 8,311 output tokens thinking
     * and never wrote its answer, even after the limit was doubled.
     */
    public java.util.Optional<OutputConfig.Effort> effort() {
        return java.util.Optional.empty();
    }

    /** The fewest searches the budget may shrink this role to. */
    public long minSearches() {
        return maxSearches() > 0 ? 1 : 0;
    }

    /** A budget with this role's minimums built in. */
    public Budget newBudget(double maxCostUsd, int maxCalls) {
        return new Budget(maxCostUsd, maxCalls, minOutputTokens(), minSearches(), Pricing.forModel(model));
    }

    /**
     * Builds one request for the size the budget allowed. outputConfig(type) makes the model answer with
     * JSON matching the type. Earlier turns of a paused run are sent back so the model continues.
     *
     * Cache order is tools -> system -> messages, and a cache hit needs an identical prefix. So the system
     * prompt stays fixed per role and carries the cache breakpoint (it caches the tools and the system
     * prompt together); everything that changes (today's date, the search limit, the question) goes in the
     * user message after it. Prefixes below the model's minimum size are silently not cached.
     */
    public StructuredMessageCreateParams<T> request(String user, long maxTokens, long maxSearches, List<Message> priorTurns) {
        String volatileNote = "\n\n(Today's date is " + LocalDate.now() + "."
                + (maxSearches > 0
                    ? " You may run at most " + maxSearches + " web searches in this run; plan your queries so they "
                      + "cover every part of the task, and do not search again after the limit."
                    : "")
                + ")";

        StructuredMessageCreateParams.Builder<T> builder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxTokens)
                .outputConfig(outputType())
                .systemOfTextBlockParams(List.of(TextBlockParam.builder()
                        .text(system())
                        .cacheControl(CacheControlEphemeral.builder().build())
                        .build()))
                .addUserMessage(user + volatileNote);
        if (!thinking()) {
            builder.thinking(ThinkingConfigDisabled.builder().build());
        }
        if (effort().isPresent()) {
            // Keep the JSON format that outputConfig(type) set, and add the effort to the same config.
            OutputConfig withFormat = builder.build().rawParams().outputConfig().orElseThrow();
            builder.outputConfig(withFormat.toBuilder().effort(effort().get()).build());
        }
        if (maxSearches > 0) {
            builder.addTool(WebSearchTool20250305.builder().maxUses(maxSearches).build());
        }
        priorTurns.forEach(builder::addMessage);
        return builder.build();
    }
}
