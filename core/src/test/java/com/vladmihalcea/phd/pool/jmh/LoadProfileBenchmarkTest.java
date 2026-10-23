package com.vladmihalcea.phd.pool.jmh;

import com.vladmihalcea.phd.pool.common.util.AbstractBenchmarkTest;
import com.vladmihalcea.phd.pool.common.util.Database;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.Locale;

/**
 * Failsafe entry point for {@link LoadProfileBenchmark} (L4): every policy under increasing, steady, and shrinking
 * load. The measurement iterations are the profile's levels times {@code usl.profile.itersPerLevel} (5 x 4 = 20 by
 * default), of {@code jmh.secs} (5) seconds each, after {@code jmh.warmup} (2) warm-up iterations at the first
 * level; JMH runs as many threads as the largest level. Narrow it with {@code -Djmh.param.policy=...},
 * {@code -Djmh.param.profile=...}, and {@code -Djmh.param.workload=...}. The per-iteration rows of an earlier run are
 * deleted first.
 *
 * @author Vlad Mihalcea
 */
public class LoadProfileBenchmarkTest extends AbstractBenchmarkTest {

    @Override
    protected Class<?> benchmarkClass() {
        return LoadProfileBenchmark.class;
    }

    @Override
    protected void checkPreconditions() {
        for (String database : expandParamValues(System.getProperty(PARAM_PREFIX + "database", "postgresql"))) {
            Database.valueOf(database.toUpperCase(Locale.ROOT)).assumeAvailable();
        }
    }

    @Override
    protected void beforeRun() {
        try {
            Files.deleteIfExists(LoadProfileBenchmark.iterationsCsv());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    protected int threads() {
        return LoadProfileBenchmark.maxClients();
    }

    @Override
    protected int warmupIterations() {
        return Integer.getInteger("jmh.warmup", 2);
    }

    /** Fixed by the profile, so every profile has the same number of iterations per fork. */
    @Override
    protected int measurementIterations() {
        return LoadProfileBenchmark.LEVELS.size() * LoadProfileBenchmark.ITERS_PER_LEVEL;
    }

    @Override
    protected int iterationSeconds() {
        return Integer.getInteger("jmh.secs", 5);
    }

    @Override
    protected int forks() {
        return Integer.getInteger("jmh.forks", 10);
    }
}
