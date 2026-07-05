package com.vladmihalcea.phd.pool.experiment;

import com.vladmihalcea.phd.pool.controller.ReactiveTimeoutPolicy;
import com.vladmihalcea.phd.pool.controller.ScalingPolicy;
import com.vladmihalcea.phd.pool.controller.StaticPolicy;
import com.vladmihalcea.phd.pool.controller.UslAutoScalingController;
import com.vladmihalcea.phd.pool.sim.PolicyRunner;
import com.vladmihalcea.phd.pool.sim.SimulatedSystem;
import com.vladmihalcea.phd.pool.usl.UslModel;
import com.vladmihalcea.phd.pool.util.CsvWriter;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Experiment E4: how each sizing policy converges on a stationary workload. Every policy starts from an
 * under-provisioned pool and is driven through the deterministic simulator; the per-window pool-size and
 * throughput trajectories (averaged over seeds) become the paper's convergence figures, and a summary
 * table reports steady-state throughput, steady-state pool size and time-to-95%-of-oracle.
 * <p>
 * Simulator-based so it is fully reproducible without Docker; the PostgreSQL-backed variant reuses the
 * identical policies and control loop over a live pool. The ground truth has {@code N* = 44}, below the
 * client-thread count, so the reactive policy visibly overshoots the throughput optimum.
 *
 * @author Vlad Mihalcea
 */
public class AutoScalingConvergenceExperimentTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(AutoScalingConvergenceExperimentTest.class);

    private static final int MIN = 2;
    private static final int MAX = 96;
    private static final int CLIENT_THREADS = 96;
    private static final int INITIAL = 4;
    private static final int WINDOWS = Integer.getInteger("usl.windows", 80);
    private static final int REPS = Integer.getInteger("usl.reps", 5);
    private static final long BASE_SEED = 4_000L;

    // Ground truth: N* = sqrt((1 - 0.01)/0.0005) = 44.
    private static final UslModel TRUTH = new UslModel(1500, 0.01, 0.0005);

    @Test
    public void measureConvergence() {
        int nStar = TRUTH.optimalPoolSize();
        double oraclePeak = TRUTH.peakThroughput();

        Map<String, Supplier<ScalingPolicy>> policies = new LinkedHashMap<>();
        policies.put("static-small", () -> new StaticPolicy("static-small", 8));
        policies.put("static-oracle", () -> new StaticPolicy("static-oracle", nStar));
        policies.put("static-large", () -> new StaticPolicy("static-large", MAX));
        policies.put("reactive", () -> new ReactiveTimeoutPolicy(
                MIN, MAX, new SimulatedSystem(TRUTH, CLIENT_THREADS, 0, 0).reactiveTimeoutNanos()));
        policies.put("usl", () -> new UslAutoScalingController(MIN, MAX));

        // policy -> per-window averaged pool size and throughput
        Map<String, double[]> avgSize = new LinkedHashMap<>();
        Map<String, double[]> avgTput = new LinkedHashMap<>();

        for (Map.Entry<String, Supplier<ScalingPolicy>> entry : policies.entrySet()) {
            double[] size = new double[WINDOWS];
            double[] tput = new double[WINDOWS];
            for (int rep = 0; rep < REPS; rep++) {
                SimulatedSystem system = new SimulatedSystem(TRUTH, CLIENT_THREADS, 0.02, BASE_SEED + rep);
                List<PolicyRunner.Step> trajectory =
                        PolicyRunner.run(entry.getValue().get(), system, INITIAL, WINDOWS, -1, null);
                for (int w = 0; w < WINDOWS; w++) {
                    size[w] += trajectory.get(w).poolSize();
                    tput[w] += trajectory.get(w).throughput();
                }
            }
            for (int w = 0; w < WINDOWS; w++) {
                size[w] /= REPS;
                tput[w] /= REPS;
            }
            avgSize.put(entry.getKey(), size);
            avgTput.put(entry.getKey(), tput);
        }

        writeWide("Convergence", "convergence-poolsize.csv", policies.keySet(), avgSize);
        writeWide("Convergence", "convergence-tps.csv", policies.keySet(), avgTput);
        writeSummary(policies.keySet(), avgTput, avgSize, oraclePeak, nStar);
        writeGnuplot();

        LOGGER.info("E4 convergence: N*={}, oracle peak throughput={}", nStar, oraclePeak);
    }

    private void writeWide(String figure, String file, Iterable<String> policies, Map<String, double[]> data) {
        List<String> header = new ArrayList<>();
        header.add("window");
        policies.forEach(header::add);
        Path csv = CsvWriter.metricsPath(figure, file);
        try (CsvWriter out = new CsvWriter(csv, header.toArray(new String[0]))) {
            for (int w = 0; w < WINDOWS; w++) {
                Object[] row = new Object[header.size()];
                row[0] = w;
                int c = 1;
                for (String policy : policies) {
                    row[c++] = data.get(policy)[w];
                }
                out.writeRow(row);
            }
        }
    }

    private void writeSummary(Iterable<String> policies, Map<String, double[]> tput,
                              Map<String, double[]> size, double oraclePeak, int nStar) {
        Path csv = CsvWriter.metricsPath("Convergence", "convergence-summary.csv");
        try (CsvWriter out = new CsvWriter(csv,
                "policy", "steady_tps", "steady_size", "pct_of_oracle_tps", "time_to_95pct_oracle")) {
            for (String policy : policies) {
                double[] t = tput.get(policy);
                double[] s = size.get(policy);
                double steadyTps = tailMean(t, 15);
                double steadySize = tailMean(s, 15);
                int timeTo95 = -1;
                for (int w = 0; w < WINDOWS; w++) {
                    if (t[w] >= 0.95 * oraclePeak) {
                        timeTo95 = w;
                        break;
                    }
                }
                out.writeRow(policy, steadyTps, steadySize,
                        100.0 * steadyTps / oraclePeak,
                        timeTo95 < 0 ? "never" : timeTo95);
            }
        }
        LOGGER.info("Wrote convergence summary (oracle N*={})", nStar);
    }

    private static double tailMean(double[] values, int tail) {
        int from = Math.max(0, values.length - tail);
        double sum = 0;
        int n = 0;
        for (int i = from; i < values.length; i++) {
            sum += values[i];
            n++;
        }
        return sum / n;
    }

    private void writeGnuplot() {
        String sizeGp = """
                # Experiment E4: configured pool size vs. time per policy (stationary workload).
                # Input:  convergence-poolsize.csv (window, static-small, static-oracle, static-large, reactive, usl)
                # Output: convergence-poolsize.svg / .pdf
                set datafile separator ","
                set title "Pool size convergence per policy" font ",13"
                set xlabel "Observation window"
                set ylabel "Configured pool size"
                set grid xtics ytics lt 0 lw 0.5 lc rgb "#cccccc"
                set key outside right top vertical
                set style line 1 lw 2 lc rgb "#90A4AE"
                set style line 2 lw 2 lc rgb "#2E7D32"
                set style line 3 lw 2 lc rgb "#C62828"
                set style line 4 lw 2 lc rgb "#F9A825"
                set style line 5 lw 2 lc rgb "#1565C0"
                set terminal svg enhanced font "arial,11" size 1000,560
                set output 'convergence-poolsize.svg'
                plot for [i=2:6] 'convergence-poolsize.csv' using 1:i with lines ls (i-1) title columnheader
                set terminal pdfcairo enhanced font "arial,10" size 8,4.5
                set output 'convergence-poolsize.pdf'
                replot
                set output
                """;
        writeFile(CsvWriter.metricsPath("Convergence", "convergence-poolsize.gp"), sizeGp);

        String tpsGp = """
                # Experiment E4: throughput vs. time per policy (stationary workload).
                # Input:  convergence-tps.csv (window, static-small, static-oracle, static-large, reactive, usl)
                # Output: convergence-tps.svg / .pdf
                set datafile separator ","
                set title "Throughput convergence per policy" font ",13"
                set xlabel "Observation window"
                set ylabel "Throughput (tx/s)"
                set grid xtics ytics lt 0 lw 0.5 lc rgb "#cccccc"
                set key outside right bottom vertical
                set style line 1 lw 2 lc rgb "#90A4AE"
                set style line 2 lw 2 lc rgb "#2E7D32"
                set style line 3 lw 2 lc rgb "#C62828"
                set style line 4 lw 2 lc rgb "#F9A825"
                set style line 5 lw 2 lc rgb "#1565C0"
                set terminal svg enhanced font "arial,11" size 1000,560
                set output 'convergence-tps.svg'
                plot for [i=2:6] 'convergence-tps.csv' using 1:i with lines ls (i-1) title columnheader
                set terminal pdfcairo enhanced font "arial,10" size 8,4.5
                set output 'convergence-tps.pdf'
                replot
                set output
                """;
        writeFile(CsvWriter.metricsPath("Convergence", "convergence-tps.gp"), tpsGp);
    }

    private static void writeFile(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
        } catch (Exception e) {
            throw new IllegalStateException("Could not write " + path, e);
        }
    }
}
