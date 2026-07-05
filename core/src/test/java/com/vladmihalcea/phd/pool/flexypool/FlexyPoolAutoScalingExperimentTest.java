package com.vladmihalcea.phd.pool.flexypool;

import com.vladmihalcea.flexypool.FlexyPoolDataSource;
import com.vladmihalcea.flexypool.adaptor.HikariCPPoolAdapter;
import com.vladmihalcea.flexypool.common.ConfigurationProperties;
import com.vladmihalcea.flexypool.config.FlexyPoolConfiguration;
import com.vladmihalcea.flexypool.metric.Histogram;
import com.vladmihalcea.flexypool.metric.Metrics;
import com.vladmihalcea.flexypool.metric.MetricsFactory;
import com.vladmihalcea.flexypool.metric.Timer;
import com.vladmihalcea.flexypool.strategy.ConnectionAcquisitionStrategyFactory;
import com.vladmihalcea.flexypool.strategy.IncrementPoolOnTimeoutConnectionAcquisitionStrategy;
import com.vladmihalcea.flexypool.strategy.RetryConnectionAcquisitionStrategy;
import com.vladmihalcea.phd.pool.common.util.PostgreSqlSupport;
import com.vladmihalcea.phd.pool.workload.OltpWorkload;
import com.vladmihalcea.phd.pool.workload.Schema;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.util.SplittableRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises FlexyPool's dynamic-sizing strategies over a real HikariCP pool on PostgreSQL:
 * <ol>
 *   <li>the built-in {@link IncrementPoolOnTimeoutConnectionAcquisitionStrategy} +
 *       {@link RetryConnectionAcquisitionStrategy} grow the pool <em>beyond its initial maximum</em> under
 *       contention, and</li>
 *   <li>the new {@link ThroughputOptimizingConnectionAcquisitionStrategy}, built on those two, grows to
 *       explore larger sizes and then caps the pool at a discovered throughput-optimal size instead of
 *       running away to the ceiling.</li>
 * </ol>
 * Database-gated: skipped (not failed) when neither a local PostgreSQL server nor Docker is reachable.
 *
 * @author Vlad Mihalcea
 */
public class FlexyPoolAutoScalingExperimentTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(FlexyPoolAutoScalingExperimentTest.class);

    @BeforeAll
    public static void setUp() {
        PostgreSqlSupport.assumeDatabaseAvailable();
        new Schema(10).create(PostgreSqlSupport.adminDataSource());
    }

    @Test
    public void incrementAndRetryGrowThePoolBeyondInitialMax() throws Exception {
        int initialMax = 4;
        int maxOvergrow = 16;
        int holders = 12;

        HikariDataSource hikari = hikariDataSource(initialMax);
        FlexyPoolDataSource<HikariDataSource> flexyPool = flexyPool(hikari,
                new RetryConnectionAcquisitionStrategy.Factory<>(3),
                new IncrementPoolOnTimeoutConnectionAcquisitionStrategy.Factory<>(maxOvergrow, 100));
        ExecutorService executor = Executors.newFixedThreadPool(holders);
        CountDownLatch acquired = new CountDownLatch(holders);
        CountDownLatch release = new CountDownLatch(1);
        try {
            // Saturate: every holder borrows and keeps its connection until released. With only
            // `initialMax` connections the surplus borrows time out, and the increment strategy grows
            // the pool one connection at a time to satisfy them.
            for (int i = 0; i < holders; i++) {
                executor.submit(() -> {
                    try (Connection connection = flexyPool.getConnection()) {
                        acquired.countDown();
                        release.await(30, TimeUnit.SECONDS);
                        connection.isValid(1);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
            }
            assertTrue(acquired.await(30, TimeUnit.SECONDS),
                    "all " + holders + " borrowers should be served after the pool grows");
            int grownSize = hikari.getMaximumPoolSize();
            LOGGER.info("FlexyPool grew HikariCP from {} to {} (maxOvergrow {})", initialMax, grownSize, maxOvergrow);
            assertTrue(grownSize > initialMax,
                    "the pool must grow beyond its initial max " + initialMax + ", got " + grownSize);
            assertTrue(grownSize <= maxOvergrow,
                    "the pool must not exceed maxOvergrow " + maxOvergrow + ", got " + grownSize);
        } finally {
            release.countDown();
            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);
            flexyPool.close();
            hikari.close();
        }
    }

    @Test
    public void throughputOptimizingStrategyGrowsThenCaps() throws Exception {
        int initialMax = 2;
        int minSize = 2;
        int maxOvergrow = 40;
        int clientThreads = 24;
        long runMillis = 6_000;

        HikariDataSource hikari = hikariDataSource(initialMax);
        ThroughputOptimizingConnectionAcquisitionStrategy.Config config =
                new ThroughputOptimizingConnectionAcquisitionStrategy.Config(
                        minSize, maxOvergrow, Integer.MAX_VALUE, 2, 500L, 4);
        ThroughputOptimizingConnectionAcquisitionStrategy.Factory<HikariDataSource> factory =
                new ThroughputOptimizingConnectionAcquisitionStrategy.Factory<>(config);
        FlexyPoolDataSource<HikariDataSource> flexyPool = flexyPool(hikari, factory);

        OltpWorkload workload = OltpWorkload.byName("readMostly", new Schema(10).accountCount());
        ExecutorService executor = Executors.newFixedThreadPool(clientThreads);
        AtomicBoolean stop = new AtomicBoolean();
        AtomicLong committed = new AtomicLong();
        AtomicLong failures = new AtomicLong();
        AtomicLong maxObservedSize = new AtomicLong(initialMax);
        try {
            for (int t = 0; t < clientThreads; t++) {
                final long seed = t;
                executor.submit(() -> {
                    SplittableRandom random = new SplittableRandom(seed);
                    while (!stop.get()) {
                        try (Connection connection = flexyPool.getConnection()) {
                            workload.runTransaction(connection, bound -> random.nextInt(bound));
                            committed.incrementAndGet();
                            maxObservedSize.accumulateAndGet(hikari.getMaximumPoolSize(), Math::max);
                        } catch (Exception e) {
                            failures.incrementAndGet();
                        }
                    }
                });
            }
            Thread.sleep(runMillis);
            stop.set(true);
            executor.shutdown();
            executor.awaitTermination(20, TimeUnit.SECONDS);

            int finalSize = hikari.getMaximumPoolSize();
            LOGGER.info("Throughput-optimizing strategy: committed={}, failures={}, peakSize={}, finalSize={} (ceiling {})",
                    committed.get(), failures.get(), maxObservedSize.get(), finalSize, maxOvergrow);

            assertTrue(committed.get() > 0, "the closed loop should commit transactions");
            assertTrue(maxObservedSize.get() > initialMax,
                    "the strategy must explore sizes beyond the initial max " + initialMax
                            + ", peak was " + maxObservedSize.get());
            // The optimum for a 24-thread read-mostly load is bounded by the client concurrency, well below
            // the 40 ceiling — so a working search must cap there instead of running away to maxOvergrow.
            assertTrue(finalSize >= minSize && finalSize < maxOvergrow,
                    "the strategy must cap the pool below the ceiling " + maxOvergrow
                            + " at the discovered optimum, got " + finalSize);
        } finally {
            stop.set(true);
            executor.shutdownNow();
            flexyPool.close();
            hikari.close();
        }
    }

    private static HikariDataSource hikariDataSource(int initialMax) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(PostgreSqlSupport.jdbcUrl());
        config.setUsername(PostgreSqlSupport.username());
        config.setPassword(PostgreSqlSupport.password());
        config.setMaximumPoolSize(initialMax);
        config.setMinimumIdle(1);
        config.setPoolName("flexy-hikari");
        // Short so a saturated borrow times out quickly and the increment strategy grows the pool fast.
        config.setConnectionTimeout(300);
        return new HikariDataSource(config);
    }

    @SafeVarargs
    private static FlexyPoolDataSource<HikariDataSource> flexyPool(
            HikariDataSource hikari,
            ConnectionAcquisitionStrategyFactory<?, HikariDataSource>... strategyFactories) {
        FlexyPoolConfiguration<HikariDataSource> configuration =
                new FlexyPoolConfiguration.Builder<>("flexy-uslpool", hikari, HikariCPPoolAdapter.FACTORY)
                        .setMetricsFactory(NopMetrics.FACTORY)
                        .setJmxEnabled(false)
                        .build();
        FlexyPoolDataSource<HikariDataSource> flexyPool =
                new FlexyPoolDataSource<>(configuration, strategyFactories);
        flexyPool.start();
        return flexyPool;
    }

    /** A no-op {@link Metrics} so the test needs no Dropwizard/Micrometer metrics module on the classpath. */
    private static final class NopMetrics implements Metrics {

        static final MetricsFactory FACTORY = configurationProperties -> new NopMetrics();

        private static final Histogram HISTOGRAM = value -> {
        };
        private static final Timer TIMER = (duration, timeUnit) -> {
        };

        @Override
        public Histogram histogram(String name) {
            return HISTOGRAM;
        }

        @Override
        public Timer timer(String name) {
            return TIMER;
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }
    }
}
