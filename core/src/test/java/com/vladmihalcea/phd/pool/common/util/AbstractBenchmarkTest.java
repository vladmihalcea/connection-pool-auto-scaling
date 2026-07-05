package com.vladmihalcea.phd.pool.common.util;

import com.vladmihalcea.phd.pool.util.CsvWriter;
import org.junit.jupiter.api.Test;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * JUnit entry point that runs a JMH benchmark class through the failsafe plugin, mirroring the
 * dto-entity-auto-sync convention (a {@code *BenchmarkTest} wrapper per {@code *Benchmark}). Results are
 * additionally persisted as JSON and as a tidy long-format CSV under {@code metrics/jmh-results/} so the
 * gnuplot scripts can consume them.
 *
 * @author Vlad Mihalcea
 */
public abstract class AbstractBenchmarkTest {

    protected final Logger LOGGER = LoggerFactory.getLogger(getClass());

    protected abstract Class<?> benchmarkClass();

    // Defaults are publication-grade; a quick pass can shrink them, e.g. -Djmh.forks=1 -Djmh.iters=1 -Djmh.secs=1.
    protected int warmupIterations() {
        return Integer.getInteger("jmh.warmup", 5);
    }

    protected int measurementIterations() {
        return Integer.getInteger("jmh.iters", 10);
    }

    protected int iterationSeconds() {
        return Integer.getInteger("jmh.secs", 10);
    }

    protected int forks() {
        return Integer.getInteger("jmh.forks", 2);
    }

    /** Throughput sweeps report ops/s; per-acquire latency benchmarks override to a time mode. */
    protected TimeUnit timeUnit() {
        return TimeUnit.SECONDS;
    }

    /** Number of concurrent client threads driving the pool; overridable per benchmark. */
    protected int threads() {
        return Integer.getInteger("jmh.threads", 128);
    }

    /**
     * Precondition check run in the main JVM before JMH forks. DB-backed benchmarks override this to
     * call {@code PostgreSqlSupport.assumeDatabaseAvailable()} so they are skipped (not failed) when
     * neither a local PostgreSQL server nor Docker is reachable.
     */
    protected void checkPreconditions() {
    }

    @Test
    public void runBenchmarks() throws RunnerException {
        checkPreconditions();
        Collection<RunResult> results = execute(resultFile());
        writeCsvSummary(results);
        LOGGER.info("Completed {} result(s) for {}", results.size(), benchmarkClass().getSimpleName());
    }

    /**
     * Emits a tidy long-format CSV (one row per benchmark result, with every JMH {@code @Param} as its
     * own column) straight from the {@link RunResult}s, so no JSON post-processing step is needed before
     * the gnuplot scripts consume it.
     */
    private void writeCsvSummary(Collection<RunResult> results) {
        if (results.isEmpty()) {
            return;
        }
        Set<String> paramKeys = new LinkedHashSet<>();
        for (RunResult result : results) {
            if (result.getParams().getParamsKeys() != null) {
                paramKeys.addAll(result.getParams().getParamsKeys());
            }
        }
        List<String> header = new ArrayList<>();
        header.add("benchmark");
        header.add("mode");
        header.addAll(paramKeys);
        header.add("threads");
        header.add("score");
        header.add("score_error");
        header.add("unit");

        Path csv = CsvWriter.metricsPath("jmh-results", benchmarkClass().getSimpleName() + ".csv");
        try (CsvWriter out = new CsvWriter(csv, header.toArray(new String[0]))) {
            for (RunResult result : results) {
                List<Object> row = new ArrayList<>();
                String fqn = result.getParams().getBenchmark();
                row.add(fqn.substring(fqn.lastIndexOf('.') + 1));
                row.add(result.getParams().getMode().shortLabel());
                for (String key : paramKeys) {
                    row.add(result.getParams().getParam(key));
                }
                row.add(result.getParams().getThreads());
                row.add(result.getPrimaryResult().getScore());
                row.add(result.getPrimaryResult().getScoreError());
                row.add(result.getPrimaryResult().getScoreUnit());
                out.writeRow(row.toArray());
            }
        }
        LOGGER.info("Wrote JMH summary CSV to {}", csv);
    }

    private Collection<RunResult> execute(Path resultFile) throws RunnerException {
        OptionsBuilder builder = new OptionsBuilder();
        builder.include(benchmarkClass().getSimpleName() + "\\..*")
                .warmupIterations(warmupIterations())
                .warmupTime(TimeValue.seconds(iterationSeconds()))
                .measurementIterations(measurementIterations())
                .measurementTime(TimeValue.seconds(iterationSeconds()))
                .timeUnit(timeUnit())
                .threads(threads())
                .forks(forks())
                .shouldFailOnError(true);
        if (resultFile != null) {
            builder.resultFormat(ResultFormatType.JSON)
                    .result(resultFile.toString());
        }
        Options opts = builder.build();
        return new Runner(opts).run();
    }

    private Path resultFile() {
        try {
            Path base = Path.of("").toAbsolutePath();
            Path metrics = base.resolve("metrics");
            if (!Files.exists(metrics) && base.getParent() != null) {
                metrics = base.getParent().resolve("metrics");
            }
            Path jmhResults = metrics.resolve("jmh-results");
            Files.createDirectories(jmhResults);
            return jmhResults.resolve(benchmarkClass().getSimpleName() + ".json");
        } catch (Exception e) {
            LOGGER.warn("Could not prepare JMH result file, continuing without it", e);
            return null;
        }
    }
}
