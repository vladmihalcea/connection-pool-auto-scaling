package com.vladmihalcea.phd.pool.api;

import javax.sql.DataSource;

/**
 * Abstraction over a resizable JDBC connection pool, so the throughput sweep (E1) and the auto-scaling
 * controller can be written once against every pool implementation (HikariCP, Apache DBCP2, Agroal) and
 * against the in-memory {@code SimulatedPoolUnderTest} used for Docker-less reproduction.
 * <p>
 * This mirrors FlexyPool's own {@code PoolAdapter} SPI ({@code getMaxPoolSize} / {@code setMaxPoolSize});
 * the {@code adapter} package bridges the two so the controller can equally drive a real FlexyPool
 * deployment.
 *
 * @author Vlad Mihalcea
 */
public interface PoolUnderTest extends AutoCloseable {

    /**
     * The {@link DataSource} to borrow connections from. For real pools this is the live pool; for the
     * simulator it is a synthetic data source whose {@code getConnection()} latency follows a USL curve.
     */
    DataSource dataSource();

    /**
     * @return the current maximum pool size (the actuator's observed value).
     */
    int getMaxPoolSize();

    /**
     * Live-resize the pool. Growing must let more connections be borrowed concurrently; shrinking caps
     * new borrows (in-use connections may be retired lazily, which the convergence plots account for by
     * showing configured vs. actual size).
     *
     * @param maxPoolSize the new maximum pool size.
     */
    void setMaxPoolSize(int maxPoolSize);

    /**
     * @return a short identifier for CSV columns and figures (e.g. {@code hikari}, {@code dbcp2},
     * {@code agroal}, {@code sim}).
     */
    String id();

    @Override
    void close();
}
