package com.vladmihalcea.phd.pool.workload.sysbench;

import com.vladmihalcea.phd.pool.workload.Schema;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.SplittableRandom;

/**
 * The tables of sysbench's OLTP tests ({@code oltp_common.lua}): {@code sbtest1} to {@code sbtestN}, each
 * with {@code tableSize} rows of an integer {@code k}, a 120-character {@code c}, and a 60-character
 * {@code pad}, and the secondary index on {@code k} built after the load, as sysbench builds it. The ids are
 * written explicitly (sysbench declares them auto-increment, which only its prepare step relies on). The DDL
 * is portable between PostgreSQL and MySQL.
 * <p>
 * Loading takes seconds, so {@link #ensure} keeps tables that already hold the expected rows: like
 * sysbench's prepare step, the data is created once and reused across runs. The workload's delete-insert
 * pairs keep every table at its size.
 *
 * <p>This is a sysbench-<em>derived</em> fixture for the pool-sizing experiments: sysbench itself keeps one
 * connection per thread, so it cannot drive a Java connection pool.
 *
 * @author Vlad Mihalcea
 */
public final class SysbenchSchema {

    private static final int BATCH_SIZE = 1_000;

    private final int tables;
    private final int tableSize;

    public SysbenchSchema(int tables, int tableSize) {
        if (tables < 1 || tableSize < 1) {
            throw new IllegalArgumentException("tables and tableSize must be positive");
        }
        this.tables = tables;
        this.tableSize = tableSize;
    }

    public int tables() {
        return tables;
    }

    public int tableSize() {
        return tableSize;
    }

    /** Creates and loads the tables unless every one of them already holds {@code tableSize} rows. */
    public void ensure(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            if (!isLoaded(connection)) {
                create(connection);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not prepare the sysbench tables", e);
        }
    }

    private boolean isLoaded(Connection connection) {
        for (int t = 1; t <= tables; t++) {
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM sbtest" + t)) {
                if (!resultSet.next() || resultSet.getLong(1) != tableSize) {
                    return false;
                }
            } catch (SQLException missingTable) {
                return false;
            }
        }
        return true;
    }

    private void create(Connection connection) throws SQLException {
        boolean mySql = Schema.isMySql(connection);
        SplittableRandom random = new SplittableRandom(42); // the same data on every load
        for (int t = 1; t <= tables; t++) {
            String table = "sbtest" + t;
            try (Statement ddl = connection.createStatement()) {
                ddl.execute("DROP TABLE IF EXISTS " + table);
                ddl.execute("CREATE TABLE " + table + " (id INTEGER NOT NULL, k INTEGER NOT NULL DEFAULT 0, "
                        + "c CHAR(120) NOT NULL DEFAULT '', pad CHAR(60) NOT NULL DEFAULT '', PRIMARY KEY (id))"
                        + (mySql ? " ENGINE=InnoDB" : ""));
            }
            load(connection, table, random);
            try (Statement ddl = connection.createStatement()) {
                ddl.execute("CREATE INDEX k_" + t + " ON " + table + " (k)");
                ddl.execute((mySql ? "ANALYZE TABLE " : "ANALYZE ") + table);
            }
        }
    }

    private void load(Connection connection, String table, SplittableRandom random) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO " + table + " (id, k, c, pad) VALUES (?, ?, ?, ?)")) {
            for (int id = 1; id <= tableSize; id++) {
                insert.setInt(1, id);
                insert.setInt(2, 1 + random.nextInt(tableSize));
                insert.setString(3, SysbenchWorkload.cValue(random));
                insert.setString(4, SysbenchWorkload.padValue(random));
                insert.addBatch();
                if (id % BATCH_SIZE == 0 || id == tableSize) {
                    insert.executeBatch();
                    connection.commit();
                }
            }
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }
}
