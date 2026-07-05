package com.vladmihalcea.phd.pool.jmh;

import com.vladmihalcea.phd.pool.adapter.JdbcConfig;
import com.vladmihalcea.phd.pool.adapter.PoolFactory;
import com.vladmihalcea.phd.pool.api.PoolUnderTest;
import com.vladmihalcea.phd.pool.common.util.PostgreSqlSupport;
import com.vladmihalcea.phd.pool.workload.OltpWorkload;
import com.vladmihalcea.phd.pool.workload.Schema;
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

import java.sql.Connection;
import java.sql.SQLException;
import java.util.SplittableRandom;
import java.util.concurrent.Semaphore;
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
 *
 * @author Vlad Mihalcea
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
public class ThroughputVsPoolSizeBenchmark {

    @State(Scope.Benchmark)
    public static class PoolState {

        @Param({"hikari", "dbcp2", "agroal"})
        public String pool;

        @Param({"1", "2", "4", "8", "16", "24", "32", "48", "64", "96"})
        public int poolSize;

        @Param({"readMostly", "writeHeavy", "longTx"})
        public String workload;

        private PoolUnderTest poolUnderTest;
        OltpWorkload oltpWorkload;

        /**
         * Limits concurrent pool contenders to {@code poolSize + 16} (capped at the 128 JMH threads), so
         * the pool wait-queue never exceeds 16 waiters. This keeps the pool fully saturated while
         * preventing the HikariCP / DBCP2 / Agroal unfair handoff from starving a waiter past the acquire
         * timeout — which the raw JMH thread count would otherwise cause at small pools (a 100+:1 ratio)
         * and a size-proportional queue would cause at the larger pools.
         */
        Semaphore clientThrottle;

        @Setup(Level.Trial)
        public void setUp() {
            Schema schema = new Schema(10); // 10k accounts: enough to avoid a single hot row dominating
            schema.create(PostgreSqlSupport.adminDataSource());

            JdbcConfig jdbc = new JdbcConfig(
                    PostgreSqlSupport.jdbcUrl(), PostgreSqlSupport.username(), PostgreSqlSupport.password());
            poolUnderTest = PoolFactory.create(pool, jdbc, poolSize);
            oltpWorkload = OltpWorkload.byName(workload, schema.accountCount());

            int permits = Math.min(poolSize + 16, 128);
            clientThrottle = new Semaphore(permits, /*fair=*/true);
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            if (poolUnderTest != null) {
                poolUnderTest.close();
            }
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
        try (Connection connection = state.poolUnderTest.dataSource().getConnection()) {
            state.oltpWorkload.runTransaction(connection, bound -> rng.random.nextInt(bound));
            blackhole.consume(connection);
        } finally {
            state.clientThrottle.release();
        }
    }
}
