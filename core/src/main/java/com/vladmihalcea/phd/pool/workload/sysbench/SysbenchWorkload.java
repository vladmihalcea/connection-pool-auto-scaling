package com.vladmihalcea.phd.pool.workload.sysbench;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.LongAdder;

/**
 * The transactions of sysbench's {@code oltp_read_write} and {@code oltp_read_only} tests, with their
 * default options ({@code oltp_common.lua}): 10 point selects; one simple, one sum, one ordered, and one
 * distinct range of 100 ids; and, for read-write, one index update, one non-index update, and one
 * delete-insert pair, all inside one transaction. Each statement group picks a random table, and ids are
 * drawn uniformly (sysbench's {@code --rand-type=uniform}).
 * <p>
 * Like sysbench, which restarts a transaction that fails with a deadlock or a duplicate key, a transaction
 * that fails with a retryable error is rolled back and run again; {@link #retries()} counts those restarts.
 * Concurrent delete-insert pairs on the same id make them expected under load.
 *
 * @author Vlad Mihalcea
 */
public final class SysbenchWorkload {

    public static final String READ_WRITE = "oltpReadWrite";
    public static final String READ_ONLY = "oltpReadOnly";

    private static final int POINT_SELECTS = 10;
    private static final int RANGE_SIZE = 100;
    private static final int MAX_ATTEMPTS = 10;

    private final String name;
    private final boolean writes;
    private final SysbenchSchema schema;
    private final LongAdder retries = new LongAdder();

    private SysbenchWorkload(String name, boolean writes, SysbenchSchema schema) {
        this.name = name;
        this.writes = writes;
        this.schema = schema;
    }

    public static boolean isSysbench(String name) {
        return READ_WRITE.equals(name) || READ_ONLY.equals(name);
    }

    public static SysbenchWorkload byName(String name, SysbenchSchema schema) {
        return switch (name) {
            case READ_WRITE -> new SysbenchWorkload(name, true, schema);
            case READ_ONLY -> new SysbenchWorkload(name, false, schema);
            default -> throw new IllegalArgumentException("Unknown sysbench workload: " + name);
        };
    }

    public String name() {
        return name;
    }

    /** Transactions restarted after a retryable error. */
    public long retries() {
        return retries.sum();
    }

    /**
     * Executes one transaction. The connection is assumed to be in auto-commit mode on entry and is restored
     * to it on exit.
     *
     * @throws SQLException if the transaction fails with a non-retryable error, or keeps failing
     */
    public void runTransaction(Connection connection, SplittableRandom random) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            for (int attempt = 1; ; attempt++) {
                try {
                    statements(connection, random);
                    connection.commit();
                    return;
                } catch (SQLException e) {
                    safeRollback(connection);
                    if (!isRetryable(e) || attempt == MAX_ATTEMPTS) {
                        throw e;
                    }
                    retries.increment();
                }
            }
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private void statements(Connection connection, SplittableRandom random) throws SQLException {
        String table = table(random);
        try (PreparedStatement select = connection.prepareStatement("SELECT c FROM " + table + " WHERE id = ?")) {
            for (int i = 0; i < POINT_SELECTS; i++) {
                select.setInt(1, id(random));
                readAll(select);
            }
        }
        range(connection, "SELECT c FROM " + table(random) + " WHERE id BETWEEN ? AND ?", random);
        range(connection, "SELECT SUM(k) FROM " + table(random) + " WHERE id BETWEEN ? AND ?", random);
        range(connection, "SELECT c FROM " + table(random) + " WHERE id BETWEEN ? AND ? ORDER BY c", random);
        range(connection, "SELECT DISTINCT c FROM " + table(random) + " WHERE id BETWEEN ? AND ? ORDER BY c",
                random);
        if (!writes) {
            return;
        }
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE " + table(random) + " SET k = k + 1 WHERE id = ?")) {
            update.setInt(1, id(random));
            update.executeUpdate();
        }
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE " + table(random) + " SET c = ? WHERE id = ?")) {
            update.setString(1, cValue(random));
            update.setInt(2, id(random));
            update.executeUpdate();
        }
        String deleteInsertTable = table(random);
        int id = id(random);
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM " + deleteInsertTable + " WHERE id = ?")) {
            delete.setInt(1, id);
            delete.executeUpdate();
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO " + deleteInsertTable + " (id, k, c, pad) VALUES (?, ?, ?, ?)")) {
            insert.setInt(1, id);
            insert.setInt(2, id(random));
            insert.setString(3, cValue(random));
            insert.setString(4, padValue(random));
            insert.executeUpdate();
        }
    }

    private void range(Connection connection, String sql, SplittableRandom random) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(sql)) {
            int from = id(random);
            select.setInt(1, from);
            select.setInt(2, from + RANGE_SIZE - 1);
            readAll(select);
        }
    }

    /** Reads every row, as sysbench does, so the result set crosses the network. */
    private static void readAll(PreparedStatement select) throws SQLException {
        try (ResultSet resultSet = select.executeQuery()) {
            while (resultSet.next()) {
                resultSet.getString(1);
            }
        }
    }

    private String table(SplittableRandom random) {
        return "sbtest" + (1 + random.nextInt(schema.tables()));
    }

    private int id(SplittableRandom random) {
        return 1 + random.nextInt(schema.tableSize());
    }

    /**
     * Deadlocks and serialization failures (SQLState 40001; PostgreSQL 40P01), duplicate keys from concurrent
     * delete-insert pairs (PostgreSQL 23505, MySQL 23000), and MySQL lock wait timeouts (error 1205).
     */
    static boolean isRetryable(SQLException e) {
        String state = e.getSQLState();
        return "40001".equals(state) || "40P01".equals(state) || "23505".equals(state) || "23000".equals(state)
                || e.getErrorCode() == 1205;
    }

    /** sysbench's {@code c} value: ten groups of eleven random digits, separated by dashes. */
    static String cValue(SplittableRandom random) {
        return digitGroups(random, 10);
    }

    /** sysbench's {@code pad} value: five groups of eleven random digits, separated by dashes. */
    static String padValue(SplittableRandom random) {
        return digitGroups(random, 5);
    }

    private static String digitGroups(SplittableRandom random, int groups) {
        StringBuilder value = new StringBuilder(groups * 12);
        for (int g = 0; g < groups; g++) {
            if (g > 0) {
                value.append('-');
            }
            for (int d = 0; d < 11; d++) {
                value.append((char) ('0' + random.nextInt(10)));
            }
        }
        return value.toString();
    }

    private static void safeRollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignore) {
            // best-effort rollback; the original exception decides what happens next
        }
    }

    @Override
    public String toString() {
        return name;
    }
}
