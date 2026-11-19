package org.example.capstone;

/**
 * R5 test, free (no request reaches the API): runs the pipeline and the single agent against a port where nothing
 * listens. Every API call fails with a connection error. Expected: no exception, no crash, a clear INCOMPLETE result.
 */
public class FaultTest {

    public static void main(String[] args) {
        var client = Clients.forTest("http://localhost:1");
        ReportService reports = new ReportService();
        boolean ok = true;

        System.out.println("--- pipeline against a dead server ---");
        long t0 = System.nanoTime();
        try {
            RunResult r = new ResearchPipeline(client, reports, false).run("Should a small team adopt microservices?", 0.20);
            boolean clean = r.report() == null && r.stoppedBy() != null;
            System.out.printf("pipeline: no exception, report=%s, stoppedBy=%s, %.1f s -> %s%n",
                    r.report(), r.stoppedBy(), (System.nanoTime() - t0) / 1e9, clean ? "PASS" : "FAIL");
            ok &= clean;
        } catch (Throwable e) {
            System.out.println("pipeline: CRASHED with " + e + " -> FAIL");
            ok = false;
        }

        System.out.println("--- single agent against a dead server ---");
        try {
            RunResult r = SingleAgentRunner.run(client, reports, "Should a small team adopt microservices?", 0.20, false);
            boolean clean = r.report() == null && r.stoppedBy() != null;
            System.out.println("single agent: no exception, stoppedBy=" + r.stoppedBy() + " -> " + (clean ? "PASS" : "FAIL"));
            ok &= clean;
        } catch (Throwable e) {
            System.out.println("single agent: CRASHED with " + e + " -> FAIL");
            ok = false;
        }
        System.out.println(ok ? "R5 fault test: PASS" : "R5 fault test: FAIL");
        System.exit(ok ? 0 : 1);
    }
}
