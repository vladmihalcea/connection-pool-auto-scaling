package com.vladmihalcea.phd.pool.common.util;

import com.vladmihalcea.phd.pool.adapter.PoolFactory;
import com.vladmihalcea.phd.pool.api.PoolUnderTest;
import com.vladmihalcea.phd.pool.workload.OltpWorkload;
import com.vladmihalcea.phd.pool.workload.Schema;
import com.vladmihalcea.phd.pool.workload.sysbench.SysbenchSchema;
import com.vladmihalcea.phd.pool.workload.sysbench.SysbenchWorkload;
import com.vladmihalcea.phd.pool.workload.tpcc.TpccSchema;
import com.vladmihalcea.phd.pool.workload.tpcc.TpccWorkload;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
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
 * <p>
 * The live experiments also {@link #settle} the database before every measured run and {@link #warmUp} each
 * workload before its first one. On the laptop (17–19 Nov), the first runs after a change of workload were 30% to 50%
 * slower for 10 to 15 minutes, in the live and the shared-database runs alike, and the first trial of the reservoir
 * starvation block on Hostner had a tenth of the later trials' timeouts: the first runs paid for the maintenance that
 * the previous ones left behind (autovacuum after TPC-C's writes, the hint bits of a fresh load).
 *
 * @author Vlad Mihalcea
 */
public final class Workloads {

    public static final String TPCC = "tpcc";

    private static final int SYSBENCH_TABLES = Integer.getInteger("usl.sysbench.tables", 4);
    private static final int SYSBENCH_TABLE_SIZE = Integer.getInteger("usl.sysbench.tableSize", 100_000);
    private static final int TPCC_WAREHOUSES = Integer.getInteger("tpcc.warehouses", 2);
    private static final Logger LOGGER = LoggerFactory.getLogger(Workloads.class);

    /** The warm-up of {@link #warmUp(Database, Prepared)}: {@code usl.warmupSeconds} (60), 0 to skip it. */
    static final long WARMUP_SECONDS = Long.getLong("usl.warmupSeconds", 60);
    static final int WARMUP_CLIENTS = 64;
    static final int WARMUP_POOL_SIZE = 32;

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

    /**
     * Brings the database to the same clean state before a measured run: on PostgreSQL, a {@code VACUUM (ANALYZE)} of the
     * whole database (the dead row versions of earlier runs, the hint bits and the visibility map of a fresh load, the
     * planner statistics) and a {@code CHECKPOINT}, so neither autovacuum nor a checkpoint catches up during the run.
     * On MySQL it does nothing: InnoDB purges in the background, and its statistics update by themselves. A failure is
     * logged, not thrown, so a missing privilege costs the settling, not the run.
     */
    public static void settle(Database database) {
        if (database != Database.POSTGRESQL) {
            return;
        }
        long start = System.nanoTime();
        DataSource admin = database.adminDataSource();
        try (Connection connection = admin.getConnection(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(true); // VACUUM cannot run inside a transaction block
            statement.execute("VACUUM (ANALYZE)");
            statement.execute("CHECKPOINT");
            LOGGER.info("Settled {} (VACUUM (ANALYZE), CHECKPOINT) in {} ms", database.id(),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        } catch (SQLException e) {
            LOGGER.warn("Could not settle {}: {}", database.id(), e.getMessage());
        } finally {
            close(admin);
        }
    }

    /**
     * Runs {@code workload} for {@link #WARMUP_SECONDS} with {@link #WARMUP_CLIENTS} closed-loop clients on a fixed
     * HikariCP pool of {@link #WARMUP_POOL_SIZE}, and discards what it measured: the caches, the JIT, and the plans
     * warm up before a workload's first measured run. Failed transactions are counted in the log only.
     */
    public static void warmUp(Database database, Prepared workload) throws InterruptedException {
        if (WARMUP_SECONDS <= 0) {
            return;
        }
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(WARMUP_SECONDS);
        LongAdder committed = new LongAdder();
        LongAdder failed = new LongAdder();
        try (PoolUnderTest pool = PoolFactory.create("hikari", database.jdbcConfig(), WARMUP_POOL_SIZE)) {
            List<Thread> clients = new ArrayList<>();
            for (int c = 0; c < WARMUP_CLIENTS; c++) {
                SplittableRandom random = new SplittableRandom(7_919L * c + 1);
                clients.add(Thread.ofPlatform().daemon().name("warmup-" + c).start(() -> {
                    while (System.nanoTime() < end) {
                        try (Connection connection = pool.dataSource().getConnection()) {
                            workload.transaction().run(connection, random);
                            committed.increment();
                        } catch (SQLException e) {
                            failed.increment();
                        }
                    }
                }));
            }
            for (Thread client : clients) {
                client.join();
            }
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            LOGGER.warn("The warm-up of {} on {} failed: {}", workload.name(), database.id(), e.getMessage());
            return;
        }
        LOGGER.info("Warmed up {} on {} for {} s: {} transactions, {} failed", workload.name(), database.id(),
                WARMUP_SECONDS, committed.sum(), failed.sum());
    }

    private static void close(DataSource admin) {
        if (admin instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignore) {
                // best-effort close of the one-off admin pool
            }
        }
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
