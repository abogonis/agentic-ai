package org.example.capstone;

/** Role: split the research question into 3 sub-questions. No search, a small answer. */
public class PlannerAgentBuilder extends AgentBuilder<Plan> {

    static final String SYSTEM = """
            You are the planner of a research team. Split the research question into exactly 3
            sub-questions that together cover it with little overlap. Each sub-question must be
            self-contained: name the topic in full and include any detail needed to search for it.
            For a comparison, use one sub-question per option plus one for how they compare on what
            matters to the decision. Do not answer the question.
            """;

    public PlannerAgentBuilder() {
        super(HAIKU);
    }

    @Override public Class<Plan> outputType() { return Plan.class; }
    @Override public String system() { return SYSTEM; }
    @Override public long maxTokens() { return 600; }
    @Override public long maxSearches() { return 0; }
    @Override public long minOutputTokens() { return 150; }

    public String userMessage(String question) {
        return question;
    }
}
