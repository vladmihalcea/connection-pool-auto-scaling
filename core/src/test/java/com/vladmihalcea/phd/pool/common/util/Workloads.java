package com.vladmihalcea.phd.pool.common.util;

import com.vladmihalcea.phd.pool.workload.OltpWorkload;
import com.vladmihalcea.phd.pool.workload.Schema;
import com.vladmihalcea.phd.pool.workload.sysbench.SysbenchSchema;
import com.vladmihalcea.phd.pool.workload.sysbench.SysbenchWorkload;
import com.vladmihalcea.phd.pool.workload.tpcc.TpccSchema;
import com.vladmihalcea.phd.pool.workload.tpcc.TpccWorkload;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.SplittableRandom;
import java.util.function.LongSupplier;

/**
 * Prepares a workload by name on a database and returns its transaction, for the JMH sweep and the live runs:
 * <ul>
 *   <li>{@code readMostly}, {@code writeHeavy}, {@code longTx}: the three ICASC micro-workloads, on a freshly
 *       created 10,000-account table ({@code longTx} is PostgreSQL-only);</li>
 *   <li>{@code oltpReadWrite}, {@code oltpReadOnly}: the sysbench-derived OLTP mixes, on {@code usl.sysbench.tables}
 *       {@code sbtest} tables of {@code usl.sysbench.tableSize} rows (4 of 100,000 by default), created once and
 *       reused, since the mixes keep the table sizes;</li>
 *   <li>{@code tpcc}: the TPC-C-derived mix, on a freshly loaded schema of {@code tpcc.warehouses} warehouses
 *       (default 2), because TPC-C slows down as its hot rows accumulate versions.</li>
 * </ul>
 *
 * @author Vlad Mihalcea
 */
public final class Workloads {

    public static final String TPCC = "tpcc";

    private static final int SYSBENCH_TABLES = Integer.getInteger("usl.sysbench.tables", 4);
    private static final int SYSBENCH_TABLE_SIZE = Integer.getInteger("usl.sysbench.tableSize", 100_000);
    private static final int TPCC_WAREHOUSES = Integer.getInteger("tpcc.warehouses", 2);

    /** One transaction of a workload. */
    @FunctionalInterface
    public interface Transaction {
        void run(Connection connection, SplittableRandom random) throws SQLException;
    }

    /**
     * A prepared workload.
     *
     * @param name        the workload name
     * @param transaction one transaction
     * @param retries     the transactions restarted after a deadlock or a duplicate key (sysbench-derived only)
     */
    public record Prepared(String name, Transaction transaction, LongSupplier retries) {
    }

    private Workloads() {
    }

    /** Creates or reloads the workload's tables on {@code database} and returns its transaction. */
    public static Prepared prepare(Database database, String name) {
        DataSource admin = database.adminDataSource();
        try {
            if (SysbenchWorkload.isSysbench(name)) {
                SysbenchSchema schema = new SysbenchSchema(SYSBENCH_TABLES, SYSBENCH_TABLE_SIZE);
                schema.ensure(admin);
                SysbenchWorkload sysbench = SysbenchWorkload.byName(name, schema);
                return new Prepared(name, sysbench::runTransaction, sysbench::retries);
            }
            if (TPCC.equals(name)) {
                new TpccSchema(TPCC_WAREHOUSES).create(admin);
                TpccWorkload tpcc = new TpccWorkload(TPCC_WAREHOUSES);
                return new Prepared(name, tpcc::runTransaction, () -> 0);
            }
            Schema schema = new Schema(10);
            schema.create(admin);
            OltpWorkload oltp = OltpWorkload.byName(name, schema.accountCount());
            return new Prepared(name, (connection, random) -> oltp.runTransaction(connection, random::nextInt),
                    () -> 0);
        } finally {
            if (admin instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception ignore) {
                    // best-effort close of the one-off admin pool
                }
            }
        }
    }
}
