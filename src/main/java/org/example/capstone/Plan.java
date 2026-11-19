package org.example.capstone;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/** The planner's output: the research question split into independent sub-questions. */
public record Plan(
        @JsonPropertyDescription("Exactly 3 self-contained sub-questions that together cover the whole question "
                + "with little overlap. Each names its topic in full and can be answered by a web search.")
        List<String> subQuestions) {

    public Plan {
        subQuestions = subQuestions == null ? List.of() : List.copyOf(subQuestions);
    }
}
