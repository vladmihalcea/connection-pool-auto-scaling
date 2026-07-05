package com.vladmihalcea.phd.pool.workload;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * A single OLTP transaction against the {@link Schema} {@code accounts} table, parameterised by a
 * read/write mix and an optional in-transaction service delay. This is the unit of work executed in a
 * closed loop by the throughput sweep (E1) and the auto-scaling experiments (E4/E5).
 * <p>
 * Three named profiles are used across the paper:
 * <ul>
 *   <li>{@code readMostly} — 90% point-select, 10% balance update; short service time.</li>
 *   <li>{@code writeHeavy} — 50/50; more lock contention on hot rows.</li>
 *   <li>{@code longTx} — read-mostly plus a fixed slug of server-side CPU work ({@code cpuWorkRows}
 *       rows of {@code sum(sqrt(...))} over a {@code generate_series}) inside the transaction. This
 *       both lengthens the service time and makes concurrent transactions contend for the database
 *       server's CPU cores, so the throughput curve saturates near the core count and then turns over —
 *       a genuine USL retrograde region. (A sleep would not: independent waits never contend, and it is
 *       quantised to the OS timer tick — ~15.6 ms on Windows — so the curve would be a flat, unfittable
 *       line.)</li>
 * </ul>
 * Determinism: the caller supplies a seeded {@link java.util.random.RandomGenerator}; the workload never
 * consults a global RNG, so a run is reproducible from its seed.
 *
 * @author Vlad Mihalcea
 */
public final class OltpWorkload {

    /**
     * Rows of server-side CPU work per {@code longTx} transaction. ~3.5 ms on a modern core; the exact
     * wall-clock time is host-dependent, but the work (rows scanned) is fixed, so a run is reproducible.
     */
    private static final int LONG_TX_CPU_ROWS = 50_000;

    private final String name;
    private final double writeRatio;
    private final int cpuWorkRows;
    private final int accountCount;

    private OltpWorkload(String name, double writeRatio, int cpuWorkRows, int accountCount) {
        this.name = name;
        this.writeRatio = writeRatio;
        this.cpuWorkRows = cpuWorkRows;
        this.accountCount = accountCount;
    }

    public static OltpWorkload readMostly(int accountCount) {
        return new OltpWorkload("readMostly", 0.10, 0, accountCount);
    }

    public static OltpWorkload writeHeavy(int accountCount) {
        return new OltpWorkload("writeHeavy", 0.50, 0, accountCount);
    }

    /** Read-mostly plus {@code cpuWorkRows} rows of server-side CPU work to lengthen service time. */
    public static OltpWorkload longTx(int accountCount, int cpuWorkRows) {
        return new OltpWorkload("longTx", 0.10, cpuWorkRows, accountCount);
    }

    public static OltpWorkload byName(String name, int accountCount) {
        return switch (name) {
            case "readMostly" -> readMostly(accountCount);
            case "writeHeavy" -> writeHeavy(accountCount);
            case "longTx" -> longTx(accountCount, LONG_TX_CPU_ROWS);
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
            if (cpuWorkRows > 0) {
                // Real server-side CPU work, not a sleep: sum(sqrt(...)) over a generate_series burns CPU
                // proportional to cpuWorkRows, so concurrent long transactions compete for the database
                // server's cores and the throughput curve saturates and turns over (a fittable USL
                // optimum). A sleep instead would be timer-quantised and never contend, giving a flat line.
                try (PreparedStatement compute = connection.prepareStatement(
                        "SELECT sum(sqrt(g.i::float8)) FROM generate_series(1, ?) AS g(i)")) {
                    compute.setInt(1, cpuWorkRows);
                    try (ResultSet rs = compute.executeQuery()) {
                        rs.next();
                    }
                }
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
