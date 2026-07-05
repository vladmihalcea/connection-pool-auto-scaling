package com.vladmihalcea.phd.pool.workload;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * A pgbench-style {@code accounts} schema plus a seeded data load, used as the OLTP fixture for every
 * throughput and auto-scaling experiment. Kept deliberately small and dependency-free (plain JDBC DDL)
 * so it can be created against any PostgreSQL data source, real or Testcontainers-managed.
 *
 * @author Vlad Mihalcea
 */
public final class Schema {

    private static final Logger LOGGER = LoggerFactory.getLogger(Schema.class);

    /** One "scale factor" unit corresponds to this many account rows (pgbench convention). */
    public static final int ROWS_PER_SCALE = 1_000;

    private final int scaleFactor;

    public Schema(int scaleFactor) {
        this.scaleFactor = Math.max(1, scaleFactor);
    }

    public int accountCount() {
        return scaleFactor * ROWS_PER_SCALE;
    }

    /**
     * (Re)creates the schema and loads {@link #accountCount()} rows. Idempotent: drops any previous
     * table first so a fork always starts from a known state.
     */
    public void create(DataSource dataSource) {
        long start = System.nanoTime();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement ddl = connection.createStatement()) {
                ddl.execute("DROP TABLE IF EXISTS accounts");
                ddl.execute("""
                        CREATE TABLE accounts (
                            id      BIGINT       PRIMARY KEY,
                            balance BIGINT       NOT NULL,
                            filler  VARCHAR(120) NOT NULL
                        )
                        """);
            }
            connection.commit();

            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO accounts (id, balance, filler) VALUES (?, ?, ?)")) {
                int rows = accountCount();
                for (int id = 1; id <= rows; id++) {
                    insert.setLong(1, id);
                    insert.setLong(2, 100_000L);
                    insert.setString(3, "account-" + id);
                    insert.addBatch();
                    if (id % 1_000 == 0) {
                        insert.executeBatch();
                    }
                }
                insert.executeBatch();
            }
            connection.commit();

            try (Statement analyze = connection.createStatement()) {
                analyze.execute("ANALYZE accounts");
            }
            connection.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not create the accounts schema", e);
        }
        LOGGER.info("Created accounts schema with {} rows in {} ms",
                accountCount(), (System.nanoTime() - start) / 1_000_000);
    }
}
