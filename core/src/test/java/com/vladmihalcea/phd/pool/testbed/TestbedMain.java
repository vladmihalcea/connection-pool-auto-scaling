package com.vladmihalcea.phd.pool.testbed;

import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import java.io.PrintWriter;
import java.util.regex.Pattern;

import static org.junit.platform.engine.discovery.ClassNameFilter.includeClassNamePatterns;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectPackage;

/**
 * Entry point of the self-contained testbed pack ({@code mvn -pl core -P testbed package}): runs the named
 * benchmark or experiment test classes through the JUnit Platform, as failsafe would, so a client machine
 * needs only a Java runtime to measure a remote database host. Arguments are simple or fully qualified
 * test class names, e.g. {@code LinkCheckExperimentTest}.
 *
 * @author Vlad Mihalcea
 */
public final class TestbedMain {

    private static final String BASE_PACKAGE = "com.vladmihalcea.phd.pool";

    private TestbedMain() {
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("Usage: TestbedMain <TestClassName>...");
            System.exit(2);
        }
        String[] patterns = new String[args.length];
        for (int i = 0; i < args.length; i++) {
            patterns[i] = args[i].contains(".") ? Pattern.quote(args[i]) : ".*\\." + Pattern.quote(args[i]);
        }
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(selectPackage(BASE_PACKAGE))
                .filters(includeClassNamePatterns(patterns))
                .build();
        SummaryGeneratingListener summary = new SummaryGeneratingListener();
        LauncherFactory.create().execute(request, summary, new OutcomeListener());

        TestExecutionSummary result = summary.getSummary();
        result.printTo(new PrintWriter(System.out, true));
        if (result.getTestsFoundCount() == 0) {
            System.err.println("No test class matches " + String.join(", ", args));
            System.exit(2);
        }
        // A test parameterized over both databases aborts (an assumption) for the one that is not configured, so
        // an abort alone is not a failure; a run in which nothing succeeded is.
        if (result.getTotalFailureCount() > 0) {
            System.exit(1);
        }
        if (result.getTestsSucceededCount() == 0) {
            System.err.println("No test ran: every test was skipped or aborted");
            System.exit(1);
        }
        System.exit(0);
    }

    /** Prints why a test was skipped, aborted or failed, which the summary alone does not show. */
    private static final class OutcomeListener implements TestExecutionListener {

        @Override
        public void executionSkipped(TestIdentifier test, String reason) {
            System.out.println("SKIPPED " + test.getDisplayName() + ": " + reason);
        }

        @Override
        public void executionFinished(TestIdentifier test, TestExecutionResult result) {
            if (result.getStatus() != TestExecutionResult.Status.SUCCESSFUL) {
                System.out.println(result.getStatus() + " " + test.getDisplayName() + ": "
                        + result.getThrowable().map(Throwable::toString).orElse(""));
            }
        }
    }
}
