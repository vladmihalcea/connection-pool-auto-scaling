package com.vladmihalcea.phd.pool.jmh;

import com.vladmihalcea.phd.pool.common.util.PostgreSqlSupport;
import com.vladmihalcea.phd.pool.workload.OltpWorkload;
import com.vladmihalcea.phd.pool.workload.Schema;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

/**
 * Smoke test for the whole JMH + Docker + PostgreSQL + pool plumbing: borrow a connection from a
 * fixed-size HikariCP pool, run one read-mostly transaction, release. Proves the harness end-to-end
 * before the real throughput sweep (E1) is trusted. Not a scientific measurement.
 *
 * @author Vlad Mihalcea
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
public class SanityBenchmark {

    @State(Scope.Benchmark)
    public static class PoolState {

        private HikariDataSource dataSource;
        OltpWorkload workload;

        @Setup(Level.Trial)
        public void setUp() {
            Schema schema = new Schema(1);
            schema.create(PostgreSqlSupport.adminDataSource());

            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(PostgreSqlSupport.jdbcUrl());
            config.setUsername(PostgreSqlSupport.username());
            config.setPassword(PostgreSqlSupport.password());
            config.setMaximumPoolSize(16);
            config.setPoolName("sanity");
            dataSource = new HikariDataSource(config);

            workload = OltpWorkload.readMostly(schema.accountCount());
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            if (dataSource != null) {
                dataSource.close();
            }
        }
    }

    @State(Scope.Thread)
    public static class Rng {
        final SplittableRandom random = new SplittableRandom(42);
    }

    @Benchmark
    public void acquireRunRelease(PoolState state, Rng rng, Blackhole blackhole) throws SQLException {
        try (Connection connection = state.dataSource.getConnection()) {
            state.workload.runTransaction(connection, bound -> rng.random.nextInt(bound));
            blackhole.consume(connection);
        }
    }
}
