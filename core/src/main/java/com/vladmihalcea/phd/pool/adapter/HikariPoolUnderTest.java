package com.vladmihalcea.phd.pool.adapter;

import com.vladmihalcea.phd.pool.api.PoolUnderTest;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;

/**
 * {@link PoolUnderTest} backed by HikariCP. Resizing is live: {@link HikariDataSource#setMaximumPoolSize(int)}
 * updates the pool cap immediately (growing lets new borrows succeed at once; shrinking retires idle
 * connections lazily via HikariCP's housekeeping, which the convergence plots handle by tracking
 * configured vs. actual size). {@code minimumIdle} is pinned to the max so the pool eagerly holds the
 * configured number of connections, matching a saturated OLTP deployment.
 *
 * @author Vlad Mihalcea
 */
public final class HikariPoolUnderTest implements PoolUnderTest {

    private final HikariDataSource dataSource;

    public HikariPoolUnderTest(JdbcConfig jdbc, int initialSize) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbc.url());
        config.setUsername(jdbc.username());
        config.setPassword(jdbc.password());
        config.setMaximumPoolSize(initialSize);
        config.setMinimumIdle(initialSize);
        config.setPoolName("hikari-uslpool");
        // 10 s is sufficient: the benchmark's client-throttle semaphore caps the pool-queue depth at
        // 16 waiters, so the worst-case wait (longTx ≈ 15 ms/tx) is ≤ 16 * 15 ms ≈ 250 ms ≪ 10 s.
        config.setConnectionTimeout(10_000);
        this.dataSource = new HikariDataSource(config);
    }

    @Override
    public DataSource dataSource() {
        return dataSource;
    }

    @Override
    public int getMaxPoolSize() {
        return dataSource.getMaximumPoolSize();
    }

    @Override
    public void setMaxPoolSize(int maxPoolSize) {
        // Order matters: when growing, raise the max before minIdle; when shrinking, lower minIdle first.
        if (maxPoolSize >= dataSource.getMaximumPoolSize()) {
            dataSource.setMaximumPoolSize(maxPoolSize);
            dataSource.setMinimumIdle(maxPoolSize);
        } else {
            dataSource.setMinimumIdle(maxPoolSize);
            dataSource.setMaximumPoolSize(maxPoolSize);
        }
    }

    @Override
    public String id() {
        return "hikari";
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
