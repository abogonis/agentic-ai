package org.example.capstone;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Checks a finished brief against the quality rules: R1 (every finding has a source and every URL comes
 * from the search results, never from memory) and R3 (at most 500 words).
 */
public class ValidationService {

    static final int MAX_WORDS = 500;

    /** The outcome of the checks: counters for the run summary and a list of rule violations. */
    public record ValidationResult(
            int words,
            int resultUrlCount,
            int reportUrlCount,
            int citedUrlCount,
            int inventedUrlCount,
            List<String> violations) {

        public boolean isValid() {
            return violations.isEmpty();
        }
    }

    /**
     * @param markdown   the rendered brief the analyst will read (R3 is counted on this text)
     * @param report     the parsed report (R1 is checked on its findings and sources)
     * @param resultUrls URLs the search tool really returned
     * @param citedUrls  URLs cited inline by the model, if any
     */
    public ValidationResult validate(String markdown, Report report, Set<String> resultUrls, Set<String> citedUrls) {
        List<String> violations = new ArrayList<>();

        int words = countWords(markdown);
        if (words > MAX_WORDS) {
            violations.add("R3 violated: " + words + " words (limit " + MAX_WORDS + ")");
        }

        // An empty brief passes every check below trivially, so reject it first.
        if (report.title().isBlank() || report.summary().isBlank()) {
            violations.add("Empty brief: the title or the summary is blank");
        }
        if (report.findings().isEmpty()) {
            violations.add("Empty brief: no findings");
        }

        Set<String> reportUrls = new LinkedHashSet<>();
        int index = 1;
        for (Report.Finding f : report.findings()) {
            if (f.sourceUrls().isEmpty()) {
                violations.add("R1 violated: finding " + index + " has no source URL");
            }
            for (String url : f.sourceUrls()) reportUrls.add(normalizeUrl(url));
            index++;
        }
        for (String url : report.sources()) reportUrls.add(normalizeUrl(url));

        int invented = 0;
        for (String url : reportUrls) {
            if (!resultUrls.contains(url)) {
                violations.add("R1 violated: invented URL (not in search results): " + url);
                invented++;
            }
        }
        for (String url : citedUrls) {
            if (!resultUrls.contains(url)) {
                violations.add("R1 violated: invented URL in citation (not in search results): " + url);
                invented++;
            }
        }

        return new ValidationResult(words, resultUrls.size(), reportUrls.size(), citedUrls.size(), invented, violations);
    }

    /** A blank text counts as 0 words ("".split("\\s+") would return one empty string). */
    static int countWords(String text) {
        return text.isBlank() ? 0 : text.trim().split("\\s+").length;
    }

    /** Strips trailing punctuation and slashes so the same URL compares equal however it was written. */
    static String normalizeUrl(String url) {
        return url.trim().replaceAll("[.,;:)\\]]+$", "").replaceAll("/+$", "");
    }
}
