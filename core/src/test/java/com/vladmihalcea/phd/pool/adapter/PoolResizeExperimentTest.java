package com.vladmihalcea.phd.pool.adapter;

import com.vladmihalcea.phd.pool.api.PoolUnderTest;
import com.vladmihalcea.phd.pool.common.util.PostgreSqlSupport;
import com.vladmihalcea.phd.pool.workload.Schema;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies the live-resize actuator for all three real pools (HikariCP, DBCP2, Agroal): a pool capped at
 * {@code base} blocks a borrow beyond {@code base}, growing the cap makes {@code base} more connections
 * borrowable at once, and the reported max size tracks the setter. This is the correctness gate behind
 * milestone M2 — every downstream auto-scaling result depends on the actuator doing what
 * {@link PoolUnderTest#setMaxPoolSize(int)} claims.
 * <p>
 * Database-gated: skipped (not failed) when neither a local PostgreSQL server nor Docker is reachable.
 *
 * @author Vlad Mihalcea
 */
public class PoolResizeExperimentTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(PoolResizeExperimentTest.class);

    private static JdbcConfig jdbc;

    @BeforeAll
    public static void setUp() {
        PostgreSqlSupport.assumeDatabaseAvailable();
        new Schema(1).create(PostgreSqlSupport.adminDataSource());
        jdbc = new JdbcConfig(
                PostgreSqlSupport.jdbcUrl(), PostgreSqlSupport.username(), PostgreSqlSupport.password());
    }

    @Test
    public void hikariGrowsAndShrinks() throws Exception {
        assertResizeActuator("hikari");
    }

    @Test
    public void dbcp2GrowsAndShrinks() throws Exception {
        assertResizeActuator("dbcp2");
    }

    @Test
    public void agroalGrowsAndShrinks() throws Exception {
        assertResizeActuator("agroal");
    }

    private void assertResizeActuator(String poolId) throws Exception {
        int base = 2;
        ExecutorService probe = Executors.newSingleThreadExecutor();
        List<Connection> held = new ArrayList<>();
        try (PoolUnderTest pool = PoolFactory.create(poolId, jdbc, base)) {
            assertEquals(base, pool.getMaxPoolSize(), poolId + ": initial size");

            // Saturate the pool by holding all `base` connections open.
            for (int i = 0; i < base; i++) {
                held.add(pool.dataSource().getConnection());
            }

            // A further borrow must block while the pool is exhausted: capacity is exactly `base`.
            CompletableFuture<Connection> beyondCapacity =
                    CompletableFuture.supplyAsync(() -> borrow(pool), probe);
            assertThrows(TimeoutException.class, () -> beyondCapacity.get(1, TimeUnit.SECONDS),
                    poolId + ": borrow beyond capacity should block");
            // Abandon that borrow so it cannot later grab one of the connections we grow into. Pools
            // provision lazily: raising the cap does not retroactively wake an already-blocked waiter
            // (it is only served by a returned connection or the pool's periodic housekeeper), so we
            // verify the grown capacity with fresh borrows rather than by unblocking this one.
            probe.shutdownNow();
            probe.awaitTermination(5, TimeUnit.SECONDS);

            // Growing the pool must make `base` more connections borrowable at once and raise the size.
            pool.setMaxPoolSize(base * 2);
            assertEquals(base * 2, pool.getMaxPoolSize(), poolId + ": size after grow");
            for (int i = 0; i < base; i++) {
                held.add(pool.dataSource().getConnection());
            }
            assertEquals(base * 2, held.size(), poolId + ": grow raised live borrow capacity");

            // Shrinking is reflected in the reported size (in-use connections retire lazily).
            for (Connection c : held) {
                c.close();
            }
            held.clear();
            pool.setMaxPoolSize(base);
            assertEquals(base, pool.getMaxPoolSize(), poolId + ": size after shrink");
            LOGGER.info("Resize actuator verified for {}", poolId);
        } finally {
            for (Connection c : held) {
                try {
                    c.close();
                } catch (Exception ignore) {
                    // best effort
                }
            }
            probe.shutdownNow();
        }
    }

    private static Connection borrow(PoolUnderTest pool) {
        try {
            return pool.dataSource().getConnection();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
