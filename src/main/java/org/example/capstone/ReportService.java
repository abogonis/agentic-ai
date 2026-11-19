package org.example.capstone;

import com.anthropic.models.messages.StopReason;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Everything about the report as a document: parsing the model's JSON, rendering Markdown, saving the file. */
public class ReportService {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new Jdk8Module())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final Path reportsDir;

    public ReportService() {
        this(Path.of("reports"));
    }

    public ReportService(Path reportsDir) {
        this.reportsDir = reportsDir;
    }

    /** Parses the model's JSON into a Report, or returns null if it is missing or cut off. */
    public Report parse(String json) {
        return parse(json, Report.class);
    }

    /** Parses the model's JSON into any typed result (Plan, Notes, Report), or null if missing or cut off. */
    public <T> T parse(String json, Class<T> type) {
        if (json == null || json.isBlank()) return null;
        try {
            return mapper.readValue(json, type);
        } catch (IOException e) {
            System.err.println("Could not parse the report JSON: " + e.getMessage());
            return null;
        }
    }

    /** Renders the brief; an incomplete run gets an INCOMPLETE marker on top (R4). */
    public String render(Report report, boolean incomplete, StopReason stopReason) {
        String marker = incomplete ? "INCOMPLETE (stop_reason=" + stopReason + ")\n\n" : "";
        return marker + report.toMarkdown();
    }

    /** Saves the brief to reports/<slug>.md and returns the file path. */
    public Path save(String question, String markdown) throws IOException {
        Files.createDirectories(reportsDir);
        Path file = reportsDir.resolve(slug(question) + ".md");
        Files.writeString(file, markdown, StandardCharsets.UTF_8);
        return file;
    }

    /** "Should a 5-person team adopt microservices?" becomes "should-a-5-person-team-adopt-microservices". */
    static String slug(String question) {
        String s = question.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (s.length() > 60) s = s.substring(0, 60).replaceAll("-+$", "");
        return s.isEmpty() ? "report" : s;
    }
}
