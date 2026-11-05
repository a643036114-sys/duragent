package dev.duragent.demo;

import org.junit.jupiter.api.Test;

/**
 * Runs the demo as part of {@code mvn test} so it cannot rot.
 *
 * <p>Run it standalone with:
 * {@code mvn -q test -Dtest=CrashResumeDemoTest}
 */
class CrashResumeDemoTest {

    @Test
    void demoRuns() throws Exception {
        CrashResumeDemo.main(new String[0]);
    }
}
