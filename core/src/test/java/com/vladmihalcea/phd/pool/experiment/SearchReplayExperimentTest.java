package com.vladmihalcea.phd.pool.experiment;

import com.vladmihalcea.phd.pool.controller.Policies;
import com.vladmihalcea.phd.pool.controller.ScalingPolicy;
import com.vladmihalcea.phd.pool.controller.WindowSample;
import com.vladmihalcea.phd.pool.report.ThroughputData;
import com.vladmihalcea.phd.pool.usl.SizingTarget;
import com.vladmihalcea.phd.pool.usl.UslFitter;
import com.vladmihalcea.phd.pool.util.CsvWriter;
import com.vladmihalcea.phd.pool.util.Statistics;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * K9: search-based tuning against the closed-form plateau start, replayed on measured curves, as Newsom (2022)
 * replayed his exhaustive grid. Each curve of the dense sweep (K1, {@code ThroughputVsPoolSizeBenchmark-dense-*.csv}
 * in {@code metrics/jmh-results/}; else the ICASC 10-size grid; else the synthetic ground truth) becomes a replayed
 * system: a window at pool size {@code N} returns the measured throughput, linearly interpolated between measured
 * sizes, times {@code 1 + noise * Z} (2% by default, {@code usl.replay.noise}). The pool is saturated (128
 * clients), so every window is a point on the curve.
 *
 * <p>Every policy runs {@code usl.replay.windows} windows (200) for {@code usl.replay.seeds} seeds (100):
 * the genetic algorithm, hill climbing, and the three USL controllers. Per run, against the best measured
 * throughput {@code X_best}, the measured plateau start {@code N_eps} (the smallest measured size within
 * {@code epsilon = 1%} of {@code X_best}), and the fitted target (the {@code usl.target}, by default the point of
 * diminishing returns {@code eta:0.1}, of the USL fitted to the whole measured curve):
 * <ul>
 *   <li>{@code windows_to_5pct}, {@code windows_to_10pct}: the first window from which the policy stays within 5%
 *       (10%) of {@code X_best} for 10 windows;</li>
 *   <li>{@code distinct_sizes}: how many pool sizes it measured, the cost Newsom counts;</li>
 *   <li>{@code final_size}, {@code final_share}: the median size of the last 20 windows and its true throughput
 *       share of {@code X_best};</li>
 *   <li>{@code final_to_plateau}, {@code final_to_target}: the final size over the measured {@code N_eps} and over
 *       the fitted target.</li>
 * </ul>
 * Output: {@code metrics/SearchReplay/search-replay-runs.csv} and {@code search-replay-summary.csv}.
 *
 * @author Vlad Mihalcea
 */
public class SearchReplayExperimentTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(SearchReplayExperimentTest.class);

    private static final String FIGURE = "SearchReplay";
    private static final List<String> POLICIES = Policies.parse(System.getProperty("usl.replay.policies",
            "genetic,hill-climb,usl-optimum,usl-plateau,usl-response-time"));
    private static final int WINDOWS = Integer.getInteger("usl.replay.windows", 200);
    private static final int SEEDS = Integer.getInteger("usl.replay.seeds", 100);
    private static final double NOISE = Double.parseDouble(System.getProperty("usl.replay.noise", "0.02"));
    private static final double TOLERANCE = Double.parseDouble(System.getProperty("usl.tolerance", "0.01"));
    private static final SizingTarget TARGET = SizingTarget.parse(System.getProperty("usl.target", "eta:0.1"));
    private static final int MIN = 1;
    private static final int CLIENTS = 128;

    /** A measured curve with its source, for example the database of the dense sweep. */
    record Source(String source, ThroughputData.Curve curve) {
    }

    @Test
    public void replaySearchOnMeasuredCurves() {
        List<Source> sources = loadCurves();
        assertFalse(sources.isEmpty(), "no curve to replay");
        try (CsvWriter runs = new CsvWriter(CsvWriter.metricsPath(FIGURE, "search-replay-runs.csv"), "source", "pool",
                "workload", "policy", "seed", "best_tps", "measured_plateau", "fitted_target", "windows_to_5pct",
                "windows_to_10pct", "distinct_sizes", "final_size", "final_share", "final_to_plateau",
                "final_to_target");
             CsvWriter summary = new CsvWriter(CsvWriter.metricsPath(FIGURE, "search-replay-summary.csv"), "source",
                     "pool", "workload", "policy", "runs", "measured_plateau", "fitted_target", "windows_to_5pct",
                     "reached_5pct",
                     "windows_to_10pct", "reached_10pct", "distinct_sizes", "distinct_sizes_ci95", "final_size",
                     "final_size_ci95", "final_share", "final_share_ci95", "final_to_plateau", "final_to_target")) {
            for (Source source : sources) {
                ThroughputData.Curve curve = source.curve();
                int max = (int) curve.poolSizes()[curve.poolSizes().length - 1];
                double best = Arrays.stream(curve.throughput()).max().orElseThrow();
                int plateau = measuredPlateau(curve, best);
                int target = fittedTarget(curve, max);
                for (String id : POLICIES) {
                    Statistics to5 = new Statistics();
                    Statistics to10 = new Statistics();
                    Statistics distinct = new Statistics();
                    Statistics finalSize = new Statistics();
                    Statistics finalShare = new Statistics();
                    int reached5 = 0;
                    int reached10 = 0;
                    for (int seed = 0; seed < SEEDS; seed++) {
                        Run run = replay(id, curve, max, best, seed);
                        runs.writeRow(source.source(), curve.pool(), curve.workload(), id, seed, best, plateau, target,
                                run.to5, run.to10, run.distinct, run.finalSize, run.finalShare,
                                run.finalSize / (double) plateau, run.finalSize / (double) target);
                        if (run.to5 >= 0) {
                            to5.add(run.to5);
                            reached5++;
                        }
                        if (run.to10 >= 0) {
                            to10.add(run.to10);
                            reached10++;
                        }
                        distinct.add(run.distinct);
                        finalSize.add(run.finalSize);
                        finalShare.add(run.finalShare);
                    }
                    summary.writeRow(source.source(), curve.pool(), curve.workload(), id, SEEDS, plateau, target,
                            to5.mean(),
                            reached5 / (double) SEEDS, to10.mean(), reached10 / (double) SEEDS, distinct.mean(),
                            distinct.ci95HalfWidthStudent(), finalSize.mean(), finalSize.ci95HalfWidthStudent(),
                            finalShare.mean(), finalShare.ci95HalfWidthStudent(), finalSize.mean() / plateau,
                            finalSize.mean() / target);
                    LOGGER.info("{} {}/{} {}: {} sizes measured, final {} (target {}, 1% plateau {}), {} of best, within"
                                    + " 5% after {} windows in {} of runs", source.source(), curve.pool(),
                            curve.workload(), id, String.format("%.1f", distinct.mean()),
                            String.format("%.1f", finalSize.mean()), target, plateau,
                            String.format("%.3f", finalShare.mean()), String.format("%.1f", to5.mean()),
                            String.format("%.2f", reached5 / (double) SEEDS));
                }
            }
        }
    }

    private record Run(int to5, int to10, int distinct, int finalSize, double finalShare) {
    }

    private static Run replay(String id, ThroughputData.Curve curve, int max, double best, int seed) {
        ScalingPolicy policy = Policies.create(id, MIN, max, TARGET, seed, 0);
        policy.reset();
        Random random = new Random(9_000L + seed);
        int size = Policies.initialSize(id, MIN);
        Set<Integer> visited = new HashSet<>();
        int[] sizes = new int[WINDOWS];
        for (int w = 0; w < WINDOWS; w++) {
            sizes[w] = size;
            visited.add(size);
            double throughput = interpolate(curve, size) * (1 + NOISE * random.nextGaussian());
            size = policy.decide(new WindowSample(size, Math.max(1, throughput), 0, CLIENTS - size, size, 0));
        }
        int[] tail = Arrays.copyOfRange(sizes, WINDOWS - 20, WINDOWS);
        Arrays.sort(tail);
        int finalSize = tail[tail.length / 2];
        return new Run(firstStable(curve, sizes, best, 0.05), firstStable(curve, sizes, best, 0.10), visited.size(),
                finalSize, interpolate(curve, finalSize) / best);
    }

    /** The first window from which the true throughput stays within {@code gap} of the best for 10 windows. */
    private static int firstStable(ThroughputData.Curve curve, int[] sizes, double best, double gap) {
        int run = 0;
        for (int w = 0; w < sizes.length; w++) {
            run = interpolate(curve, sizes[w]) >= (1 - gap) * best ? run + 1 : 0;
            if (run == 10) {
                return w - 9;
            }
        }
        return -1;
    }

    /** The target of the USL fitted to the whole measured curve, the reference the controllers aim at. */
    static int fittedTarget(ThroughputData.Curve curve, int max) {
        return TARGET.poolSize(UslFitter.fit(curve.poolSizes(), curve.throughput()).model(), max);
    }

    static int measuredPlateau(ThroughputData.Curve curve, double best) {
        for (int i = 0; i < curve.poolSizes().length; i++) {
            if (curve.throughput()[i] >= (1 - TOLERANCE) * best) {
                return (int) curve.poolSizes()[i];
            }
        }
        return (int) curve.poolSizes()[curve.poolSizes().length - 1];
    }

    static double interpolate(ThroughputData.Curve curve, double size) {
        double[] n = curve.poolSizes();
        double[] x = curve.throughput();
        if (size <= n[0]) {
            return x[0] * size / n[0];
        }
        for (int i = 1; i < n.length; i++) {
            if (size <= n[i]) {
                double t = (size - n[i - 1]) / (n[i] - n[i - 1]);
                return x[i - 1] + t * (x[i] - x[i - 1]);
            }
        }
        return x[x.length - 1];
    }

    private static List<Source> loadCurves() {
        Path results = CsvWriter.metricsPath("jmh-results", "x").getParent();
        List<Source> sources = new ArrayList<>();
        if (Files.isDirectory(results)) {
            try (Stream<Path> files = Files.list(results)) {
                for (Path file : files.filter(f -> f.getFileName().toString().matches(
                        "ThroughputVsPoolSizeBenchmark-dense-.*\\.csv") && !f.toString().endsWith("-latency.csv"))
                        .sorted().toList()) {
                    String name = file.getFileName().toString();
                    String source = name.substring("ThroughputVsPoolSizeBenchmark-".length(), name.length() - 4);
                    ThroughputData.loadMeasured(file).forEach(c -> sources.add(new Source(source, c)));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            if (sources.isEmpty() && Files.exists(results.resolve("ThroughputVsPoolSizeBenchmark.csv"))) {
                ThroughputData.loadMeasured(results.resolve("ThroughputVsPoolSizeBenchmark.csv"))
                        .forEach(c -> sources.add(new Source("icasc-grid", c)));
            }
        }
        if (sources.isEmpty()) {
            LOGGER.info("No measured E1/K1 curve found; replaying the synthetic ground truth");
            ThroughputData.synthesize(List.of("hikari", "dbcp2", "agroal"),
                            List.of("readMostly", "writeHeavy", "longTx"), 1L)
                    .forEach(c -> sources.add(new Source("synthetic", c)));
        }
        return sources;
    }
}
