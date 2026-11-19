package org.example.capstone;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** One eval question and the facts a good brief should contain. */
public record EvalCase(int id, String type, String question, List<Fact> facts) {

    /** A fact is found when the brief contains at least one of the keywords (case-insensitive). */
    public record Fact(String name, List<String> anyOf) {}

    public static List<EvalCase> load(Path file) throws IOException {
        return List.of(new ObjectMapper().readValue(file.toFile(), EvalCase[].class));
    }
}
