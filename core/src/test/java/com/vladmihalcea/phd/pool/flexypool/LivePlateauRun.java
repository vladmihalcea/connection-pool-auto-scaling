package com.vladmihalcea.phd.pool.flexypool;

import com.vladmihalcea.flexypool.FlexyPoolDataSource;
import com.vladmihalcea.flexypool.adaptor.DBCP2PoolAdapter;
import com.vladmihalcea.flexypool.adaptor.HikariCPPoolAdapter;
import com.vladmihalcea.flexypool.adaptor.PoolAdapterFactory;
import com.vladmihalcea.flexypool.config.FlexyPoolConfiguration;
import com.vladmihalcea.phd.pool.adapter.JdbcConfig;
import com.vladmihalcea.phd.pool.common.util.Database;
import com.vladmihalcea.phd.pool.usl.UslFit;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.agroal.api.AgroalDataSource;
import io.agroal.api.configuration.supplier.AgroalDataSourceConfigurationSupplier;
import io.agroal.api.security.NamePrincipal;
import io.agroal.api.security.SimplePassword;
import org.apache.commons.dbcp2.BasicDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;

/**
 * Runs the {@link UslPlateauConnectionAcquisitionStrategy} live: a closed loop of client threads executes a
 * workload through a {@link FlexyPoolDataSource} wrapping HikariCP, DBCP2 or Agroal on PostgreSQL or MySQL,
 * while the strategy grows and shrinks the pool. The pool's maximum size is sampled every 250 ms during the
 * observation period at the end of the run, whose median is the size the strategy settled at.
 *
 * <p>Each pool starts at {@code minPoolSize} with at most one connection open, so the bootstrap pays the
 * pool's real cost of opening connections, and acquisition times out after 300 ms so the strategy's retry
 * absorbs a saturated borrow.
 *
 * @author Vlad Mihalcea
 */
public final class LivePlateauRun {

    private static final Logger LOGGER = LoggerFactory.getLogger(LivePlateauRun.class);

    /** The pools the live experiments exercise through FlexyPool. */
    public static final String[] POOLS = {"hikari", "dbcp2", "agroal"};

    private static final int ACQUISITION_TIMEOUT_MILLIS = 300;

    /** One transaction of the workload under test. */
    @FunctionalInterface
    public interface Transaction {
        void run(Connection connection, SplittableRandom random) throws SQLException;
    }

    /** The outcome of one live run. */
    public record Result(String database, String pool, String workload, int ceiling, int settledSize,
                         int plateauStart, int optimum, UslFit fit, double observedThroughput,
                         long committed, long failures) {

        public String fitSummary() {
            return fit == null ? "n/a" : String.format("lambda=%.0f sigma=%.4f kappa=%.6f R2=%.3f",
                    fit.model().lambda(), fit.model().sigma(), fit.model().kappa(), fit.rSquared());
        }
    }

    private final Database database;
    private final String poolId;
    private final String workloadId;
    private final Transaction transaction;
    private final UslPlateauConnectionAcquisitionStrategy.Config config;
    private final int clientThreads;
    private final long runMillis;
    private final long observeFromMillis;

    public LivePlateauRun(Database database, String poolId, String workloadId, Transaction transaction,
                          UslPlateauConnectionAcquisitionStrategy.Config config, int clientThreads,
                          long runMillis, long observeFromMillis) {
        this.database = database;
        this.poolId = poolId;
        this.workloadId = workloadId;
        this.transaction = transaction;
        this.config = config;
        this.clientThreads = clientThreads;
        this.runMillis = runMillis;
        this.observeFromMillis = observeFromMillis;
    }

    public Result run() throws Exception {
        JdbcConfig jdbc = database.jdbcConfig();
        int initial = config.minPoolSize();
        return switch (poolId) {
            case "hikari" -> {
                HikariConfig hikariConfig = new HikariConfig();
                hikariConfig.setJdbcUrl(jdbc.url());
                hikariConfig.setUsername(jdbc.username());
                hikariConfig.setPassword(jdbc.password());
                hikariConfig.setMaximumPoolSize(initial);
                hikariConfig.setMinimumIdle(1);
                hikariConfig.setConnectionTimeout(ACQUISITION_TIMEOUT_MILLIS);
                hikariConfig.setPoolName("plateau-hikari");
                HikariDataSource dataSource = new HikariDataSource(hikariConfig);
                yield execute(dataSource, HikariCPPoolAdapter.FACTORY, dataSource::getMaximumPoolSize,
                        dataSource::close);
            }
            case "dbcp2" -> {
                BasicDataSource dataSource = new BasicDataSource();
                dataSource.setUrl(jdbc.url());
                dataSource.setUsername(jdbc.username());
                dataSource.setPassword(jdbc.password());
                dataSource.setMaxTotal(initial);
                // FlexyPool's DBCP2 adapter only moves maxTotal; with the default maxIdle of 8, DBCP2 would
                // destroy every returned connection above 8 idle ones, churning connections during a grow.
                dataSource.setMaxIdle(config.maxPoolSize());
                dataSource.setMinIdle(0);
                dataSource.setInitialSize(1);
                dataSource.setMaxWaitMillis(ACQUISITION_TIMEOUT_MILLIS);
                yield execute(dataSource, DBCP2PoolAdapter.FACTORY, dataSource::getMaxTotal, dataSource::close);
            }
            case "agroal" -> {
                AgroalDataSourceConfigurationSupplier agroalConfig = new AgroalDataSourceConfigurationSupplier()
                        .connectionPoolConfiguration(cp -> cp
                                .maxSize(initial)
                                .minSize(1)
                                .initialSize(1)
                                .acquisitionTimeout(Duration.ofMillis(ACQUISITION_TIMEOUT_MILLIS))
                                .connectionFactoryConfiguration(cf -> cf
                                        .jdbcUrl(jdbc.url())
                                        .principal(new NamePrincipal(jdbc.username()))
                                        .credential(new SimplePassword(jdbc.password()))));
                AgroalDataSource dataSource = AgroalDataSource.from(agroalConfig);
                yield execute(dataSource, AgroalPoolAdapter.FACTORY,
                        () -> dataSource.getConfiguration().connectionPoolConfiguration().maxSize(),
                        dataSource::close);
            }
            default -> throw new IllegalArgumentException("Unknown pool: " + poolId);
        };
    }

    private <T extends DataSource> Result execute(T dataSource, PoolAdapterFactory<T> adapterFactory,
                                                  IntSupplier maxPoolSize, AutoCloseable closePool)
            throws Exception {
        UslPlateauConnectionAcquisitionStrategy.Factory<T> strategyFactory =
                new UslPlateauConnectionAcquisitionStrategy.Factory<>(config);
        // The metrics tap feeds FlexyPool's own concurrency histograms to the strategy, so it can tell
        // saturated windows from demand-limited ones and resizes in progress.
        FlexyPoolConfiguration<T> configuration =
                new FlexyPoolConfiguration.Builder<>("plateau-" + poolId, dataSource, adapterFactory)
                        .setMetricsFactory(WindowedPoolMetrics.factory(null))
                        .setJmxEnabled(false)
                        .build();
        FlexyPoolDataSource<T> flexyPool = new FlexyPoolDataSource<>(configuration, strategyFactory);
        flexyPool.start();
        UslPlateauConnectionAcquisitionStrategy<T> strategy = strategyFactory.strategy();

        ExecutorService executor = Executors.newFixedThreadPool(clientThreads);
        AtomicBoolean stop = new AtomicBoolean();
        AtomicLong committed = new AtomicLong();
        AtomicLong failures = new AtomicLong();
        List<Integer> observedSizes = new ArrayList<>();
        try {
            for (int t = 0; t < clientThreads; t++) {
                final long seed = t;
                executor.submit(() -> {
                    SplittableRandom random = new SplittableRandom(seed);
                    while (!stop.get()) {
                        try (Connection connection = flexyPool.getConnection()) {
                            transaction.run(connection, random);
                            committed.incrementAndGet();
                        } catch (Exception e) {
                            failures.incrementAndGet();
                        }
                    }
                });
            }
            long start = System.currentTimeMillis();
            long committedAtObserve = -1;
            while (System.currentTimeMillis() - start < runMillis) {
                Thread.sleep(250);
                if (System.currentTimeMillis() - start >= observeFromMillis) {
                    if (committedAtObserve < 0) {
                        committedAtObserve = committed.get();
                    }
                    observedSizes.add(maxPoolSize.getAsInt());
                }
            }
            long committedAtEnd = committed.get();
            stop.set(true);
            executor.shutdown();
            executor.awaitTermination(20, TimeUnit.SECONDS);

            Collections.sort(observedSizes);
            double observedSeconds = (runMillis - observeFromMillis) / 1000.0;
            Result result = new Result(database.id(), poolId, workloadId, config.maxPoolSize(),
                    observedSizes.get(observedSizes.size() / 2), strategy.estimatedPlateauStart(),
                    strategy.estimatedOptimum(), strategy.lastFit(),
                    (committedAtEnd - committedAtObserve) / observedSeconds, committed.get(), failures.get());
            LOGGER.info("Plateau strategy {}/{}/{}: settledSize={}, N_eps={}, N*={}, tps={}, committed={}, "
                            + "failures={}, fit={} (ceiling {})",
                    result.database(), result.pool(), result.workload(), result.settledSize(),
                    result.plateauStart(), result.optimum(), String.format("%.0f", result.observedThroughput()),
                    result.committed(), result.failures(), result.fitSummary(), result.ceiling());
            return result;
        } finally {
            stop.set(true);
            executor.shutdownNow();
            if (strategy != null) {
                strategy.close();
            }
            flexyPool.close();
            closePool.close();
        }
    }
}
