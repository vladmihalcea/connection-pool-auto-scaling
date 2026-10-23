package com.vladmihalcea.phd.pool.jmh;

import com.vladmihalcea.phd.pool.adapter.JdbcConfig;
import com.vladmihalcea.phd.pool.adapter.PoolFactory;
import com.vladmihalcea.phd.pool.api.PoolUnderTest;
import com.vladmihalcea.phd.pool.common.util.AbstractBenchmarkTest;
import com.vladmihalcea.phd.pool.common.util.Database;
import com.vladmihalcea.phd.pool.common.util.Workloads;
import com.vladmihalcea.phd.pool.util.CsvWriter;
import org.HdrHistogram.ConcurrentHistogram;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.infra.IterationParams;
import org.openjdk.jmh.runner.IterationType;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.concurrent.Semaphore;
import java.util.function.LongSupplier;
import java.util.concurrent.TimeUnit;

/**
 * Experiment E1: transactions/second as a function of connection pool size, for each pool implementation
 * and OLTP profile. This is the raw material the USL fit (E2) and the model comparison (E3) consume — the
 * concave throughput curve whose peak reveals the retrograde region (claim C1) and whose fitted
 * coefficients differ per pool (claim C3).
 * <p>
 * Closed loop: JMH runs {@code AbstractBenchmarkTest.threads()} client threads, but a per-trial
 * {@link Semaphore} caps the number that can compete for the pool at any instant to
 * {@code min(128, poolSize + 16)}. Those extra 16 permits keep the pool fully saturated (always a
 * queue) while holding the pool-queue depth at a constant 16 waiters — crucially <em>independent</em> of
 * pool size. The starvation risk under the pools' unfair connection handoff grows with the number of
 * waiters, so a queue that scaled with {@code poolSize} would starve a waiter past the timeout at the
 * larger sizes; a fixed depth of 16 does not. Even the worst-case {@code longTx} workload
 * (≈15 ms/tx on Windows) clears 16 waiters in ≈250 ms — well inside the pool adapters' 10-second acquire
 * timeout — so no thread is ever starved past the timeout at any pool size, and
 * {@code shouldFailOnError(true)} never fires.
 * <p>
 * Besides the three ICASC workloads, {@code workload} accepts the sysbench-derived {@code oltpReadWrite} and
 * {@code oltpReadOnly}, which run on their own {@code sbtest} tables, and the TPC-C-derived {@code tpcc}, which
 * every fork loads afresh ({@link Workloads}).
 * The {@code database} parameter selects PostgreSQL (the default, as in the ICASC sweep) or MySQL. Besides
 * the throughput that JMH reports, each fork records the response time of every transaction of its
 * measurement iterations, from the request for a connection to its return, and appends one row of
 * percentiles to {@link #latencyCsv()}: in a closed loop Little's Law ties the mean to the throughput, but
 * not the tail.
 *
 * @author Vlad Mihalcea
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
public class ThroughputVsPoolSizeBenchmark {

    @FunctionalInterface
    interface Transaction {
        void run(Connection connection, SplittableRandom random) throws SQLException;
    }

    /** Response-time percentiles, one row per fork, next to the JMH results of the same run. */
    public static Path latencyCsv() {
        return CsvWriter.metricsPath("jmh-results",
                AbstractBenchmarkTest.resultsName(ThroughputVsPoolSizeBenchmark.class) + "-latency.csv");
    }

    @State(Scope.Benchmark)
    public static class PoolState {

        @Param({"postgresql"})
        public String database;

        @Param({"hikari", "dbcp2", "agroal"})
        public String pool;

        @Param({"1", "2", "4", "8", "16", "24", "32", "48", "64", "96"})
        public int poolSize;

        @Param({"readMostly", "writeHeavy", "longTx"})
        public String workload;

        private PoolUnderTest poolUnderTest;

        /** One transaction of the workload: an ICASC micro-workload or a sysbench-derived one. */
        private Transaction transaction;

        /** Transactions restarted after a deadlock or a duplicate key (sysbench-derived workloads only). */
        private LongSupplier retries = () -> 0;

        /**
         * Limits concurrent pool contenders to {@code poolSize + 16} (capped at the 128 JMH threads), so
         * the pool wait-queue never exceeds 16 waiters. This keeps the pool fully saturated while
         * preventing the HikariCP / DBCP2 / Agroal unfair handoff from starving a waiter past the acquire
         * timeout — which the raw JMH thread count would otherwise cause at small pools (a 100+:1 ratio)
         * and a size-proportional queue would cause at the larger pools.
         */
        private Semaphore clientThrottle;

        /** Response times of the transactions of the measurement iterations, in nanoseconds. */
        private final ConcurrentHistogram latency = new ConcurrentHistogram(3);

        private volatile boolean measuring;

        @Setup(Level.Trial)
        public void setUp() throws Exception {
            Database db = Database.valueOf(database.toUpperCase(Locale.ROOT));
            // A fresh 10k-account table (ICASC micro-workloads), the reused sbtest tables (sysbench-derived), or a
            // freshly loaded TPC-C schema per fork (TPC-C drifts as its hot rows accumulate versions).
            Workloads.Prepared prepared = Workloads.prepare(db, workload);
            transaction = prepared.transaction()::run;
            retries = prepared.retries();

            JdbcConfig jdbc = db.jdbcConfig();
            poolUnderTest = PoolFactory.create(pool, jdbc, poolSize);
            prefill(poolUnderTest, poolSize);

            int permits = Math.min(poolSize + 16, 128);
            clientThrottle = new Semaphore(permits, /*fair=*/true);
        }

        /**
         * Holds all {@code poolSize} connections at once before the first iteration, so every pool has opened
         * its full set of physical connections when JMH starts measuring. HikariCP fills its pool in the
         * background (about one connection per second against PostgreSQL on Windows in our live runs), and a
         * pool still filling during the measurement would report the throughput of a smaller pool.
         */
        private static void prefill(PoolUnderTest poolUnderTest, int poolSize) {
            List<Connection> held = new ArrayList<>(poolSize);
            try {
                for (int i = 0; i < poolSize; i++) {
                    held.add(poolUnderTest.dataSource().getConnection());
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Could not open " + poolSize + " connections", e);
            } finally {
                for (Connection connection : held) {
                    try {
                        connection.close();
                    } catch (SQLException ignore) {
                        // returning a connection to the pool; nothing to recover
                    }
                }
            }
        }

        /**
         * Records response times only during measurement iterations, never during warm-up. The histogram also
         * takes the transactions of JMH's short warm-down at the end of each measurement iteration, which JMH
         * does not count in the throughput; they run under the same load.
         */
        @Setup(Level.Iteration)
        public void startIteration(IterationParams iteration) {
            measuring = iteration.getType() == IterationType.MEASUREMENT;
        }

        void recordLatency(long nanos) {
            if (measuring) {
                latency.recordValue(nanos);
            }
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            writeLatency();
            if (poolUnderTest != null) {
                poolUnderTest.close();
            }
        }

        /** Appends this fork's percentiles; JMH runs the forks one after another, so appends never interleave. */
        private void writeLatency() {
            if (latency.getTotalCount() == 0) {
                return;
            }
            Path csv = latencyCsv();
            try {
                Files.createDirectories(csv.getParent());
                StringBuilder rows = new StringBuilder();
                if (!Files.exists(csv)) {
                    rows.append("database,pool,workload,poolSize,count,mean_us,p50_us,p90_us,p99_us,p999_us,max_us,"
                            + "retries\n");
                }
                rows.append(String.join(",", database, pool, workload, String.valueOf(poolSize),
                        String.valueOf(latency.getTotalCount()), micros(latency.getMean()),
                        micros(latency.getValueAtPercentile(50)), micros(latency.getValueAtPercentile(90)),
                        micros(latency.getValueAtPercentile(99)), micros(latency.getValueAtPercentile(99.9)),
                        micros(latency.getMaxValue()), String.valueOf(retries.getAsLong()))).append('\n');
                Files.writeString(csv, rows, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private static String micros(double nanos) {
            return String.format(Locale.ROOT, "%.1f", nanos / 1_000.0);
        }
    }

    @State(Scope.Thread)
    public static class Rng {
        final SplittableRandom random = new SplittableRandom(System.nanoTime());
    }

    @Benchmark
    public void transaction(PoolState state, Rng rng, Blackhole blackhole) throws SQLException {
        // Throttle to poolSize + 16 contenders before touching the pool so the queue depth stays bounded.
        state.clientThrottle.acquireUninterruptibly();
        try {
            long start = System.nanoTime();
            try (Connection connection = state.poolUnderTest.dataSource().getConnection()) {
                state.transaction.run(connection, rng.random);
                blackhole.consume(connection);
            }
            state.recordLatency(System.nanoTime() - start);
        } finally {
            state.clientThrottle.release();
        }
    }
}
