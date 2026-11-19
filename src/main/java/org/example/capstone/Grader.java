package org.example.capstone;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Scores a brief by plain keyword matching: which of the expected facts does it mention? Cheap and
 * repeatable, but crude: it measures coverage, not whether a claim is supported by its source.
 */
public final class Grader {

    public record Grade(int found, int total, List<String> missing) {
        public double score() {
            return total == 0 ? 0 : (double) found / total;
        }
    }

    private Grader() {}

    /** A keyword starting with "re:" is a regular expression; any other keyword is a plain substring. */
    static boolean matches(String keyword, String lowerCaseText) {
        if (keyword.startsWith("re:")) {
            return Pattern.compile(keyword.substring(3), Pattern.CASE_INSENSITIVE).matcher(lowerCaseText).find();
        }
        return lowerCaseText.contains(keyword.toLowerCase(Locale.ROOT));
    }

    public static Grade grade(EvalCase c, String briefText) {
        String text = briefText == null ? "" : briefText.toLowerCase(Locale.ROOT);
        int found = 0;
        List<String> missing = new ArrayList<>();
        for (EvalCase.Fact f : c.facts()) {
            boolean hit = f.anyOf().stream().anyMatch(k -> matches(k, text));
            if (hit) found++;
            else missing.add(f.name());
        }
        return new Grade(found, c.facts().size(), missing);
    }
}
