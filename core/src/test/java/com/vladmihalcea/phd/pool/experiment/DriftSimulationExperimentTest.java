package com.vladmihalcea.phd.pool.experiment;

import com.vladmihalcea.phd.pool.controller.Policies;
import com.vladmihalcea.phd.pool.controller.ScalingPolicy;
import com.vladmihalcea.phd.pool.controller.UslPlateauController;
import com.vladmihalcea.phd.pool.controller.UslResponseTimeController;
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
import java.util.Map;
import java.util.function.IntFunction;

/**
 * The drift half of RQ4 on the simulator: does a plateau-seeking controller keep the plateau start while the
 * throughput level drifts, as the TPC-C-derived load drifts on PostgreSQL (from about 2,500 to 700 transactions per
 * second in 90 s at a fixed pool size), without restarting its exploration? The draft's controller answers every
 * drift with a flush and a new probe ladder; with {@code @drift:rescale} ({@link Policies#DRIFT_RESCALE}) it
 * rescales its history instead, which is enough whenever only the level moves, since the target depends on
 * {@code sigma} and {@code kappa} and not on {@code lambda}.
 * <p>
 * Three scenarios of {@code usl.windows} (300) one-second windows, 96 closed-loop clients, 2% noise, the
 * ICASC HikariCP read-mostly shape ({@code sigma = 0.12}, {@code kappa = 4.3e-4}, {@code N* = 45}):
 * <ul>
 *   <li>{@code decay}: {@code lambda} falls smoothly by 70% over the run, the TPC-C drift;</li>
 *   <li>{@code steps}: {@code lambda} drops by 30% at a third and at two thirds of the run;</li>
 *   <li>{@code shape}: at half the run the shape changes ({@code sigma = 0.2}, {@code kappa = 1.2e-3},
 *       {@code N* = 26}), which no rescaling explains, so the rescaling controllers must fall back to the flush.</li>
 * </ul>
 * Each of {@code usl-plateau} and {@code usl-response-time} runs as it is and with {@code @drift:rescale}, next to
 * the {@code N*} controller, over {@code usl.reps} (5) seeds. Per policy and scenario: the mean pool size, the share
 * of the windows within 3 connections of the true point of diminishing returns of the moment, the mean share of the
 * moment's best throughput, the rescales, and the windows spent exploring. Outputs:
 * {@code metrics/DriftSimulation/drift-summary.csv} and {@code drift-series.csv} (the mean trajectory).
 *
 * @author Vlad Mihalcea
 */
public class DriftSimulationExperimentTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(DriftSimulationExperimentTest.class);

    private static final int MIN = 2;
    private static final int MAX = 96;
    private static final int CLIENTS = 96;
    private static final int WINDOWS = Integer.getInteger("usl.windows", 300);
    private static final int REPS = Integer.getInteger("usl.reps", 5);
    private static final long BASE_SEED = 7_100L;
    private static final double LAMBDA = 7_000;
    private static final UslModel SHAPE = new UslModel(LAMBDA, 0.12, 4.3e-4);
    private static final UslModel CHANGED_SHAPE = new UslModel(LAMBDA * 0.6, 0.2, 1.2e-3);
    private static final SizingTarget TARGET = SizingTarget.DEFAULT;

    static final List<String> POLICIES = List.of("usl-optimum", "usl-plateau", "usl-plateau@" + Policies.DRIFT_RESCALE,
            "usl-response-time", "usl-response-time@" + Policies.DRIFT_RESCALE);

    /** The true curve of every window. */
    static Map<String, IntFunction<UslModel>> scenarios() {
        Map<String, IntFunction<UslModel>> scenarios = new LinkedHashMap<>();
        scenarios.put("decay", w -> scaled(Math.pow(0.3, w / (double) WINDOWS)));
        scenarios.put("steps", w -> scaled(w < WINDOWS / 3 ? 1.0 : w < 2 * WINDOWS / 3 ? 0.7 : 0.49));
        scenarios.put("shape", w -> w < WINDOWS / 2 ? SHAPE : CHANGED_SHAPE);
        return scenarios;
    }

    private static UslModel scaled(double factor) {
        return new UslModel(SHAPE.lambda() * factor, SHAPE.sigma(), SHAPE.kappa());
    }

    @Test
    public void measureDrift() {
        try (CsvWriter summary = new CsvWriter(CsvWriter.metricsPath("DriftSimulation", "drift-summary.csv"),
                "scenario", "policy", "reps", "mean_pool_size", "on_target_share", "throughput_share",
                "throughput_share_ci95", "rescales", "exploring_windows");
             CsvWriter series = new CsvWriter(CsvWriter.metricsPath("DriftSimulation", "drift-series.csv"),
                     "scenario", "policy", "window", "true_target", "true_nstar", "mean_pool_size",
                     "mean_throughput_share")) {
            for (Map.Entry<String, IntFunction<UslModel>> scenario : scenarios().entrySet()) {
                for (String policyId : POLICIES) {
                    double[] size = new double[WINDOWS];
                    double[] share = new double[WINDOWS];
                    Statistics tputShare = new Statistics();
                    Statistics onTarget = new Statistics();
                    Statistics meanSize = new Statistics();
                    Statistics rescales = new Statistics();
                    Statistics flushes = new Statistics();
                    for (int rep = 0; rep < REPS; rep++) {
                        Result r = run(scenario.getValue(), policyId, BASE_SEED + rep);
                        for (int w = 0; w < WINDOWS; w++) {
                            size[w] += r.size[w] / REPS;
                            share[w] += r.share[w] / REPS;
                        }
                        tputShare.add(r.meanShare);
                        onTarget.add(r.onTargetShare);
                        meanSize.add(r.meanSize);
                        rescales.add(r.rescales);
                        flushes.add(r.exploringWindows);
                    }
                    summary.writeRow(scenario.getKey(), policyId, REPS, meanSize.mean(), onTarget.mean(),
                            tputShare.mean(), tputShare.ci95HalfWidth(), rescales.mean(), flushes.mean());
                    for (int w = 0; w < WINDOWS; w++) {
                        UslModel truth = scenario.getValue().apply(w);
                        series.writeRow(scenario.getKey(), policyId, w, TARGET.poolSize(truth, MAX),
                                truth.optimalPoolSize(), size[w], share[w]);
                    }
                    LOGGER.info("{} {}: pool {}, on target {}% of the windows, {}% of the best throughput, "
                                    + "{} rescales, {} windows exploring", scenario.getKey(), policyId,
                            String.format("%.1f", meanSize.mean()), String.format("%.0f", 100 * onTarget.mean()),
                            String.format("%.1f", 100 * tputShare.mean()), String.format("%.1f", rescales.mean()),
                            String.format("%.0f", flushes.mean()));
                }
            }
        }
    }

    private record Result(double[] size, double[] share, double meanSize, double onTargetShare, double meanShare,
                          int rescales, int exploringWindows) {
    }

    /** One seeded run of a policy through a scenario. */
    static Result run(IntFunction<UslModel> scenario, String policyId, long seed) {
        ScalingPolicy policy = Policies.create(policyId, MIN, MAX, TARGET, seed, 1);
        SimulatedSystem system = new SimulatedSystem(scenario.apply(0), CLIENTS, 0.02, seed);
        double[] size = new double[WINDOWS];
        double[] share = new double[WINDOWS];
        int current = 4;
        int onTarget = 0;
        int exploring = 0;
        double sizeSum = 0;
        double shareSum = 0;
        for (int w = 0; w < WINDOWS; w++) {
            UslModel truth = scenario.apply(w);
            system.setTruth(truth);
            WindowSample sample = system.sample(current);
            size[w] = current;
            share[w] = truth.throughputAt(current) / truth.peakThroughput();
            sizeSum += current;
            shareSum += share[w];
            if (Math.abs(current - TARGET.poolSize(truth, MAX)) <= 3) {
                onTarget++;
            }
            String state = policy.state();
            if ("exploring".equals(state) || "sweep".equals(state)) {
                exploring++;
            }
            current = Math.max(MIN, Math.min(MAX, policy.decide(sample)));
        }
        ScalingPolicy inner = Policies.unwrap(policy);
        int rescales = inner instanceof UslPlateauController p ? p.rescales()
                : inner instanceof UslResponseTimeController r ? r.rescales() : 0;
        return new Result(size, share, sizeSum / WINDOWS, onTarget / (double) WINDOWS, shareSum / WINDOWS, rescales,
                exploring);
    }
}
