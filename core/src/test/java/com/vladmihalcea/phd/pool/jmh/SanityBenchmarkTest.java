package com.vladmihalcea.phd.pool.jmh;

import com.vladmihalcea.phd.pool.common.util.AbstractBenchmarkTest;
import com.vladmihalcea.phd.pool.common.util.PostgreSqlSupport;

/**
 * Failsafe entry point for {@link SanityBenchmark}. Runs a short, few-thread pass just to prove the
 * harness. Skipped automatically when neither a local PostgreSQL server nor Docker is available.
 *
 * @author Vlad Mihalcea
 */
public class SanityBenchmarkTest extends AbstractBenchmarkTest {

    @Override
    protected Class<?> benchmarkClass() {
        return SanityBenchmark.class;
    }

    @Override
    protected void checkPreconditions() {
        PostgreSqlSupport.assumeDatabaseAvailable();
    }

    @Override
    protected int warmupIterations() {
        return Integer.getInteger("jmh.warmup", 1);
    }

    @Override
    protected int measurementIterations() {
        return Integer.getInteger("jmh.iters", 2);
    }

    @Override
    protected int iterationSeconds() {
        return Integer.getInteger("jmh.secs", 2);
    }

    @Override
    protected int forks() {
        return Integer.getInteger("jmh.forks", 1);
    }

    @Override
    protected int threads() {
        return Integer.getInteger("jmh.threads", 8);
    }
}
