package com.vladmihalcea.phd.pool.workload;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.locks.LockSupport;

/**
 * A single OLTP transaction against the {@link Schema} {@code accounts} table, parameterised by a
 * read/write mix and an optional in-transaction service delay. This is the unit of work executed in a
 * closed loop by the throughput sweep (E1) and the auto-scaling experiments (E4/E5).
 * <p>
 * Three named profiles are used across the paper:
 * <ul>
 *   <li>{@code readMostly} — 90% point-select, 10% balance update; short service time.</li>
 *   <li>{@code writeHeavy} — 50/50; more lock contention on hot rows.</li>
 *   <li>{@code longTx} — read-mostly plus a fixed {@code serviceDelayMicros} spin inside the
 *       transaction, lengthening service time so the USL optimum {@code N*} shifts to a larger pool.</li>
 * </ul>
 * Determinism: the caller supplies a seeded {@link java.util.random.RandomGenerator}; the workload never
 * consults a global RNG, so a run is reproducible from its seed.
 *
 * @author Vlad Mihalcea
 */
public final class OltpWorkload {

    private final String name;
    private final double writeRatio;
    private final int serviceDelayMicros;
    private final int accountCount;

    private OltpWorkload(String name, double writeRatio, int serviceDelayMicros, int accountCount) {
        this.name = name;
        this.writeRatio = writeRatio;
        this.serviceDelayMicros = serviceDelayMicros;
        this.accountCount = accountCount;
    }

    public static OltpWorkload readMostly(int accountCount) {
        return new OltpWorkload("readMostly", 0.10, 0, accountCount);
    }

    public static OltpWorkload writeHeavy(int accountCount) {
        return new OltpWorkload("writeHeavy", 0.50, 0, accountCount);
    }

    /** Read-mostly with an added in-transaction service delay (microseconds) to lengthen service time. */
    public static OltpWorkload longTx(int accountCount, int serviceDelayMicros) {
        return new OltpWorkload("longTx", 0.10, serviceDelayMicros, accountCount);
    }

    public static OltpWorkload byName(String name, int accountCount) {
        return switch (name) {
            case "readMostly" -> readMostly(accountCount);
            case "writeHeavy" -> writeHeavy(accountCount);
            case "longTx" -> longTx(accountCount, 5_000);
            default -> throw new IllegalArgumentException("Unknown workload: " + name);
        };
    }

    public String name() {
        return name;
    }

    /**
     * Executes exactly one transaction (BEGIN … COMMIT) on the given connection. The connection is
     * assumed to be in auto-commit mode on entry and is restored to it on exit.
     *
     * @param connection a borrowed JDBC connection
     * @param nextInt    a bounded random supplier (e.g. {@code rng::nextInt}) for row and coin choices
     * @throws SQLException if the transaction fails (the caller decides whether to retry)
     */
    public void runTransaction(Connection connection, java.util.function.IntUnaryOperator nextInt)
            throws SQLException {
        int accountId = 1 + nextInt.applyAsInt(accountCount);
        boolean write = (nextInt.applyAsInt(1_000_000) / 1_000_000.0) < writeRatio;

        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            if (write) {
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE accounts SET balance = balance + 1 WHERE id = ?")) {
                    update.setLong(1, accountId);
                    update.executeUpdate();
                }
            } else {
                try (PreparedStatement select = connection.prepareStatement(
                        "SELECT balance FROM accounts WHERE id = ?")) {
                    select.setLong(1, accountId);
                    try (ResultSet rs = select.executeQuery()) {
                        if (rs.next()) {
                            rs.getLong(1);
                        }
                    }
                }
            }
            if (serviceDelayMicros > 0) {
                LockSupport.parkNanos(serviceDelayMicros * 1_000L);
            }
            connection.commit();
        } catch (SQLException e) {
            safeRollback(connection);
            throw e;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private static void safeRollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignore) {
            // best-effort rollback; the original exception is propagated
        }
    }
}
