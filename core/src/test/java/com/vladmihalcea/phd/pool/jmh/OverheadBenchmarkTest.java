package com.vladmihalcea.phd.pool.jmh;

import com.vladmihalcea.phd.pool.common.util.AbstractBenchmarkTest;

import java.util.concurrent.TimeUnit;

/**
 * Failsafe entry point for {@link OverheadBenchmark} (experiment E6). No Docker required — the measured
 * costs are pure CPU (controller decision and USL refit). Single-threaded, short.
 *
 * @author Vlad Mihalcea
 */
public class OverheadBenchmarkTest extends AbstractBenchmarkTest {

    @Override
    protected Class<?> benchmarkClass() {
        return OverheadBenchmark.class;
    }

    @Override
    protected TimeUnit timeUnit() {
        return TimeUnit.NANOSECONDS;
    }

    @Override
    protected int threads() {
        return 1;
    }

    @Override
    protected int forks() {
        return Integer.getInteger("jmh.forks", 1);
    }

    @Override
    protected int warmupIterations() {
        return Integer.getInteger("jmh.warmup", 3);
    }

    @Override
    protected int measurementIterations() {
        return Integer.getInteger("jmh.iters", 5);
    }

    @Override
    protected int iterationSeconds() {
        return Integer.getInteger("jmh.secs", 2);
    }
}
