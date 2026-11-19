package org.example.capstone;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * The research brief as typed data. The model fills this shape (structured output), and
 * {@link #toMarkdown()} renders the final brief, so the format cannot drift between runs.
 * The descriptions below are sent to the model as part of the JSON schema.
 */
public record Report(
        @JsonPropertyDescription("Short title of the brief, at most 12 words.")
        String title,

        @JsonPropertyDescription("Summary of the answer in 2 to 3 sentences.")
        String summary,

        @JsonPropertyDescription("3 to 5 findings. Each finding is one or two sentences with the URLs that support it.")
        List<Finding> findings,

        @JsonPropertyDescription("Every URL used in the findings. Only URLs that appeared in web search results; never invent URLs.")
        List<String> sources,

        @JsonPropertyDescription("One line listing what could not be found out or verified. Empty if nothing is open.")
        String openQuestions) {

    /** One claim and the source URLs that support it (rule R1: at least one URL). */
    public record Finding(
            @JsonPropertyDescription("The finding, one or two sentences. Say so when sources disagree or when unsure.")
            String claim,

            @JsonPropertyDescription("At least one URL from web search results that supports the claim.")
            List<String> sourceUrls) {

        public Finding {
            claim = claim == null ? "" : claim;
            sourceUrls = sourceUrls == null ? List.of() : List.copyOf(sourceUrls);
        }
    }

    /** Missing values from the model become empty values, so rendering never hits a null. */
    public Report {
        title = title == null ? "" : title;
        summary = summary == null ? "" : summary;
        findings = findings == null ? List.of() : List.copyOf(findings);
        sources = sources == null ? List.of() : List.copyOf(sources);
        openQuestions = openQuestions == null ? "" : openQuestions;
    }

    /** Renders the brief in the format required by CAPSTONE.md. */
    public String toMarkdown() {
        StringBuilder md = new StringBuilder();
        md.append("# ").append(title).append("\n\n");

        md.append("## Summary\n").append(summary).append("\n\n");

        md.append("## Findings\n");
        int n = 1;
        for (Finding f : findings) {
            md.append(n++).append(". ").append(f.claim());
            if (!f.sourceUrls().isEmpty()) {
                md.append(" (").append(String.join(", ", f.sourceUrls())).append(")");
            }
            md.append("\n");
        }
        md.append("\n");

        md.append("## Sources\n");
        if (sources.isEmpty()) {
            md.append("No sources found.\n");
        } else {
            for (String url : sources) md.append("- ").append(url).append("\n");
        }
        md.append("\n");

        md.append("## Open questions\n")
                .append(openQuestions.isBlank() ? "None." : openQuestions)
                .append("\n");
        return md.toString();
    }
}
