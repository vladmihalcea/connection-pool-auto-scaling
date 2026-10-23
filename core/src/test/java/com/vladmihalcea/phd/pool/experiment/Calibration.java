package com.vladmihalcea.phd.pool.experiment;

import com.vladmihalcea.phd.pool.adapter.PoolFactory;
import com.vladmihalcea.phd.pool.api.PoolUnderTest;
import com.vladmihalcea.phd.pool.common.util.Database;
import com.vladmihalcea.phd.pool.common.util.Workloads;
import com.vladmihalcea.phd.pool.usl.SizingTarget;
import com.vladmihalcea.phd.pool.usl.UslFit;
import com.vladmihalcea.phd.pool.usl.UslFitter;
import com.vladmihalcea.phd.pool.usl.UslModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * A short closed-loop sweep and USL fit at the start of an experiment that needs the curve of its workload, the
 * multi-instance (K4) and open-arrival (K7) experiments: the throughput of a static pool at each of a few sizes, with
 * {@code N + 16} clients as in the K1 sweep, and the fitted optimum, plateau start, and point of diminishing
 * returns. Measuring the curve in the same run, on the same database state, avoids depending on a K1 sweep of
 * another day; the K1 analysis ({@code PlateauStartReportTest}) remains the reference for the curve itself.
 *
 * @author Vlad Mihalcea
 */
final class Calibration {

    private static final Logger LOGGER = LoggerFactory.getLogger(Calibration.class);

    /** The default sizes: the ICASC grid, denser around the plateau starts of the ICASC curves. */
    static final int[] SIZES = {1, 2, 4, 8, 12, 16, 20, 24, 32, 40, 48, 64, 80, 96};

    /** The measured curve and what the fit gives. */
    record Result(String workload, double[] sizes, double[] throughput, UslFit fit, int nStar, int nEta,
                  int nEps05, double peakThroughput) {

        UslModel model() {
            return fit.model();
        }
    }

    private Calibration() {
    }

    /**
     * Sweeps the sizes (those above {@code maxSize} are skipped) and fits the USL.
     *
     * @param warmupMillis  the warm-up at every size
     * @param measureMillis the measured time at every size
     */
    static Result run(Database database, String poolId, Workloads.Prepared workload, int[] sizes, int maxSize,
                      long warmupMillis, long measureMillis, long seed) throws InterruptedException {
        int[] used = Arrays.stream(sizes).filter(s -> s <= maxSize).toArray();
        double[] n = new double[used.length];
        double[] x = new double[used.length];
        for (int i = 0; i < used.length; i++) {
            n[i] = used[i];
            x[i] = measure(database, poolId, workload, used[i], used[i] + 16, warmupMillis, measureMillis,
                    seed + i);
        }
        UslFit fit = UslFitter.fit(n, x);
        UslModel model = fit.model();
        int nStar = Math.min(maxSize, model.optimalPoolSize() == Integer.MAX_VALUE ? maxSize : model.optimalPoolSize());
        int nEta = SizingTarget.DEFAULT.poolSize(model, maxSize);
        int nEps05 = model.poolSizeAtFraction(0.95, maxSize);
        Result result = new Result(workload.name(), n, x, fit, nStar, nEta, nEps05, model.throughputAt(nStar));
        LOGGER.info("Calibration of {} on {} {}: sigma={} kappa={} R2={}, N*={}, N_eta={}, N_0.05={}, X(N*)={} tx/s",
                workload.name(), database.id(), poolId, String.format("%.4f", model.sigma()),
                String.format("%.2e", model.kappa()), String.format("%.3f", fit.rSquared()), nStar, nEta, nEps05,
                String.format("%.0f", result.peakThroughput()));
        return result;
    }

    /** The throughput of a static pool of {@code size} under a closed loop of {@code clients} without think time. */
    static double measure(Database database, String poolId, Workloads.Prepared workload, int size, int clients,
                          long warmupMillis, long measureMillis, long seed) throws InterruptedException {
        try (PoolUnderTest pool = PoolFactory.create(poolId, database.jdbcConfig(), size)) {
            prefill(pool, size);
            AtomicBoolean stop = new AtomicBoolean();
            AtomicBoolean measuring = new AtomicBoolean();
            LongAdder committed = new LongAdder();
            List<Thread> threads = new ArrayList<>(clients);
            for (int c = 0; c < clients; c++) {
                SplittableRandom random = new SplittableRandom(seed * 7_919L + c);
                threads.add(Thread.ofPlatform().daemon().name("calibration-" + c).start(() -> {
                    while (!stop.get()) {
                        try (Connection connection = pool.dataSource().getConnection()) {
                            workload.transaction().run(connection, random);
                        } catch (SQLException e) {
                            continue;
                        }
                        if (measuring.get()) {
                            committed.increment();
                        }
                    }
                }));
            }
            Thread.sleep(warmupMillis);
            measuring.set(true);
            long start = System.nanoTime();
            Thread.sleep(measureMillis);
            measuring.set(false);
            long elapsed = System.nanoTime() - start;
            stop.set(true);
            for (Thread thread : threads) {
                thread.join(30_000);
            }
            return committed.sum() / (elapsed / 1e9);
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Calibration at " + size + " connections failed", e);
        }
    }

    /** Holds all connections once, so the pool has opened them before it is measured (as the K1 sweep does). */
    static void prefill(PoolUnderTest pool, int size) throws SQLException {
        List<Connection> held = new ArrayList<>(size);
        try {
            for (int i = 0; i < size; i++) {
                held.add(pool.dataSource().getConnection());
            }
        } finally {
            for (Connection connection : held) {
                connection.close();
            }
        }
    }
}
