package com.vladmihalcea.phd.pool.report;

import com.vladmihalcea.phd.pool.usl.UslFit;
import com.vladmihalcea.phd.pool.usl.UslFitter;
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

/**
 * Experiment E2: fit the USL to every pool's throughput-vs-size curve and emit the paper's parameter
 * table (lambda, sigma, kappa with 95% CIs, R^2 and N*), a clustered-bar figure of N* per pool, and a
 * measured-vs-fitted overlay per pool. Consumes the measured E1 CSV when present, otherwise a seeded
 * synthetic dataset (see {@link ThroughputData}); the chosen source is logged.
 *
 * @author Vlad Mihalcea
 */
public class UslFitReportTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(UslFitReportTest.class);

    private static final List<String> POOLS = List.of("hikari", "dbcp2", "agroal");
    private static final List<String> WORKLOADS = List.of("readMostly", "writeHeavy", "longTx");

    @Test
    public void fitAndReport() {
        List<ThroughputData.Curve> curves = loadCurves();

        // 1) Parameter table (long form): one row per (pool, workload).
        Path coeffCsv = CsvWriter.metricsPath("UslFit", "usl-coefficients.csv");
        Map<String, UslFit> fits = new LinkedHashMap<>();
        try (CsvWriter out = new CsvWriter(coeffCsv,
                "pool", "workload", "lambda", "sigma", "sigma_ci95", "kappa", "kappa_ci95", "nstar", "r2")) {
            for (ThroughputData.Curve curve : curves) {
                UslFit fit = UslFitter.fit(curve.poolSizes(), curve.throughput());
                fits.put(curve.pool() + "|" + curve.workload(), fit);
                out.writeRow(curve.pool(), curve.workload(),
                        fit.model().lambda(), fit.model().sigma(), fit.sigmaCi95(),
                        fit.model().kappa(), fit.kappaCi95(),
                        nStarCell(fit), fit.rSquared());
            }
        }
        LOGGER.info("Wrote USL coefficient table to {}", coeffCsv);

        // 2) N* per pool, grouped by workload (clustered bars => claim C3).
        Path nstarCsv = CsvWriter.metricsPath("UslFit", "nstar-bars.csv");
        try (CsvWriter out = new CsvWriter(nstarCsv, headerWith("workload", POOLS))) {
            for (String workload : WORKLOADS) {
                Object[] row = new Object[POOLS.size() + 1];
                row[0] = workload;
                for (int p = 0; p < POOLS.size(); p++) {
                    UslFit fit = fits.get(POOLS.get(p) + "|" + workload);
                    row[p + 1] = fit == null ? 0 : cappedNStar(fit);
                }
                out.writeRow(row);
            }
        }
        writeNStarGnuplot();

        // 3) Measured-vs-fitted overlay for the readMostly workload, all pools on one figure.
        writeOverlay(curves, fits);

        LOGGER.info("USL fit report complete for {} curves", curves.size());
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
        LOGGER.warn("No measured E1 CSV found at {} - falling back to SYNTHETIC ground-truth data. "
                + "Run ThroughputVsPoolSizeBenchmark (needs Docker) to regenerate from real measurements.", e1Csv);
        return ThroughputData.synthesize(POOLS, WORKLOADS, 2026L);
    }

    private void writeOverlay(List<ThroughputData.Curve> curves, Map<String, UslFit> fits) {
        String workload = "readMostly";
        // Align every pool's curve on the canonical sweep grid.
        List<String> header = new ArrayList<>();
        header.add("poolSize");
        for (String pool : POOLS) {
            header.add(pool + "_measured");
            header.add(pool + "_fitted");
        }
        Path csv = CsvWriter.metricsPath("UslFit", "fit-readMostly.csv");
        try (CsvWriter out = new CsvWriter(csv, header.toArray(new String[0]))) {
            for (double size : ThroughputData.POOL_SIZES) {
                Object[] row = new Object[1 + POOLS.size() * 2];
                row[0] = size;
                for (int p = 0; p < POOLS.size(); p++) {
                    String pool = POOLS.get(p);
                    row[1 + p * 2] = measuredAt(curves, pool, workload, size);
                    UslFit fit = fits.get(pool + "|" + workload);
                    row[2 + p * 2] = fit == null ? Double.NaN : fit.model().throughputAt(size);
                }
                out.writeRow(row);
            }
        }
        writeOverlayGnuplot();
    }

    private static double measuredAt(List<ThroughputData.Curve> curves, String pool, String workload, double size) {
        for (ThroughputData.Curve curve : curves) {
            if (curve.pool().equals(pool) && curve.workload().equals(workload)) {
                for (int i = 0; i < curve.poolSizes().length; i++) {
                    if (curve.poolSizes()[i] == size) {
                        return curve.throughput()[i];
                    }
                }
            }
        }
        return Double.NaN;
    }

    private static Object nStarCell(UslFit fit) {
        int nStar = fit.optimalPoolSize();
        return nStar == Integer.MAX_VALUE ? "inf" : nStar;
    }

    private static int cappedNStar(UslFit fit) {
        int nStar = fit.optimalPoolSize();
        return nStar == Integer.MAX_VALUE ? 512 : nStar; // cap the no-coherency case for plotting
    }

    private static String[] headerWith(String first, List<String> rest) {
        String[] header = new String[rest.size() + 1];
        header[0] = first;
        for (int i = 0; i < rest.size(); i++) {
            header[i + 1] = rest.get(i);
        }
        return header;
    }

    private void writeNStarGnuplot() {
        String gp = """
                # Experiment E2: optimal pool size N* per pool implementation, grouped by workload.
                # Input:  nstar-bars.csv  (workload, hikari, dbcp2, agroal)
                # Output: nstar-bars.svg / .pdf
                set datafile separator ","
                set title "USL-optimal pool size N* per pool" font ",13"
                set ylabel "N* (connections)"
                set style data histograms
                set style histogram clustered gap 1
                set style fill solid 0.9 border -1
                set boxwidth 0.9
                set grid ytics lt 0 lw 0.5 lc rgb "#cccccc"
                set key outside right top vertical
                set xtics rotate by -20

                set style line 1 lc rgb "#1565C0"
                set style line 2 lc rgb "#2E7D32"
                set style line 3 lc rgb "#F9A825"

                set terminal svg enhanced font "arial,11" size 900,520
                set output 'nstar-bars.svg'
                plot for [i=2:4] 'nstar-bars.csv' using i:xtic(1) ls (i-1) title columnheader

                set terminal pdfcairo enhanced font "arial,10" size 8,4.5
                set output 'nstar-bars.pdf'
                replot
                set output
                """;
        writeFile(CsvWriter.metricsPath("UslFit", "nstar-bars.gp"), gp);
    }

    private void writeOverlayGnuplot() {
        String gp = """
                # Experiment E2: measured throughput points vs. fitted USL curves (readMostly workload).
                # Input:  fit-readMostly.csv (poolSize, <pool>_measured, <pool>_fitted per pool)
                # Output: fit-readMostly.svg / .pdf
                set datafile separator ","
                set title "Throughput vs. pool size: measured points and fitted USL" font ",13"
                set xlabel "Pool size (connections)"
                set ylabel "Throughput (tx/s)"
                set grid xtics ytics lt 0 lw 0.5 lc rgb "#cccccc"
                set key outside right top vertical

                # measured = points, fitted = smooth line, one colour per pool
                set style line 1 lc rgb "#1565C0" pt 7 ps 0.8
                set style line 2 lc rgb "#1565C0" lw 2
                set style line 3 lc rgb "#2E7D32" pt 5 ps 0.8
                set style line 4 lc rgb "#2E7D32" lw 2
                set style line 5 lc rgb "#F9A825" pt 9 ps 0.8
                set style line 6 lc rgb "#F9A825" lw 2

                set terminal svg enhanced font "arial,11" size 1000,600
                set output 'fit-readMostly.svg'
                plot 'fit-readMostly.csv' using 1:2 with points ls 1 title 'hikari (measured)', \\
                     '' using 1:3 with lines ls 2 title 'hikari (USL)', \\
                     '' using 1:4 with points ls 3 title 'dbcp2 (measured)', \\
                     '' using 1:5 with lines ls 4 title 'dbcp2 (USL)', \\
                     '' using 1:6 with points ls 5 title 'agroal (measured)', \\
                     '' using 1:7 with lines ls 6 title 'agroal (USL)'

                set terminal pdfcairo enhanced font "arial,10" size 8,4.8
                set output 'fit-readMostly.pdf'
                replot
                set output
                """;
        writeFile(CsvWriter.metricsPath("UslFit", "fit-readMostly.gp"), gp);
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
