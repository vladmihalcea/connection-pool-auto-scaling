package com.vladmihalcea.phd.pool.jmh;

import com.vladmihalcea.phd.pool.common.util.AbstractBenchmarkTest;
import com.vladmihalcea.phd.pool.common.util.PostgreSqlSupport;

/**
 * Failsafe entry point for {@link ThroughputVsPoolSizeBenchmark} (experiment E1). Writes JSON + a tidy
 * long-format CSV under {@code metrics/jmh-results/}; the E2/E3 report tests pick that CSV up
 * automatically. Skipped when neither a local PostgreSQL server nor Docker is available.
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
        PostgreSqlSupport.assumeDatabaseAvailable();
    }

    @Override
    protected int threads() {
        // A saturating client population well above the largest sampled pool size (96).
        return Integer.getInteger("jmh.threads", 128);
    }
}
