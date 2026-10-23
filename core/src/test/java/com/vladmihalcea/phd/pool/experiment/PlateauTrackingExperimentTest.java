package com.vladmihalcea.phd.pool.experiment;

import com.vladmihalcea.phd.pool.controller.ScalingPolicy;
import com.vladmihalcea.phd.pool.controller.StaticPolicy;
import com.vladmihalcea.phd.pool.controller.ThroughputProbePolicy;
import com.vladmihalcea.phd.pool.controller.UslAutoScalingController;
import com.vladmihalcea.phd.pool.controller.UslPlateauController;
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
 * Experiments K3 and K5 of the "High Throughput with Few Connections" plan, on the simulator: the
 * plateau-targeting controller ({@code usl-plateau}, {@code epsilon = 5%}) against the {@code N*}-targeting USL
 * controller, the model-free probe and two static oracles, across the same regime shift as E5
 * ({@code N*} 81 to 37, {@code N_eps} 56 to 26). The summary reports each policy's pool size and its
 * throughput as a share of the phase's peak, which is the trade-off the study is about.
 * <p>
 * Simulator-based and fully reproducible without Docker.
 *
 * @author Vlad Mihalcea
 */
public class PlateauTrackingExperimentTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlateauTrackingExperimentTest.class);

    private static final int MIN = 2;
    private static final int MAX = 96;
    private static final int CLIENT_THREADS = 96;
    private static final int INITIAL = 4;
    private static final double TOLERANCE = Double.parseDouble(System.getProperty("usl.tolerance", "0.05"));
    private static final int WINDOWS = Integer.getInteger("usl.windows", 120);
    private static final int SHIFT_AT = WINDOWS / 2;
    private static final int REPS = Integer.getInteger("usl.reps", 5);
    private static final long BASE_SEED = 7_000L;

    private static final UslModel PHASE1 = new UslModel(1500, 0.01, 0.00015); // N* = 81
    private static final UslModel PHASE2 = new UslModel(1500, 0.02, 0.0007);  // N* = 37

    @Test
    public void measurePlateauTracking() {
        int nStar1 = PHASE1.optimalPoolSize();
        int nStar2 = PHASE2.optimalPoolSize();
        int plateau1 = PHASE1.poolSizeAtFraction(1 - TOLERANCE);
        int plateau2 = PHASE2.poolSizeAtFraction(1 - TOLERANCE);

        Map<String, Supplier<ScalingPolicy>> policies = new LinkedHashMap<>();
        policies.put("static-nstar-phase1", () -> new StaticPolicy("static-nstar-phase1", nStar1));
        policies.put("static-plateau-phase1", () -> new StaticPolicy("static-plateau-phase1", plateau1));
        policies.put("probe", () -> new ThroughputProbePolicy(MIN, MAX));
        policies.put("usl", () -> new UslAutoScalingController(MIN, MAX));
        policies.put("usl-plateau", () -> new UslPlateauController(UslPlateauController.Config.defaults(MIN, MAX, TOLERANCE)));

        Map<String, double[]> avgSize = new LinkedHashMap<>();
        Map<String, double[]> avgTput = new LinkedHashMap<>();

        for (Map.Entry<String, Supplier<ScalingPolicy>> entry : policies.entrySet()) {
            double[] size = new double[WINDOWS];
            double[] tput = new double[WINDOWS];
            for (int rep = 0; rep < REPS; rep++) {
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

        writeWide("plateau-poolsize.csv", policies.keySet(), avgSize);
        writeWide("plateau-tps.csv", policies.keySet(), avgTput);
        writeSummary(policies.keySet(), avgSize, avgTput, nStar1, nStar2, plateau1, plateau2);
        writeGnuplot(policies.size());

        LOGGER.info("K3/K5 plateau tracking (epsilon {}): N* {} -> {}, N_eps {} -> {} at window {}",
                TOLERANCE, nStar1, nStar2, plateau1, plateau2, SHIFT_AT);
    }

    private void writeWide(String file, Iterable<String> policies, Map<String, double[]> data) {
        List<String> header = new ArrayList<>();
        header.add("window");
        policies.forEach(header::add);
        Path csv = CsvWriter.metricsPath("PlateauTracking", file);
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

    private void writeSummary(Iterable<String> policies, Map<String, double[]> size, Map<String, double[]> tput,
                              int nStar1, int nStar2, int plateau1, int plateau2) {
        double peak1 = PHASE1.peakThroughput();
        double peak2 = PHASE2.peakThroughput();
        Path csv = CsvWriter.metricsPath("PlateauTracking", "plateau-summary.csv");
        try (CsvWriter out = new CsvWriter(csv,
                "policy", "phase1_size", "phase2_size", "phase1_tps", "phase2_tps",
                "phase1_peak_share", "phase2_peak_share", "nstar1", "nstar2", "plateau1", "plateau2", "tolerance")) {
            for (String policy : policies) {
                double[] s = size.get(policy);
                double[] t = tput.get(policy);
                double tps1 = windowMean(t, SHIFT_AT - 15, SHIFT_AT);
                double tps2 = windowMean(t, WINDOWS - 15, WINDOWS);
                out.writeRow(policy,
                        windowMean(s, SHIFT_AT - 15, SHIFT_AT),
                        windowMean(s, WINDOWS - 15, WINDOWS),
                        tps1, tps2, tps1 / peak1, tps2 / peak2,
                        nStar1, nStar2, plateau1, plateau2, TOLERANCE);
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

    private void writeGnuplot(int policyCount) {
        String sizeGp = ("""
                # Experiments K3/K5: pool size vs. time, plateau start (N_eps) vs. peak (N*) targeting, shift at window %d.
                # Input:  plateau-poolsize.csv (window, static-nstar-phase1, static-plateau-phase1, probe, usl, usl-plateau)
                # Output: plateau-poolsize.svg / .pdf
                set datafile separator ","
                set title "Pool size: plateau start vs. peak targeting" font ",13"
                set xlabel "Observation window"
                set ylabel "Configured pool size"
                set grid xtics ytics lt 0 lw 0.5 lc rgb "#cccccc"
                set key outside right top vertical
                set arrow from %d, graph 0 to %d, graph 1 nohead dt 2 lc rgb "#888888"
                set style line 1 lw 2 lc rgb "#2E7D32"
                set style line 2 lw 2 dt 2 lc rgb "#2E7D32"
                set style line 3 lw 2 lc rgb "#6A1B9A"
                set style line 4 lw 2 lc rgb "#1565C0"
                set style line 5 lw 2 lc rgb "#C62828"
                set terminal svg enhanced font "arial,11" size 1000,560
                set output 'plateau-poolsize.svg'
                plot for [i=2:%d] 'plateau-poolsize.csv' using 1:i with lines ls (i-1) title columnheader
                set terminal pdfcairo enhanced font "arial,10" size 8,4.5
                set output 'plateau-poolsize.pdf'
                replot
                set output
                """).formatted(SHIFT_AT, SHIFT_AT, SHIFT_AT, policyCount + 1);
        writeFile(CsvWriter.metricsPath("PlateauTracking", "plateau-poolsize.gp"), sizeGp);

        String tpsGp = ("""
                # Experiments K3/K5: throughput vs. time, plateau start (N_eps) vs. peak (N*) targeting, shift at window %d.
                # Input:  plateau-tps.csv (window, static-nstar-phase1, static-plateau-phase1, probe, usl, usl-plateau)
                # Output: plateau-tps.svg / .pdf
                set datafile separator ","
                set title "Throughput: plateau start vs. peak targeting" font ",13"
                set xlabel "Observation window"
                set ylabel "Throughput (tx/s)"
                set grid xtics ytics lt 0 lw 0.5 lc rgb "#cccccc"
                set key outside right bottom vertical
                set arrow from %d, graph 0 to %d, graph 1 nohead dt 2 lc rgb "#888888"
                set style line 1 lw 2 lc rgb "#2E7D32"
                set style line 2 lw 2 dt 2 lc rgb "#2E7D32"
                set style line 3 lw 2 lc rgb "#6A1B9A"
                set style line 4 lw 2 lc rgb "#1565C0"
                set style line 5 lw 2 lc rgb "#C62828"
                set terminal svg enhanced font "arial,11" size 1000,560
                set output 'plateau-tps.svg'
                plot for [i=2:%d] 'plateau-tps.csv' using 1:i with lines ls (i-1) title columnheader
                set terminal pdfcairo enhanced font "arial,10" size 8,4.5
                set output 'plateau-tps.pdf'
                replot
                set output
                """).formatted(SHIFT_AT, SHIFT_AT, SHIFT_AT, policyCount + 1);
        writeFile(CsvWriter.metricsPath("PlateauTracking", "plateau-tps.gp"), tpsGp);
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
