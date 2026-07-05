package com.vladmihalcea.phd.pool.experiment;

import com.vladmihalcea.phd.pool.controller.ReactiveTimeoutPolicy;
import com.vladmihalcea.phd.pool.controller.ScalingPolicy;
import com.vladmihalcea.phd.pool.controller.StaticPolicy;
import com.vladmihalcea.phd.pool.controller.ThroughputProbePolicy;
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
 * Experiment E5: response to a workload regime change. A long-service-time phase (optimum {@code N*=81})
 * gives way, mid-run, to a shorter-service, higher-coherency phase (optimum {@code N*=37}). The figure
 * makes the paper's sharpest point: the USL controller re-estimates the operating curve and <em>shrinks</em>
 * the pool to the new optimum, the static "oracle for phase 1" is now badly over-sized, and the reactive
 * grow-only policy — having climbed high chasing latency — cannot come back down.
 * <p>
 * Simulator-based and fully reproducible without Docker.
 *
 * @author Vlad Mihalcea
 */
public class RegimeShiftExperimentTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(RegimeShiftExperimentTest.class);

    private static final int MIN = 2;
    private static final int MAX = 96;
    private static final int CLIENT_THREADS = 96;
    private static final int INITIAL = 4;
    private static final int WINDOWS = Integer.getInteger("usl.windows", 120);
    private static final int SHIFT_AT = WINDOWS / 2;
    private static final int REPS = Integer.getInteger("usl.reps", 5);
    private static final long BASE_SEED = 5_000L;

    private static final UslModel PHASE1 = new UslModel(1500, 0.01, 0.00015); // N* = 81
    private static final UslModel PHASE2 = new UslModel(1500, 0.02, 0.0007);  // N* = 37

    @Test
    public void measureRegimeShift() {
        int nStar1 = PHASE1.optimalPoolSize();
        int nStar2 = PHASE2.optimalPoolSize();

        Map<String, Supplier<ScalingPolicy>> policies = new LinkedHashMap<>();
        policies.put("static-oracle-phase1", () -> new StaticPolicy("static-oracle-phase1", nStar1));
        policies.put("reactive", () -> new ReactiveTimeoutPolicy(
                MIN, MAX, new SimulatedSystem(PHASE1, CLIENT_THREADS, 0, 0).reactiveTimeoutNanos()));
        // Model-free baseline: it too re-tracks after the shift (flushes its history and re-climbs, so it
        // can shrink), unlike grow-only reactive — but it must re-explore the new curve by visiting sizes,
        // where the USL controller re-fits and jumps to the new N*. This is the sharpest USL-vs-model-free
        // contrast: both recover, but with different exploration cost.
        policies.put("probe", () -> new ThroughputProbePolicy(MIN, MAX));
        policies.put("usl", () -> new UslAutoScalingController(MIN, MAX));

        Map<String, double[]> avgSize = new LinkedHashMap<>();
        Map<String, double[]> avgTput = new LinkedHashMap<>();

        for (Map.Entry<String, Supplier<ScalingPolicy>> entry : policies.entrySet()) {
            double[] size = new double[WINDOWS];
            double[] tput = new double[WINDOWS];
            for (int rep = 0; rep < REPS; rep++) {
                // Each rep starts in phase 1; the runner swaps to phase 2 at SHIFT_AT.
                SimulatedSystem system = new SimulatedSystem(PHASE1, CLIENT_THREADS, 0.02, BASE_SEED + rep);
                List<PolicyRunner.Step> trajectory =
                        PolicyRunner.run(entry.getValue().get(), system, INITIAL, WINDOWS, SHIFT_AT, PHASE2);
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

        writeWide("regime-poolsize.csv", policies.keySet(), avgSize);
        writeWide("regime-tps.csv", policies.keySet(), avgTput);
        writeSummary(policies.keySet(), avgSize, avgTput, nStar1, nStar2);
        writeGnuplot();

        LOGGER.info("E5 regime shift: N* {} -> {} at window {}", nStar1, nStar2, SHIFT_AT);
    }

    private void writeWide(String file, Iterable<String> policies, Map<String, double[]> data) {
        List<String> header = new ArrayList<>();
        header.add("window");
        policies.forEach(header::add);
        Path csv = CsvWriter.metricsPath("RegimeShift", file);
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

    private void writeSummary(Iterable<String> policies, Map<String, double[]> size,
                              Map<String, double[]> tput, int nStar1, int nStar2) {
        Path csv = CsvWriter.metricsPath("RegimeShift", "regime-summary.csv");
        try (CsvWriter out = new CsvWriter(csv,
                "policy", "phase1_size", "phase2_size", "phase1_tps", "phase2_tps",
                "nstar1", "nstar2")) {
            for (String policy : policies) {
                double[] s = size.get(policy);
                double[] t = tput.get(policy);
                out.writeRow(policy,
                        windowMean(s, SHIFT_AT - 15, SHIFT_AT),
                        windowMean(s, WINDOWS - 15, WINDOWS),
                        windowMean(t, SHIFT_AT - 15, SHIFT_AT),
                        windowMean(t, WINDOWS - 15, WINDOWS),
                        nStar1, nStar2);
            }
        }
    }

    private static double windowMean(double[] values, int from, int to) {
        double sum = 0;
        int n = 0;
        for (int i = Math.max(0, from); i < Math.min(values.length, to); i++) {
            sum += values[i];
            n++;
        }
        return n == 0 ? Double.NaN : sum / n;
    }

    private void writeGnuplot() {
        String sizeGp = ("""
                # Experiment E5: pool size vs. time across a regime shift at window %d.
                # Input:  regime-poolsize.csv (window, static-oracle-phase1, reactive, probe, usl)
                # Output: regime-poolsize.svg / .pdf
                set datafile separator ","
                set title "Pool size across a workload regime shift" font ",13"
                set xlabel "Observation window"
                set ylabel "Configured pool size"
                set grid xtics ytics lt 0 lw 0.5 lc rgb "#cccccc"
                set key outside right top vertical
                set arrow from %d, graph 0 to %d, graph 1 nohead dt 2 lc rgb "#888888"
                set style line 1 lw 2 lc rgb "#2E7D32"
                set style line 2 lw 2 lc rgb "#F9A825"
                set style line 3 lw 2 lc rgb "#6A1B9A"
                set style line 4 lw 2 lc rgb "#1565C0"
                set terminal svg enhanced font "arial,11" size 1000,560
                set output 'regime-poolsize.svg'
                plot for [i=2:5] 'regime-poolsize.csv' using 1:i with lines ls (i-1) title columnheader
                set terminal pdfcairo enhanced font "arial,10" size 8,4.5
                set output 'regime-poolsize.pdf'
                replot
                set output
                """).formatted(SHIFT_AT, SHIFT_AT, SHIFT_AT);
        writeFile(CsvWriter.metricsPath("RegimeShift", "regime-poolsize.gp"), sizeGp);

        String tpsGp = ("""
                # Experiment E5: throughput vs. time across a regime shift at window %d.
                # Input:  regime-tps.csv (window, static-oracle-phase1, reactive, probe, usl)
                # Output: regime-tps.svg / .pdf
                set datafile separator ","
                set title "Throughput across a workload regime shift" font ",13"
                set xlabel "Observation window"
                set ylabel "Throughput (tx/s)"
                set grid xtics ytics lt 0 lw 0.5 lc rgb "#cccccc"
                set key outside right bottom vertical
                set arrow from %d, graph 0 to %d, graph 1 nohead dt 2 lc rgb "#888888"
                set style line 1 lw 2 lc rgb "#2E7D32"
                set style line 2 lw 2 lc rgb "#F9A825"
                set style line 3 lw 2 lc rgb "#6A1B9A"
                set style line 4 lw 2 lc rgb "#1565C0"
                set terminal svg enhanced font "arial,11" size 1000,560
                set output 'regime-tps.svg'
                plot for [i=2:5] 'regime-tps.csv' using 1:i with lines ls (i-1) title columnheader
                set terminal pdfcairo enhanced font "arial,10" size 8,4.5
                set output 'regime-tps.pdf'
                replot
                set output
                """).formatted(SHIFT_AT, SHIFT_AT, SHIFT_AT);
        writeFile(CsvWriter.metricsPath("RegimeShift", "regime-tps.gp"), tpsGp);
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
