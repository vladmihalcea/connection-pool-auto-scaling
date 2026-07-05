package com.vladmihalcea.phd.pool.adapter;

import com.vladmihalcea.phd.pool.api.PoolUnderTest;

/**
 * Single construction point for every {@link PoolUnderTest} implementation, so a benchmark or experiment
 * can select a pool by its short id ({@code hikari}, {@code dbcp2}, {@code agroal}) exactly the way the
 * reservoir repo's {@code StrategyCatalog} selects a sampling strategy by name.
 *
 * @author Vlad Mihalcea
 */
public final class PoolFactory {

    /** Pool ids exercised by the throughput sweep and the model-comparison figures. */
    public static final String[] REAL_POOLS = {"hikari", "dbcp2", "agroal"};

    private PoolFactory() {
    }

    public static PoolUnderTest create(String id, JdbcConfig jdbc, int initialSize) {
        return switch (id) {
            case "hikari" -> new HikariPoolUnderTest(jdbc, initialSize);
            case "dbcp2" -> new Dbcp2PoolUnderTest(jdbc, initialSize);
            case "agroal" -> new AgroalPoolUnderTest(jdbc, initialSize);
            default -> throw new IllegalArgumentException("Unknown pool id: " + id);
        };
    }
}
