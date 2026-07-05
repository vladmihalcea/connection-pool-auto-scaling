package com.vladmihalcea.phd.pool.report;

import com.vladmihalcea.phd.pool.usl.UslModel;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Throughput-vs-pool-size curves consumed by the USL-fit (E2) and model-comparison (E3) reports.
 * <p>
 * The reports prefer the <em>measured</em> data written by {@code ThroughputVsPoolSizeBenchmark} (E1);
 * when that CSV is absent — e.g. on a host without Docker, or before the sweep has been run — they fall
 * back to a seeded <em>synthetic</em> dataset generated from plausible per-pool ground-truth USL models,
 * so the whole fit → figure pipeline stays reproducible. Which source was used is logged by the report.
 * The synthetic ground truth deliberately gives the three pools different contention/coherency, matching
 * the paper's claim C3 that pool implementation overhead is measurable in USL terms.
 *
 * @author Vlad Mihalcea
 */
public final class ThroughputData {

    /** The pool sizes E1 sweeps; also the synthetic sampling grid. */
    public static final double[] POOL_SIZES = {1, 2, 4, 8, 12, 16, 24, 32, 48, 64, 96};

    public record Curve(String pool, String workload, double[] poolSizes, double[] throughput) {
    }

    private ThroughputData() {
    }

    /**
     * Loads the E1 long-format CSV ({@code benchmark,mode,pool,poolSize,workload,threads,score,...}) into
     * per-(pool, workload) curves. Only Throughput-mode rows are used.
     */
    public static List<Curve> loadMeasured(Path e1Csv) {
        Map<String, List<double[]>> grouped = new LinkedHashMap<>();
        try {
            List<String> lines = Files.readAllLines(e1Csv);
            if (lines.isEmpty()) {
                return List.of();
            }
            String[] header = lines.get(0).split(",");
            int poolCol = indexOf(header, "pool");
            int sizeCol = indexOf(header, "poolSize");
            int workloadCol = indexOf(header, "workload");
            int modeCol = indexOf(header, "mode");
            int scoreCol = indexOf(header, "score");
            if (poolCol < 0 || sizeCol < 0 || workloadCol < 0 || scoreCol < 0) {
                return List.of();
            }
            for (int i = 1; i < lines.size(); i++) {
                String[] cells = lines.get(i).split(",");
                if (modeCol >= 0 && !cells[modeCol].contains("thrpt")) {
                    continue;
                }
                String key = cells[poolCol] + "|" + cells[workloadCol];
                double size = Double.parseDouble(cells[sizeCol]);
                double score = Double.parseDouble(cells[scoreCol]);
                grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(new double[]{size, score});
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        List<Curve> curves = new ArrayList<>();
        for (Map.Entry<String, List<double[]>> e : grouped.entrySet()) {
            String[] parts = e.getKey().split("\\|");
            List<double[]> points = e.getValue();
            points.sort((a, b) -> Double.compare(a[0], b[0]));
            double[] sizes = new double[points.size()];
            double[] tput = new double[points.size()];
            for (int i = 0; i < points.size(); i++) {
                sizes[i] = points.get(i)[0];
                tput[i] = points.get(i)[1];
            }
            curves.add(new Curve(parts[0], parts[1], sizes, tput));
        }
        return curves;
    }

    /**
     * Ground-truth USL models used to synthesise data when no measured E1 CSV is available. Distinct
     * contention/coherency per pool; workloads shift the optimum (writeHeavy raises contention, longTx
     * pushes the optimum right by lowering the coherency penalty per connection).
     */
    public static UslModel groundTruth(String pool, String workload) {
        double lambda;
        double sigma;
        double kappa;
        switch (pool) {
            case "hikari" -> { lambda = 1800; sigma = 0.015; kappa = 0.00006; }
            case "agroal" -> { lambda = 1750; sigma = 0.020; kappa = 0.00010; }
            case "dbcp2"  -> { lambda = 1600; sigma = 0.035; kappa = 0.00018; }
            default -> throw new IllegalArgumentException("Unknown pool: " + pool);
        }
        switch (workload) {
            case "readMostly" -> { /* baseline */ }
            case "writeHeavy" -> { sigma *= 1.8; lambda *= 0.75; }
            case "longTx"     -> { lambda *= 0.35; kappa *= 0.35; } // longer service time -> optimum shifts right
            default -> throw new IllegalArgumentException("Unknown workload: " + workload);
        }
        return new UslModel(lambda, sigma, kappa);
    }

    public static List<Curve> synthesize(List<String> pools, List<String> workloads, long seed) {
        Random random = new Random(seed);
        List<Curve> curves = new ArrayList<>();
        for (String pool : pools) {
            for (String workload : workloads) {
                UslModel truth = groundTruth(pool, workload);
                double[] tput = new double[POOL_SIZES.length];
                for (int i = 0; i < POOL_SIZES.length; i++) {
                    double x = truth.throughputAt(POOL_SIZES[i]);
                    tput[i] = x * (1.0 + 0.03 * random.nextGaussian()); // 3% measurement noise
                }
                curves.add(new Curve(pool, workload, POOL_SIZES.clone(), tput));
            }
        }
        return curves;
    }

    private static int indexOf(String[] header, String name) {
        for (int i = 0; i < header.length; i++) {
            if (header[i].trim().equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }
}
