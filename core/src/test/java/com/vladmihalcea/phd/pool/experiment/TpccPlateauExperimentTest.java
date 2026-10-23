package com.vladmihalcea.phd.pool.experiment;

import com.vladmihalcea.phd.pool.adapter.JdbcConfig;
import com.vladmihalcea.phd.pool.common.util.Database;
import com.vladmihalcea.phd.pool.flexypool.LivePlateauRun;
import com.vladmihalcea.phd.pool.flexypool.UslPlateauConnectionAcquisitionStrategy;
import com.vladmihalcea.phd.pool.usl.UslFit;
import com.vladmihalcea.phd.pool.usl.UslFitter;
import com.vladmihalcea.phd.pool.util.CsvWriter;
import com.vladmihalcea.phd.pool.workload.tpcc.TpccSchema;
import com.vladmihalcea.phd.pool.workload.tpcc.TpccWorkload;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Does the plateau strategy find the right pool size under a standard load? Per database, on a freshly
 * loaded TPC-C-derived schema ({@link TpccSchema}, {@code tpcc.warehouses} warehouses, default 2):
 * <ol>
 *   <li><b>Sweep.</b> A closed loop of 64 terminals runs the standard TPC-C mix against a HikariCP pool
 *       held at each size of a fixed grid. Every size starts from a freshly loaded schema and a pool that
 *       has already opened all its connections, and is measured for {@code tpcc.measureMillis} after a
 *       warm-up: TPC-C slows down as its hot rows accumulate versions, so measuring the sizes one after
 *       another on the same data would mistake that drift for a retrograde curve. A USL fit of the measured curve gives {@code N*} and the
 *       fitted plateau start, and the grid gives the measured plateau start (the smallest size within
 *       {@code 1 - epsilon} of the best measured throughput).</li>
 *   <li><b>Live.</b> After reloading the schema, the {@link UslPlateauConnectionAcquisitionStrategy} runs the
 *       same load through FlexyPool for 90 s, starting from 2 connections, and the median pool size of its
 *       last 25 s is compared with the sweep.</li>
 * </ol>
 * Results go to {@code metrics/TpccPlateau/}: the sweep curve per database, a summary, and a gnuplot of
 * the measured curves with their USL fits.
 *
 * @author Vlad Mihalcea
 */
public class TpccPlateauExperimentTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(TpccPlateauExperimentTest.class);

    private static final int WAREHOUSES = Integer.getInteger("tpcc.warehouses", 2);
    private static final int TERMINALS = Integer.getInteger("tpcc.terminals", 64);
    private static final int[] SWEEP = {1, 2, 4, 6, 8, 12, 16, 20, 24, 32, 40, 48};
    private static final int CEILING = SWEEP[SWEEP.length - 1];
    private static final long WARMUP_MILLIS = Long.getLong("tpcc.warmupMillis", 3_000);
    private static final long MEASURE_MILLIS = Long.getLong("tpcc.measureMillis", 10_000);
    private static final long LIVE_RUN_MILLIS = Long.getLong("usl.live.runMillis", 90_000);
    private static final double TOLERANCE = 0.05;

    private record Summary(String database, UslFit fit, int fittedPlateauStart, int measuredPlateauStart,
                           double peakThroughput, LivePlateauRun.Result live) {
    }

    private static final List<Summary> SUMMARIES = new ArrayList<>();

    @ParameterizedTest(name = "{0}")
    @EnumSource(Database.class)
    public void plateauStrategyMatchesTheTpccSweep(Database database) throws Exception {
        database.assumeAvailable();

        // 1. Sweep the static pool sizes.
        double[] throughput = new double[SWEEP.length];
        double[] newOrdersPerMinute = new double[SWEEP.length];
        double[] failuresPerSecond = new double[SWEEP.length];
        sweep(database, throughput, newOrdersPerMinute, failuresPerSecond);
        writeSweep(database, throughput, newOrdersPerMinute, failuresPerSecond);

        double[] sizes = new double[SWEEP.length];
        double peak = 0;
        for (int i = 0; i < SWEEP.length; i++) {
            sizes[i] = SWEEP[i];
            peak = Math.max(peak, throughput[i]);
        }
        UslFit fit = UslFitter.fit(sizes, throughput);
        int fittedPlateauStart = fit.model().poolSizeAtFraction(1 - TOLERANCE, CEILING);
        int measuredPlateauStart = CEILING;
        for (int i = 0; i < SWEEP.length; i++) {
            if (throughput[i] >= (1 - TOLERANCE) * peak) {
                measuredPlateauStart = SWEEP[i];
                break;
            }
        }
        LOGGER.info("TPC-C sweep on {}: peak {} tx/s, USL lambda={} sigma={} kappa={} R2={}, N*={}, "
                        + "fitted N_eps={}, measured N_eps={}",
                database.id(), String.format("%.0f", peak), String.format("%.0f", fit.model().lambda()),
                String.format("%.4f", fit.model().sigma()), String.format("%.6f", fit.model().kappa()),
                String.format("%.3f", fit.rSquared()), fit.optimalPoolSize(), fittedPlateauStart,
                measuredPlateauStart);

        // 2. Run the plateau strategy live on a fresh copy of the same data.
        load(database);
        TpccWorkload workload = new TpccWorkload(WAREHOUSES);
        LivePlateauRun.Result live = new LivePlateauRun(database, "hikari", workload.name(),
                workload::runTransaction,
                UslPlateauConnectionAcquisitionStrategy.Config.defaults(2, CEILING, TOLERANCE),
                TERMINALS, LIVE_RUN_MILLIS, LIVE_RUN_MILLIS - 25_000).run();

        synchronized (SUMMARIES) {
            SUMMARIES.add(new Summary(database.id(), fit, fittedPlateauStart, measuredPlateauStart, peak, live));
        }
        LOGGER.info("TPC-C on {}: live settled at {} (its own N_eps {}), sweep plateau start {} measured / {} fitted",
                database.id(), live.settledSize(), live.plateauStart(), measuredPlateauStart, fittedPlateauStart);

        assertTrue(fit.rSquared() >= 0.9, "the USL should describe the TPC-C curve, R2=" + fit.rSquared());
        assertNotNull(live.fit(), "the live strategy should accept an online USL fit");
        // The live strategy should land at the plateau start the static sweep measured, within one control
        // step plus the dead band and the spacing of the sweep grid around it.
        assertTrue(Math.abs(live.settledSize() - measuredPlateauStart) <= 12,
                "live settled at " + live.settledSize() + ", sweep plateau start " + measuredPlateauStart);
    }

    private static void load(Database database) {
        DataSource admin = database.adminDataSource();
        try {
            new TpccSchema(WAREHOUSES).create(admin);
        } finally {
            if (admin instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception ignore) {
                    // best-effort close of the one-off admin pool
                }
            }
        }
    }

    /** Measures each grid size on a freshly loaded schema with a pre-filled HikariCP pool. */
    private static void sweep(Database database, double[] throughput, double[] newOrdersPerMinute,
                              double[] failuresPerSecond) throws Exception {
        JdbcConfig jdbc = database.jdbcConfig();
        TpccWorkload workload = new TpccWorkload(WAREHOUSES);
        for (int i = 0; i < SWEEP.length; i++) {
            int size = SWEEP[i];
            load(database);
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(jdbc.url());
            config.setUsername(jdbc.username());
            config.setPassword(jdbc.password());
            config.setMaximumPoolSize(size);
            config.setMinimumIdle(size);
            config.setConnectionTimeout(60_000);
            config.setPoolName("tpcc-sweep-" + size);
            AtomicLong committed = new AtomicLong();
            AtomicLong newOrders = new AtomicLong();
            AtomicLong failures = new AtomicLong();
            AtomicBoolean stop = new AtomicBoolean();
            ExecutorService terminals = Executors.newFixedThreadPool(TERMINALS);
            try (HikariDataSource pool = new HikariDataSource(config)) {
                long deadline = System.currentTimeMillis() + 180_000;
                while (pool.getHikariPoolMXBean().getTotalConnections() < size
                        && System.currentTimeMillis() < deadline) {
                    Thread.sleep(100);
                }
                for (int t = 0; t < TERMINALS; t++) {
                    final long seed = 1_000L * (i + 1) + t;
                    terminals.submit(() -> {
                        SplittableRandom random = new SplittableRandom(seed);
                        while (!stop.get()) {
                            try (Connection connection = pool.getConnection()) {
                                if (workload.runTransaction(connection, random) == TpccWorkload.Type.NEW_ORDER) {
                                    newOrders.incrementAndGet();
                                }
                                committed.incrementAndGet();
                            } catch (Exception e) {
                                failures.incrementAndGet();
                            }
                        }
                    });
                }
                Thread.sleep(WARMUP_MILLIS);
                long c0 = committed.get();
                long n0 = newOrders.get();
                long f0 = failures.get();
                long t0 = System.nanoTime();
                Thread.sleep(MEASURE_MILLIS);
                double seconds = (System.nanoTime() - t0) / 1e9;
                throughput[i] = (committed.get() - c0) / seconds;
                newOrdersPerMinute[i] = (newOrders.get() - n0) / seconds * 60;
                failuresPerSecond[i] = (failures.get() - f0) / seconds;
                LOGGER.info("TPC-C sweep {} pool {}: {} tx/s, {} New-Order/min, {} failures/s", database.id(),
                        size, String.format("%.0f", throughput[i]), String.format("%.0f", newOrdersPerMinute[i]),
                        String.format("%.1f", failuresPerSecond[i]));
            } finally {
                stop.set(true);
                terminals.shutdown();
                terminals.awaitTermination(60, TimeUnit.SECONDS);
            }
        }
    }

    private static void writeSweep(Database database, double[] throughput, double[] newOrdersPerMinute,
                                   double[] failuresPerSecond) {
        try (CsvWriter out = new CsvWriter(CsvWriter.metricsPath("TpccPlateau", "tpcc-sweep-" + database.id() + ".csv"),
                "pool_size", "tps", "new_order_per_min", "failures_per_s")) {
            for (int i = 0; i < SWEEP.length; i++) {
                out.writeRow(SWEEP[i], throughput[i], newOrdersPerMinute[i], failuresPerSecond[i]);
            }
        }
    }

    @AfterAll
    public static void writeSummary() {
        if (SUMMARIES.isEmpty()) {
            return;
        }
        try (CsvWriter out = new CsvWriter(CsvWriter.metricsPath("TpccPlateau", "tpcc-summary.csv"),
                "database", "warehouses", "terminals", "lambda", "sigma", "kappa", "r2", "nstar",
                "plateau_fitted", "plateau_measured", "peak_tps", "live_settled", "live_plateau_estimate",
                "live_nstar", "live_tps", "live_failures")) {
            for (Summary s : SUMMARIES) {
                out.writeRow(s.database(), WAREHOUSES, TERMINALS, s.fit().model().lambda(),
                        s.fit().model().sigma(), s.fit().model().kappa(), s.fit().rSquared(),
                        s.fit().optimalPoolSize(), s.fittedPlateauStart(), s.measuredPlateauStart(),
                        s.peakThroughput(), s.live().settledSize(), s.live().plateauStart(),
                        s.live().optimum(), s.live().observedThroughput(), s.live().failures());
            }
        }
        writeGnuplot();
    }

    /** Measured TPC-C throughput vs. pool size per database, with each database's USL fit overlaid. */
    private static void writeGnuplot() {
        StringBuilder functions = new StringBuilder();
        StringBuilder plots = new StringBuilder();
        String[] colors = {"#1565C0", "#C62828"};
        for (int i = 0; i < SUMMARIES.size(); i++) {
            Summary s = SUMMARIES.get(i);
            String f = "X_" + s.database();
            functions.append(String.format("%s(n) = %.6f * n / (1 + %.8f * (n - 1) + %.10f * n * (n - 1))%n",
                    f, s.fit().model().lambda(), s.fit().model().sigma(), s.fit().model().kappa()));
            if (!plots.isEmpty()) {
                plots.append(", \\\n     ");
            }
            String color = colors[i % colors.length];
            plots.append(String.format("'tpcc-sweep-%s.csv' using 1:2 with points pt 7 ps 0.8 lc rgb \"%s\" "
                            + "title '%s measured', %s(x) with lines lw 2 lc rgb \"%s\" title '%s USL fit'",
                    s.database(), color, s.database(), f, color, s.database()));
        }
        String gp = """
                # TPC-C-derived load: throughput vs. pool size per database, measured (points) and USL fit (lines).
                # Input:  tpcc-sweep-<database>.csv (pool_size, tps, new_order_per_min, failures_per_s)
                # Output: tpcc-sweep.svg / .pdf
                set datafile separator ","
                set title "TPC-C throughput vs. pool size (%d warehouses, %d terminals)" font ",13"
                set xlabel "Pool size (connections)"
                set ylabel "Throughput (tx/s)"
                set grid xtics ytics lt 0 lw 0.5 lc rgb "#cccccc"
                set key outside right top vertical
                set xrange [0:%d]
                set yrange [0:*]
                set samples 400
                %s
                set terminal svg enhanced font "arial,11" size 1000,560
                set output 'tpcc-sweep.svg'
                plot %s
                set terminal pdfcairo enhanced font "arial,10" size 8,4.5
                set output 'tpcc-sweep.pdf'
                replot
                set output
                """.formatted(WAREHOUSES, TERMINALS, CEILING, functions.toString().strip(), plots);
        Path path = CsvWriter.metricsPath("TpccPlateau", "tpcc-sweep.gp");
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, gp);
        } catch (Exception e) {
            throw new IllegalStateException("Could not write " + path, e);
        }
    }
}
