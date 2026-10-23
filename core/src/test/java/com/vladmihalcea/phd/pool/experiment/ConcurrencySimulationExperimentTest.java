package com.vladmihalcea.phd.pool.experiment;

import com.vladmihalcea.phd.pool.controller.Policies;
import com.vladmihalcea.phd.pool.controller.ScalingPolicy;
import com.vladmihalcea.phd.pool.controller.WindowSample;
import com.vladmihalcea.phd.pool.sim.SimulatedSystem;
import com.vladmihalcea.phd.pool.usl.SizingTarget;
import com.vladmihalcea.phd.pool.usl.UslModel;
import com.vladmihalcea.phd.pool.util.CsvWriter;
import com.vladmihalcea.phd.pool.util.Statistics;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L3 on the simulator: every policy of {@link Policies} under the concurrency profile of the live experiment
 * ({@link ConcurrencyAdaptationExperimentTest}), 8, 32, 128, 64, and 16 closed-loop clients for 60 windows each,
 * on a curve with the ICASC shape ({@code N* = 44}). The best a pool can do at {@code M} clients is
 * {@code X(min(M, N*))}; the reference size is the target of the curve ({@code usl.target}, by default the point of
 * diminishing returns {@code eta:0.1}), or {@code M} when the clients need fewer connections. Deterministic and
 * Docker-free, so it checks the policies before the server campaign and gives the expected shape of the live
 * figure.
 *
 * <p>Outputs in {@code metrics/ConcurrencySimulation/}: {@code trajectory.csv} (mean pool size and throughput per
 * window and policy over the repetitions) and {@code summary.csv} (per policy and phase: steady pool size, its
 * ratio to the reference size, and the throughput share of the best, with 95% intervals).
 *
 * @author Vlad Mihalcea
 */
public class ConcurrencySimulationExperimentTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConcurrencySimulationExperimentTest.class);

    static final UslModel TRUTH = new UslModel(1500, 0.03, 0.0005);
    private static final int[] CLIENTS = {8, 32, 128, 64, 16};
    private static final int WINDOWS_PER_PHASE = Integer.getInteger("usl.windows", 60);
    private static final int REPS = Integer.getInteger("usl.reps", 30);
    private static final int MIN = 2;
    private static final int MAX = 96;
    private static final SizingTarget TARGET = SizingTarget.parse(System.getProperty("usl.target", "eta:0.1"));
    private static final String FIGURE = "ConcurrencySimulation";

    @Test
    public void policiesFollowTheClientConcurrency() {
        List<String> policies = Policies.parse(ConcurrencyAdaptationExperimentTest.DEFAULT_POLICIES + ",static-"
                + TRUTH.optimalPoolSize() + ",static-" + TARGET.poolSize(TRUTH, MAX));
        int windows = CLIENTS.length * WINDOWS_PER_PHASE;
        long reactiveTimeout = new SimulatedSystem(TRUTH, 1, 0, 0).reactiveTimeoutNanos();

        Map<String, double[][]> trajectories = new LinkedHashMap<>();
        Map<String, Statistics[][]> phaseStats = new LinkedHashMap<>();
        for (String id : policies) {
            double[][] trajectory = new double[2][windows];
            Statistics[][] stats = new Statistics[CLIENTS.length][3];
            for (Statistics[] row : stats) {
                for (int k = 0; k < 3; k++) {
                    row[k] = new Statistics();
                }
            }
            for (int rep = 0; rep < REPS; rep++) {
                ScalingPolicy policy = Policies.create(id, MIN, MAX, TARGET, 100L + rep, reactiveTimeout);
                policy.reset();
                SimulatedSystem system = new SimulatedSystem(TRUTH, CLIENTS[0], 0.02, 5_000L + rep);
                int size = Policies.initialSize(id, MIN);
                double[][] phaseSums = new double[CLIENTS.length][2];
                for (int w = 0; w < windows; w++) {
                    int phase = w / WINDOWS_PER_PHASE;
                    system.setClientThreads(CLIENTS[phase]);
                    WindowSample sample = system.sample(size);
                    trajectory[0][w] += size / (double) REPS;
                    trajectory[1][w] += sample.throughput() / REPS;
                    if (w % WINDOWS_PER_PHASE >= WINDOWS_PER_PHASE / 2) {
                        phaseSums[phase][0] += size;
                        phaseSums[phase][1] += sample.throughput();
                    }
                    size = policy.decide(sample);
                }
                for (int p = 0; p < CLIENTS.length; p++) {
                    double steadyWindows = WINDOWS_PER_PHASE - WINDOWS_PER_PHASE / 2;
                    double steadySize = phaseSums[p][0] / steadyWindows;
                    double steadyTput = phaseSums[p][1] / steadyWindows;
                    stats[p][0].add(steadySize);
                    stats[p][1].add(steadySize / referenceSize(CLIENTS[p]));
                    stats[p][2].add(steadyTput / bestThroughput(CLIENTS[p]));
                }
            }
            trajectories.put(id, trajectory);
            phaseStats.put(id, stats);
        }

        try (CsvWriter out = new CsvWriter(CsvWriter.metricsPath(FIGURE, "trajectory.csv"), trajectoryHeader(policies))) {
            for (int w = 0; w < windows; w++) {
                Object[] row = new Object[2 + 2 * policies.size()];
                row[0] = w;
                row[1] = CLIENTS[w / WINDOWS_PER_PHASE];
                int c = 2;
                for (String id : policies) {
                    row[c++] = trajectories.get(id)[0][w];
                }
                for (String id : policies) {
                    row[c++] = trajectories.get(id)[1][w];
                }
                out.writeRow(row);
            }
        }
        try (CsvWriter out = new CsvWriter(CsvWriter.metricsPath(FIGURE, "summary.csv"), "policy", "phase", "clients",
                "reference_size", "steady_pool", "steady_pool_ci95", "pool_to_reference", "pool_to_reference_ci95",
                "tps_share_of_best", "tps_share_ci95")) {
            for (String id : policies) {
                Statistics[][] stats = phaseStats.get(id);
                for (int p = 0; p < CLIENTS.length; p++) {
                    out.writeRow(id, p, CLIENTS[p], referenceSize(CLIENTS[p]), stats[p][0].mean(),
                            stats[p][0].ci95HalfWidthStudent(), stats[p][1].mean(), stats[p][1].ci95HalfWidthStudent(),
                            stats[p][2].mean(), stats[p][2].ci95HalfWidthStudent());
                }
                LOGGER.info("{}: {}", id, describe(stats));
            }
        }

        // The response-time controller should hold near the plateau start under saturation (128 clients) and
        // give back connections when the clients drop to 16.
        Statistics[][] rt = phaseStats.get("usl-response-time");
        assertTrue(rt[2][2].mean() >= 0.95, "usl-response-time throughput share at 128 clients: " + rt[2][2].mean());
        assertTrue(rt[2][0].mean() < TRUTH.optimalPoolSize(),
                "usl-response-time should hold fewer than N* at 128 clients: " + rt[2][0].mean());
        assertTrue(rt[4][0].mean() < rt[2][0].mean(), "usl-response-time should shrink when the clients drop");
    }

    private static String[] trajectoryHeader(List<String> policies) {
        String[] header = new String[2 + 2 * policies.size()];
        header[0] = "window";
        header[1] = "clients";
        int c = 2;
        for (String id : policies) {
            header[c++] = id + "_pool";
        }
        for (String id : policies) {
            header[c++] = id + "_tps";
        }
        return header;
    }

    private static String describe(Statistics[][] stats) {
        StringBuilder text = new StringBuilder();
        for (int p = 0; p < CLIENTS.length; p++) {
            text.append(String.format(Locale.ROOT, "[%d: pool %.1f, %.3f of best] ", CLIENTS[p], stats[p][0].mean(),
                    stats[p][2].mean()));
        }
        return text.toString();
    }

    /** The target size at {@code clients} clients: the curve's target, or the clients themselves below it. */
    static int referenceSize(int clients) {
        return Math.min(clients, TARGET.poolSize(TRUTH, MAX));
    }

    static double bestThroughput(int clients) {
        return TRUTH.throughputAt(Math.min(clients, TRUTH.optimalPoolSizeReal()));
    }
}
