package com.vladmihalcea.phd.pool.report;

import com.vladmihalcea.phd.pool.usl.QueueModels;
import com.vladmihalcea.phd.pool.usl.UslFit;
import com.vladmihalcea.phd.pool.usl.UslFitter;
import com.vladmihalcea.phd.pool.util.CsvWriter;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Experiment E3: compare the pool size predicted by four sizing methods — the USL optimum, Little's Law,
 * the Erlang-C (M/M/c) model and the HikariCP heuristic — against the pool size that actually maximised
 * measured throughput. The paper's point: the model-free heuristic and the coherency-blind queueing
 * models miss the observed optimum, and by a margin that grows with the fitted coherency coefficient.
 * <p>
 * Service time and offered load are derived from each curve: {@code S = 1 / X(1)} (single-connection
 * service rate) and offered load {@code a = X_peak / X(1)} Erlangs. Consumes measured E1 data when
 * present, otherwise the seeded synthetic dataset.
 *
 * @author Vlad Mihalcea
 */
public class ModelComparisonReportTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModelComparisonReportTest.class);

    private static final List<String> POOLS = List.of("hikari", "dbcp2", "agroal");
    private static final String WORKLOAD = "readMostly";
    private static final int DB_HOST_CORES = 8;      // documented host assumption for the heuristic
    private static final double WAIT_SLO = 0.05;     // Erlang-C target P(wait) < 5%

    @Test
    public void compareModels() {
        List<ThroughputData.Curve> curves = loadCurves();

        Path wideCsv = CsvWriter.metricsPath("ModelComparison", "model-comparison.csv");
        Path errCsv = CsvWriter.metricsPath("ModelComparison", "model-comparison-error.csv");
        try (CsvWriter wide = new CsvWriter(wideCsv,
                "pool", "measured", "USL", "Little", "ErlangC", "Heuristic");
             CsvWriter err = new CsvWriter(errCsv,
                     "pool", "model", "predicted", "measured", "abs_error")) {

            for (String pool : POOLS) {
                ThroughputData.Curve curve = find(curves, pool, WORKLOAD);
                if (curve == null) {
                    continue;
                }
                UslFit fit = UslFitter.fit(curve.poolSizes(), curve.throughput());

                int measuredArgmax = argmaxPoolSize(curve);
                double singleConnThroughput = throughputAtSizeOne(curve);
                double peak = maxThroughput(curve);
                double serviceTimeSeconds = 1.0 / singleConnThroughput;
                double offeredLoadErlangs = peak / singleConnThroughput;
                double arrivalRate = offeredLoadErlangs / serviceTimeSeconds; // = peak, by construction

                int uslNStar = capInfinite(fit.optimalPoolSize());
                int little = QueueModels.littlesLaw(arrivalRate, serviceTimeSeconds);
                int erlangC = QueueModels.erlangCServers(arrivalRate, serviceTimeSeconds, WAIT_SLO);
                int heuristic = QueueModels.hikariHeuristic(DB_HOST_CORES, 1);

                wide.writeRow(pool, measuredArgmax, uslNStar, little, erlangC, heuristic);
                err.writeRow(pool, "USL", uslNStar, measuredArgmax, Math.abs(uslNStar - measuredArgmax));
                err.writeRow(pool, "Little", little, measuredArgmax, Math.abs(little - measuredArgmax));
                err.writeRow(pool, "ErlangC", erlangC, measuredArgmax, Math.abs(erlangC - measuredArgmax));
                err.writeRow(pool, "Heuristic", heuristic, measuredArgmax, Math.abs(heuristic - measuredArgmax));

                LOGGER.info("{}: measured argmax={}, USL N*={}, Little={}, ErlangC={}, heuristic={}",
                        pool, measuredArgmax, uslNStar, little, erlangC, heuristic);
            }
        }
        writeGnuplot();
        LOGGER.info("Wrote model-comparison report to {}", wideCsv);
    }

    private List<ThroughputData.Curve> loadCurves() {
        Path e1Csv = CsvWriter.metricsPath("jmh-results", "ThroughputVsPoolSizeBenchmark.csv");
        if (Files.exists(e1Csv)) {
            List<ThroughputData.Curve> measured = ThroughputData.loadMeasured(e1Csv);
            if (!measured.isEmpty()) {
                LOGGER.info("Using MEASURED throughput data from {}", e1Csv);
                return measured;
            }
        }
        LOGGER.warn("No measured E1 CSV at {} - using SYNTHETIC ground-truth data.", e1Csv);
        return ThroughputData.synthesize(POOLS, List.of(WORKLOAD), 2026L);
    }

    private static ThroughputData.Curve find(List<ThroughputData.Curve> curves, String pool, String workload) {
        return curves.stream()
                .filter(c -> c.pool().equals(pool) && c.workload().equals(workload))
                .findFirst().orElse(null);
    }

    private static int argmaxPoolSize(ThroughputData.Curve curve) {
        int best = 0;
        for (int i = 1; i < curve.throughput().length; i++) {
            if (curve.throughput()[i] > curve.throughput()[best]) {
                best = i;
            }
        }
        return (int) curve.poolSizes()[best];
    }

    private static double maxThroughput(ThroughputData.Curve curve) {
        double max = 0;
        for (double t : curve.throughput()) {
            max = Math.max(max, t);
        }
        return max;
    }

    private static double throughputAtSizeOne(ThroughputData.Curve curve) {
        for (int i = 0; i < curve.poolSizes().length; i++) {
            if (curve.poolSizes()[i] == 1.0) {
                return curve.throughput()[i];
            }
        }
        // Fall back to the smallest sampled size scaled to one connection.
        return curve.throughput()[0] / curve.poolSizes()[0];
    }

    private static int capInfinite(int nStar) {
        return nStar == Integer.MAX_VALUE ? 512 : nStar;
    }

    private void writeGnuplot() {
        String gp = """
                # Experiment E3: predicted vs. measured optimal pool size, per sizing method.
                # Input:  model-comparison.csv (pool, measured, USL, Little, ErlangC, Heuristic)
                # Output: model-comparison.svg / .pdf
                set datafile separator ","
                set title "Predicted vs. measured optimal pool size" font ",13"
                set ylabel "Pool size (connections)"
                set style data histograms
                set style histogram clustered gap 1
                set style fill solid 0.9 border -1
                set boxwidth 0.9
                set grid ytics lt 0 lw 0.5 lc rgb "#cccccc"
                set key outside right top vertical
                set xtics rotate by -20

                set style line 1 lc rgb "#455A64"
                set style line 2 lc rgb "#1565C0"
                set style line 3 lc rgb "#2E7D32"
                set style line 4 lc rgb "#F9A825"
                set style line 5 lc rgb "#C62828"

                set terminal svg enhanced font "arial,11" size 1000,560
                set output 'model-comparison.svg'
                plot for [i=2:6] 'model-comparison.csv' using i:xtic(1) ls (i-1) title columnheader

                set terminal pdfcairo enhanced font "arial,10" size 8,4.5
                set output 'model-comparison.pdf'
                replot
                set output
                """;
        writeFile(CsvWriter.metricsPath("ModelComparison", "model-comparison.gp"), gp);
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
