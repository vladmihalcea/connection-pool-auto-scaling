package com.vladmihalcea.phd.pool.adapter;

import com.vladmihalcea.phd.pool.api.PoolUnderTest;
import org.apache.commons.dbcp2.BasicDataSource;

import javax.sql.DataSource;
import java.sql.SQLException;

/**
 * {@link PoolUnderTest} backed by Apache Commons DBCP2. Resizing is live via
 * {@link BasicDataSource#setMaxTotal(int)}; {@code maxIdle} is kept equal to {@code maxTotal} so the
 * pool can hold every connection it is allowed to open. DBCP2 grows on demand up to {@code maxTotal}
 * and evicts idle connections down to {@code maxIdle} through its evictor thread.
 *
 * @author Vlad Mihalcea
 */
public final class Dbcp2PoolUnderTest implements PoolUnderTest {

    private final BasicDataSource dataSource;

    public Dbcp2PoolUnderTest(JdbcConfig jdbc, int initialSize) {
        BasicDataSource ds = new BasicDataSource();
        ds.setUrl(jdbc.url());
        ds.setUsername(jdbc.username());
        ds.setPassword(jdbc.password());
        ds.setMaxTotal(initialSize);
        ds.setMaxIdle(initialSize);
        ds.setInitialSize(initialSize);
        // 10 s is sufficient: the benchmark's client-throttle semaphore caps the pool-queue depth
        // (see ThroughputVsPoolSizeBenchmark for the bound analysis).
        ds.setMaxWaitMillis(10_000);
        this.dataSource = ds;
    }

    @Override
    public DataSource dataSource() {
        return dataSource;
    }

    @Override
    public int getMaxPoolSize() {
        return dataSource.getMaxTotal();
    }

    @Override
    public void setMaxPoolSize(int maxPoolSize) {
        if (maxPoolSize >= dataSource.getMaxTotal()) {
            dataSource.setMaxTotal(maxPoolSize);
            dataSource.setMaxIdle(maxPoolSize);
        } else {
            dataSource.setMaxIdle(maxPoolSize);
            dataSource.setMaxTotal(maxPoolSize);
        }
    }

    @Override
    public String id() {
        return "dbcp2";
    }

    @Override
    public void close() {
        try {
            dataSource.close();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not close the DBCP2 pool", e);
        }
    }
}
