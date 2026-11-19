package org.example.capstone;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Re-grades the briefs of an earlier eval run with the current questions.json, without any API call (free).
 * Use it after fixing a keyword. Usage: RegradeRunner eval/runs/&lt;timestamp&gt;
 * Writes results-regraded.md next to the original results and prints what changed.
 */
public class RegradeRunner {

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        Map<Integer, EvalCase> cases = EvalCase.load(Path.of("eval/questions.json")).stream()
                .collect(Collectors.toMap(EvalCase::id, Function.identity()));
        EvalRunner.EvalResult[] old = new ObjectMapper().readValue(dir.resolve("results.json").toFile(), EvalRunner.EvalResult[].class);

        List<EvalRunner.EvalResult> updated = new ArrayList<>();
        double spent = 0;
        for (EvalRunner.EvalResult r : old) {
            spent += r.costUsd();
            Path log = dir.resolve(String.format("%02d-%s.log", r.id(), r.mode()));
            String text = Files.readString(log, StandardCharsets.UTF_8);
            int a = text.indexOf("===== brief =====");
            int b = text.indexOf("===== console output");
            String brief = a < 0 ? "" : text.substring(a, b < 0 ? text.length() : b);
            Grader.Grade g = Grader.grade(cases.get(r.id()), brief);
            if (g.found() != r.factsFound()) {
                System.out.printf("#%d %-8s facts %d/%d -> %d/%d%n", r.id(), r.mode(), r.factsFound(), r.factsTotal(), g.found(), g.total());
            }
            updated.add(new EvalRunner.EvalResult(r.id(), r.type(), r.question(), r.mode(), r.complete(), r.valid(), r.words(),
                    r.invented(), g.found(), g.total(), g.missing(), r.costUsd(), r.calls(), r.elapsedSec(), r.stopReason(), r.note()));
        }
        Files.writeString(dir.resolve("results-regraded.md"), EvalRunner.summary(updated, spent), StandardCharsets.UTF_8);
        System.out.println(EvalRunner.summary(updated, spent));
    }
}
