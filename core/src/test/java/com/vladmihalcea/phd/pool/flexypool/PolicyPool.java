package com.vladmihalcea.phd.pool.flexypool;

import com.vladmihalcea.flexypool.FlexyPoolDataSource;
import com.vladmihalcea.flexypool.adaptor.DBCP2PoolAdapter;
import com.vladmihalcea.flexypool.adaptor.HikariCPPoolAdapter;
import com.vladmihalcea.flexypool.adaptor.PoolAdapterFactory;
import com.vladmihalcea.flexypool.config.FlexyPoolConfiguration;
import com.vladmihalcea.phd.pool.adapter.JdbcConfig;
import com.vladmihalcea.phd.pool.common.util.Database;
import com.vladmihalcea.phd.pool.controller.ScalingPolicy;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.agroal.api.AgroalDataSource;
import io.agroal.api.configuration.supplier.AgroalDataSourceConfigurationSupplier;
import io.agroal.api.security.NamePrincipal;
import io.agroal.api.security.SimplePassword;
import org.apache.commons.dbcp2.BasicDataSource;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A HikariCP, DBCP2, or Agroal pool behind a {@link FlexyPoolDataSource} whose size a {@link ScalingPolicy} sets
 * through a {@link PolicyConnectionAcquisitionStrategy}, with the {@link WindowedPoolMetrics} tap, so the live runs
 * and the JMH load-profile benchmark drive the policies through the same path. Each pool starts at the strategy's
 * initial size with one connection open, so a growing policy pays the pool's real cost of opening connections.
 *
 * @author Vlad Mihalcea
 */
public final class PolicyPool implements AutoCloseable {

    private final DataSource dataSource;
    private final PolicyConnectionAcquisitionStrategy<?> strategy;
    private final AutoCloseable flexyPool;
    private final AutoCloseable targetPool;

    private PolicyPool(DataSource dataSource, PolicyConnectionAcquisitionStrategy<?> strategy, AutoCloseable flexyPool,
                       AutoCloseable targetPool) {
        this.dataSource = dataSource;
        this.strategy = strategy;
        this.flexyPool = flexyPool;
        this.targetPool = targetPool;
    }

    /**
     * @param database                 the database to connect to
     * @param poolId                   {@code hikari}, {@code dbcp2}, or {@code agroal}
     * @param config                   the strategy's sizes and cadence
     * @param policy                   creates the policy (called once)
     * @param listener                 receives every decision, or {@code null}
     * @param acquisitionTimeoutMillis the pool's acquisition timeout
     */
    public static PolicyPool open(Database database, String poolId, PolicyConnectionAcquisitionStrategy.Config config,
                                  Supplier<ScalingPolicy> policy,
                                  Consumer<PolicyConnectionAcquisitionStrategy.Decision> listener,
                                  long acquisitionTimeoutMillis) {
        JdbcConfig jdbc = database.jdbcConfig();
        int initial = config.initialPoolSize();
        return switch (poolId) {
            case "hikari" -> {
                HikariConfig hikariConfig = new HikariConfig();
                hikariConfig.setJdbcUrl(jdbc.url());
                hikariConfig.setUsername(jdbc.username());
                hikariConfig.setPassword(jdbc.password());
                hikariConfig.setMaximumPoolSize(initial);
                hikariConfig.setMinimumIdle(1);
                hikariConfig.setConnectionTimeout(acquisitionTimeoutMillis);
                hikariConfig.setPoolName("policy-hikari");
                HikariDataSource dataSource = new HikariDataSource(hikariConfig);
                yield wrap(dataSource, HikariCPPoolAdapter.FACTORY, dataSource::close, poolId, config, policy, listener);
            }
            case "dbcp2" -> {
                BasicDataSource dataSource = new BasicDataSource();
                dataSource.setUrl(jdbc.url());
                dataSource.setUsername(jdbc.username());
                dataSource.setPassword(jdbc.password());
                dataSource.setMaxTotal(initial);
                // FlexyPool's DBCP2 adapter moves only maxTotal; a maxIdle below it would destroy returned
                // connections during a grow.
                dataSource.setMaxIdle(config.maxPoolSize());
                dataSource.setMinIdle(0);
                dataSource.setInitialSize(1);
                dataSource.setMaxWaitMillis(acquisitionTimeoutMillis);
                yield wrap(dataSource, DBCP2PoolAdapter.FACTORY, dataSource::close, poolId, config, policy, listener);
            }
            case "agroal" -> {
                AgroalDataSourceConfigurationSupplier agroalConfig = new AgroalDataSourceConfigurationSupplier()
                        .connectionPoolConfiguration(cp -> cp
                                .maxSize(initial)
                                .minSize(1)
                                .initialSize(1)
                                .acquisitionTimeout(Duration.ofMillis(acquisitionTimeoutMillis))
                                .connectionFactoryConfiguration(cf -> cf
                                        .jdbcUrl(jdbc.url())
                                        .principal(new NamePrincipal(jdbc.username()))
                                        .credential(new SimplePassword(jdbc.password()))));
                AgroalDataSource dataSource;
                try {
                    dataSource = AgroalDataSource.from(agroalConfig);
                } catch (java.sql.SQLException e) {
                    throw new IllegalStateException("Could not start the Agroal pool", e);
                }
                yield wrap(dataSource, AgroalPoolAdapter.FACTORY, dataSource::close, poolId, config, policy, listener);
            }
            default -> throw new IllegalArgumentException("Unknown pool: " + poolId);
        };
    }

    private static <T extends DataSource> PolicyPool wrap(T dataSource, PoolAdapterFactory<T> adapterFactory,
                                                          AutoCloseable closePool, String poolId,
                                                          PolicyConnectionAcquisitionStrategy.Config config,
                                                          Supplier<ScalingPolicy> policy,
                                                          Consumer<PolicyConnectionAcquisitionStrategy.Decision> listener) {
        PolicyConnectionAcquisitionStrategy.Factory<T> strategyFactory =
                new PolicyConnectionAcquisitionStrategy.Factory<>(config, policy, listener);
        FlexyPoolConfiguration<T> configuration =
                new FlexyPoolConfiguration.Builder<>("policy-" + poolId, dataSource, adapterFactory)
                        .setMetricsFactory(WindowedPoolMetrics.factory(null))
                        .setJmxEnabled(false)
                        .build();
        FlexyPoolDataSource<T> flexyPool = new FlexyPoolDataSource<>(configuration, strategyFactory);
        flexyPool.start();
        return new PolicyPool(flexyPool, strategyFactory.strategy(), flexyPool::close, closePool);
    }

    /** The FlexyPool-wrapped data source the clients borrow from. */
    public DataSource dataSource() {
        return dataSource;
    }

    /** The policy the strategy drives. */
    public ScalingPolicy policy() {
        return strategy.policy();
    }

    /** Stops the policy's decisions (the pool stays open until {@link #close()}). */
    public void stopDecisions() {
        strategy.close();
    }

    @Override
    public void close() throws Exception {
        strategy.close();
        try {
            flexyPool.close();
        } finally {
            targetPool.close();
        }
    }
}
