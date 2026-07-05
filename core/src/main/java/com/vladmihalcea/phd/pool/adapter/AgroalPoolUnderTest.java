package com.vladmihalcea.phd.pool.adapter;

import com.vladmihalcea.phd.pool.api.PoolUnderTest;
import io.agroal.api.AgroalDataSource;
import io.agroal.api.configuration.supplier.AgroalDataSourceConfigurationSupplier;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Duration;

/**
 * {@link PoolUnderTest} backed by Agroal. Contrary to the original plan's worst case, Agroal supports
 * <em>live</em> resizing without a pool swap: {@code getConfiguration().connectionPoolConfiguration()
 * .setMaxSize(int)} takes effect immediately, so the same in-place actuator used for HikariCP and DBCP2
 * applies here too. This is itself a finding for the paper's "auto-scaling readiness" comparison.
 *
 * @author Vlad Mihalcea
 */
public final class AgroalPoolUnderTest implements PoolUnderTest {

    private final AgroalDataSource dataSource;

    public AgroalPoolUnderTest(JdbcConfig jdbc, int initialSize) {
        AgroalDataSourceConfigurationSupplier config = new AgroalDataSourceConfigurationSupplier()
                .connectionPoolConfiguration(cp -> cp
                        .maxSize(initialSize)
                        .minSize(initialSize)
                        .initialSize(initialSize)
                        .acquisitionTimeout(Duration.ofSeconds(10))
                        .connectionFactoryConfiguration(cf -> cf
                                .jdbcUrl(jdbc.url())
                                .principal(new io.agroal.api.security.NamePrincipal(jdbc.username()))
                                .credential(new io.agroal.api.security.SimplePassword(jdbc.password()))));
        try {
            this.dataSource = AgroalDataSource.from(config);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not create the Agroal pool", e);
        }
    }

    @Override
    public DataSource dataSource() {
        return dataSource;
    }

    @Override
    public int getMaxPoolSize() {
        return dataSource.getConfiguration().connectionPoolConfiguration().maxSize();
    }

    @Override
    public void setMaxPoolSize(int maxPoolSize) {
        var pool = dataSource.getConfiguration().connectionPoolConfiguration();
        if (maxPoolSize >= pool.maxSize()) {
            pool.setMaxSize(maxPoolSize);
            pool.setMinSize(maxPoolSize);
        } else {
            pool.setMinSize(maxPoolSize);
            pool.setMaxSize(maxPoolSize);
        }
    }

    @Override
    public String id() {
        return "agroal";
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
