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
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.infra.Control;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;
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

    /**
     * The clients that compete for a pool of {@code poolSize} connections, at most the fork's JMH {@code threads}
     * (128). By default {@code poolSize + 16}, a queue of 16 at every size, which keeps the pool saturated without
     * starving a waiter. With {@code -Dusl.sweep.clients=M}, a fixed {@code M} at every size instead: the closed loop
     * of the article's response-time claim, in which {@code M >= N*} clients share the pool and the mean response time
     * is {@code M / X(N)}, so {@code R(N_eps) / R(N*) = X(N*) / X(N_eps) <= 1 / (1 - eps)}. The fixed loop also shows
     * whether the throughput at a pool size depends on the length of the queue.
     */
    static int clients(int poolSize, int threads) {
        int fixed = Integer.getInteger("usl.sweep.clients", 0);
        return Math.min(fixed > 0 ? fixed : poolSize + 16, threads);
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
         * and a size-proportional queue would cause at the larger pools. With {@code -Dusl.sweep.clients} the
         * permits are that fixed number instead ({@link ThroughputVsPoolSizeBenchmark#clients(int, int)}).
         * <p>
         * The semaphore is unfair by default ({@code -Dusl.sweep.fairThrottle=true} restores the fair one of the
         * ICASC sweep). A fair semaphore hands every released permit to the longest waiter, so each transaction
         * parks the releasing thread and wakes another; on the co-located laptop (128 client threads and up to 96
         * backends on 12 cores) that wake-up took about 2 ms, longer than the transaction, and the permits sat idle:
         * at 96 connections, Little's Law put only 18 transactions inside the pool on average. An unfair semaphore lets
         * the releasing thread, which is running, take the permit again. Its starvation cannot reach the pool's
         * acquisition timeout, which starts only at {@code getConnection}. The wait and lease times recorded per fork
         * (see {@link #writeLatency()}) show whether the pool was saturated at every size.
         */
        private Semaphore clientThrottle;

        /** Response times of the transactions of the measurement iterations, in nanoseconds. */
        private final ConcurrentHistogram latency = new ConcurrentHistogram(3);

        /** The acquisition waits and the leases of the transactions of the measurement iterations, in nanoseconds. */
        private final LongAdder waitNanos = new LongAdder();
        private final LongAdder leaseNanos = new LongAdder();
        private final LongAdder measured = new LongAdder();

        private volatile boolean measuring;

        @Setup(Level.Trial)
        public void setUp(BenchmarkParams params) throws Exception {
            Database db = Database.valueOf(database.toUpperCase(Locale.ROOT));
            // A fresh 10k-account table (ICASC micro-workloads), the reused sbtest tables (sysbench-derived), or a
            // freshly loaded TPC-C schema per fork (TPC-C drifts as its hot rows accumulate versions).
            Workloads.Prepared prepared = Workloads.prepare(db, workload);
            transaction = prepared.transaction()::run;
            retries = prepared.retries();

            JdbcConfig jdbc = db.jdbcConfig();
            poolUnderTest = PoolFactory.create(pool, jdbc, poolSize);
            prefill(poolUnderTest, poolSize);

            clients = clients(poolSize, params.getThreads());
            clientThrottle = new Semaphore(clients, Boolean.getBoolean("usl.sweep.fairThrottle"));
        }

        /** The clients that compete for the pool: see {@link ThroughputVsPoolSizeBenchmark#clients(int, int)}. */
        private int clients;

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

        private static final long UNSET = Long.MIN_VALUE;

        /** When a thread first saw JMH's measured window open and close in the current iteration. */
        private final AtomicLong windowStart = new AtomicLong(UNSET);
        private final AtomicLong windowEnd = new AtomicLong(UNSET);

        /** The total length of the measured windows of the measurement iterations, in nanoseconds. */
        private long windowNanos;

        /**
         * Records response times only during measurement iterations, never during warm-up, and only for the
         * transactions that complete inside JMH's measured window ({@link Control}), which opens when the first
         * thread starts its measured loop and closes when the first one leaves it: not those of the iteration's
         * synchronization phases, which JMH does not count either.
         */
        @Setup(Level.Iteration)
        public void startIteration(IterationParams iteration) {
            measuring = iteration.getType() == IterationType.MEASUREMENT;
            windowStart.set(UNSET);
            windowEnd.set(UNSET);
        }

        @TearDown(Level.Iteration)
        public void endIteration() {
            long opened = windowStart.get();
            long closed = windowEnd.get();
            if (measuring && opened != UNSET && closed != UNSET && closed > opened) {
                windowNanos += closed - opened;
            }
        }

        /** Marks the opening and the closing of the measured window the first time a thread observes each. */
        void observeWindow(Control control) {
            if (control.startMeasurement && windowStart.get() == UNSET) {
                windowStart.compareAndSet(UNSET, System.nanoTime());
            }
            if (control.stopMeasurement && windowEnd.get() == UNSET) {
                windowEnd.compareAndSet(UNSET, System.nanoTime());
            }
        }

        /**
         * Takes a permit of the throttle. Before the measured window opens, a waiting thread gives up as soon as it
         * opens. JMH sums the threads' own rates, each thread's operations over its own measured time. Without this,
         * a thread that was still waiting for a permit when the window opened would start its measured loop late,
         * possibly after the window had closed. It would then count one operation over a few milliseconds: in the
         * smoke run, 183k ops/s for oltpReadWrite at 4 connections, whose transactions completed at 640 per second.
         */
        boolean acquirePermit(Control control) {
            if (control.startMeasurement) {
                clientThrottle.acquireUninterruptibly();
                return true;
            }
            try {
                while (!control.startMeasurement) {
                    if (clientThrottle.tryAcquire(PERMIT_POLL_NANOS, TimeUnit.NANOSECONDS)) {
                        return true;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return false;
        }

        boolean inMeasuredWindow(Control control) {
            return measuring && control.startMeasurement && !control.stopMeasurement;
        }

        void recordLatency(boolean inWindow, long start, long acquired, long end) {
            if (inWindow) {
                latency.recordValue(end - start);
                waitNanos.add(acquired - start);
                leaseNanos.add(end - acquired);
                measured.increment();
            }
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            writeLatency();
            if (poolUnderTest != null) {
                poolUnderTest.close();
            }
        }

        /**
         * Appends this fork's percentiles, the clients of the closed loop, and the mean acquisition wait and lease
         * (from the request for a connection to its acquisition, and from there to its return). By Little's Law the
         * mean busy connections are the throughput times the mean lease, so the lease tells whether the pool was
         * saturated: a pool-limited point has about {@code poolSize} busy connections. The last two columns are the
         * length of the measured windows and the transactions completed inside them per second: the throughput that
         * the analysis uses, since JMH's score also counts each thread's last transaction, which completes after the
         * window has closed (128 threads over 2 s iterations add 64 ops/s, a tenth of a slow workload's throughput at
         * a small pool). JMH runs the forks one after another, so appends never interleave.
         */
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
                            + "retries,clients,mean_wait_us,mean_lease_us,window_s,window_tps\n");
                }
                long count = Math.max(1, measured.sum());
                double windowSeconds = windowNanos / 1e9;
                rows.append(String.join(",", database, pool, workload, String.valueOf(poolSize),
                        String.valueOf(latency.getTotalCount()), micros(latency.getMean()),
                        micros(latency.getValueAtPercentile(50)), micros(latency.getValueAtPercentile(90)),
                        micros(latency.getValueAtPercentile(99)), micros(latency.getValueAtPercentile(99.9)),
                        micros(latency.getMaxValue()), String.valueOf(retries.getAsLong()),
                        String.valueOf(clients), micros(waitNanos.sum() / (double) count),
                        micros(leaseNanos.sum() / (double) count), String.format(Locale.ROOT, "%.3f", windowSeconds),
                        windowSeconds > 0 ? String.format(Locale.ROOT, "%.1f", measured.sum() / windowSeconds)
                                : "NaN")).append('\n');
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

    /** The pause of a thread that has finished its measured loop while JMH waits for the others. */
    private static final long WARMDOWN_PAUSE_NANOS = 1_000_000L;

    /** How often a thread waiting for a permit before the measured window checks whether it has opened. */
    private static final long PERMIT_POLL_NANOS = 1_000_000L;

    @Benchmark
    public void transaction(PoolState state, Rng rng, Blackhole blackhole, Control control) throws SQLException {
        state.observeWindow(control);
        if (control.stopMeasurement) {
            // JMH's warm-down: the iteration is over, and JMH keeps calling the threads that have finished until the
            // last one completes its final transaction. Under the unfair throttle the finished threads kept barging
            // in, so a starved thread could wait minutes for a permit (5 minutes for oltpReadWrite at 4 connections
            // in the smoke run); stepping aside lets it finish at once. JMH counts none of these calls.
            LockSupport.parkNanos(WARMDOWN_PAUSE_NANOS);
            return;
        }
        // Throttle to poolSize + 16 contenders before touching the pool so the queue depth stays bounded.
        if (!state.acquirePermit(control)) {
            return;
        }
        try {
            long start = System.nanoTime();
            long acquired;
            try (Connection connection = state.poolUnderTest.dataSource().getConnection()) {
                acquired = System.nanoTime();
                state.transaction.run(connection, rng.random);
                blackhole.consume(connection);
            }
            long end = System.nanoTime();
            state.observeWindow(control);
            state.recordLatency(state.inMeasuredWindow(control), start, acquired, end);
        } finally {
            state.clientThrottle.release();
        }
    }
}
