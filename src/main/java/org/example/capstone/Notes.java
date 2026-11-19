package org.example.capstone;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** What one researcher found for its sub-question: sourced claims, not a finished brief. */
public record Notes(
        @JsonPropertyDescription("2 to 3 findings for the sub-question. One claim per finding, each with source URLs.")
        List<Report.Finding> findings,

        @JsonPropertyDescription("One line: what could not be found or verified. Empty if nothing is open.")
        String openQuestions) {

    public Notes {
        findings = findings == null ? List.of() : List.copyOf(findings);
        openQuestions = openQuestions == null ? "" : openQuestions;
    }

    /**
     * R1 at the source: keeps only URLs that this researcher's own searches returned, and drops a finding
     * left without any URL. Each removal is described in {@code removed} (with result URLs of the same
     * site, to tell an invention from a different spelling).
     */
    public Notes withoutUnknownUrls(Set<String> allowed, List<String> removed) {
        List<Report.Finding> kept = new ArrayList<>();
        for (Report.Finding f : findings) {
            List<String> urls = new ArrayList<>();
            for (String url : f.sourceUrls()) {
                String normalized = ValidationService.normalizeUrl(url);
                String snapped = allowed.contains(normalized) ? normalized : closest(normalized, allowed);
                if (snapped != null) {
                    if (!snapped.equals(normalized)) {
                        removed.add("snapped a slightly different URL to the real search result: "
                                + normalized + " -> " + snapped);
                    }
                    urls.add(snapped);
                } else {
                    removed.add("removed URL not in this researcher's search results: " + normalized
                            + sameSite(normalized, allowed));
                }
            }
            if (urls.isEmpty()) {
                removed.add("dropped finding, no valid URL left: " + f.claim());
            } else {
                kept.add(new Report.Finding(f.claim(), urls));
            }
        }
        return new Notes(kept, openQuestions);
    }

    /** Largest edit distance (in characters, ignoring "www.") at which a URL counts as a misspelling of a result. */
    static final int MAX_URL_DISTANCE = 3;

    /**
     * The search result URL this URL is almost identical to (a dropped "www.", a dropped word in the host, a
     * "corrected" slug), or null. Snapping to a real result URL keeps R1: the cited URL is always one the
     * search returned.
     */
    static String closest(String url, Set<String> allowed) {
        String best = null;
        int bestDistance = MAX_URL_DISTANCE + 1;
        String loose = url.replace("://www.", "://");
        for (String candidate : allowed) {
            String c = candidate.replace("://www.", "://");
            if (Math.abs(c.length() - loose.length()) > MAX_URL_DISTANCE) continue;
            int d = distance(loose, c);
            if (d < bestDistance) {
                bestDistance = d;
                best = candidate;
            }
        }
        return best;
    }

    /** Levenshtein edit distance. */
    static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }

    private static String sameSite(String url, Set<String> allowed) {
        String host = hostOf(url);
        if (host == null) return "";
        List<String> near = allowed.stream().filter(u -> host.equals(hostOf(u))).limit(3).toList();
        return near.isEmpty() ? " (no result from this site at all)" : " (results from the same site: " + near + ")";
    }

    private static String hostOf(String url) {
        try {
            return URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Plain text handed to the synthesizer. */
    public String toText() {
        StringBuilder sb = new StringBuilder();
        for (Report.Finding f : findings) {
            sb.append("- ").append(f.claim()).append("\n  sources: ").append(String.join(", ", f.sourceUrls())).append("\n");
        }
        if (!openQuestions.isBlank()) sb.append("- not found: ").append(openQuestions).append("\n");
        return sb.toString();
    }
}
