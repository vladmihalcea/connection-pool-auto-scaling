package com.vladmihalcea.phd.pool.jmh;

import com.vladmihalcea.phd.pool.common.util.AbstractBenchmarkTest;
import com.vladmihalcea.phd.pool.common.util.Database;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.Locale;

/**
 * Failsafe entry point for {@link ThroughputVsPoolSizeBenchmark} (experiment E1). Writes JSON + a tidy
 * long-format CSV under {@code metrics/jmh-results/}, plus the response-time percentiles of
 * {@link ThroughputVsPoolSizeBenchmark#latencyCsv()}; the E2/E3 report tests pick the CSV up automatically.
 * Skipped when a requested database ({@code -Djmh.param.database}, PostgreSQL by default) is available
 * neither locally nor through Docker.
 *
 * @author Vlad Mihalcea
 */
public class ThroughputVsPoolSizeBenchmarkTest extends AbstractBenchmarkTest {

    @Override
    protected Class<?> benchmarkClass() {
        return ThroughputVsPoolSizeBenchmark.class;
    }

    @Override
    protected void checkPreconditions() {
        for (String database : expandParamValues(System.getProperty(PARAM_PREFIX + "database", "postgresql"))) {
            Database.valueOf(database.toUpperCase(Locale.ROOT)).assumeAvailable();
        }
    }

    /** The forks append to the latency CSV, so a previous run's rows are removed first. */
    @Override
    protected void beforeRun() {
        try {
            Files.deleteIfExists(ThroughputVsPoolSizeBenchmark.latencyCsv());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    protected int threads() {
        // A saturating client population well above the largest sampled pool size (96).
        return Integer.getInteger("jmh.threads", 128);
    }

    // The 10-size sweep is where the science lives (λ anchor at N=1, the peak between 24 and 48), so
    // spend the time budget on covering it rather than on extra iterations: 6 measurement iterations
    // over 2 forks (Cnt=12/point) already yields ~3-4% confidence intervals for this JDBC-bound workload.
    @Override
    protected int warmupIterations() {
        return Integer.getInteger("jmh.warmup", 3);
    }

    @Override
    protected int measurementIterations() {
        return Integer.getInteger("jmh.iters", 6);
    }

    @Override
    protected int iterationSeconds() {
        return Integer.getInteger("jmh.secs", 3);
    }

    @Override
    protected int forks() {
        return Integer.getInteger("jmh.forks", 2);
    }
}
