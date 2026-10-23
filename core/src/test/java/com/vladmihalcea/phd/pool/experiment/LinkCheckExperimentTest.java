package com.vladmihalcea.phd.pool.experiment;

import com.sun.management.OperatingSystemMXBean;
import com.vladmihalcea.phd.pool.adapter.JdbcConfig;
import com.vladmihalcea.phd.pool.common.util.Database;
import com.vladmihalcea.phd.pool.common.util.MySqlSupport;
import com.vladmihalcea.phd.pool.common.util.PostgreSqlSupport;
import com.vladmihalcea.phd.pool.util.CsvWriter;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testbed check for the two-machine setup, run on the client before every measurement campaign. It measures
 * the round-trip time of a {@code SELECT 1} on one connection, and the ceiling throughput of trivial
 * transactions shaped like the read-mostly workload (a statement and a commit, so two round trips) on a large
 * pool at high concurrency, together with the client's CPU load. The ceiling must sit well above the peak of
 * the workload under test, so that neither the network nor the client bounds the measured USL curve.
 * <p>
 * Only explicitly configured or local servers are checked, never a Docker fallback: point it at the database
 * host with {@code -Dusl.db.url=...} and {@code -Dusl.mysql.url=...}, and tune it with
 * {@code -Dusl.link.pool} (default 64), {@code -Dusl.link.threads} (128) and {@code -Dusl.link.seconds} (10).
 * Results go to {@code metrics/LinkCheck/link-check.csv}.
 *
 * @author Vlad Mihalcea
 */
public class LinkCheckExperimentTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(LinkCheckExperimentTest.class);

    private static final int RTT_WARMUP = 2_000;
    private static final int RTT_SAMPLES = 20_000;
    private static final int CEILING_WARMUP_MILLIS = 3_000;

    private static final int POOL_SIZE = Integer.getInteger("usl.link.pool", 64);
    private static final int THREADS = Integer.getInteger("usl.link.threads", 128);
    private static final int SECONDS = Integer.getInteger("usl.link.seconds", 10);

    private static final List<Object[]> ROWS = new ArrayList<>();

    @ParameterizedTest
    @EnumSource(Database.class)
    public void linkCheck(Database database) throws Exception {
        boolean configured = database == Database.POSTGRESQL
                ? PostgreSqlSupport.isLocalDatabaseAvailable()
                : MySqlSupport.isLocalDatabaseAvailable();
        Assumptions.assumeTrue(configured, "No configured " + database + " server - skipping the link check");

        JdbcConfig jdbc = database.jdbcConfig();
        long[] rttNanos = roundTripNanos(jdbc);
        double[] ceiling = ceiling(jdbc);

        Object[] row = {
                database.id(), server(jdbc.url()),
                micros(percentile(rttNanos, 0.50)), micros(percentile(rttNanos, 0.90)),
                micros(percentile(rttNanos, 0.99)), micros(Arrays.stream(rttNanos).average().orElse(0)),
                String.format(Locale.ROOT, "%.0f", ceiling[0]), POOL_SIZE, THREADS, SECONDS,
                String.format(Locale.ROOT, "%.2f", ceiling[1])
        };
        LOGGER.info("{} at {}: SELECT 1 round trip p50 {} us, p99 {} us; ceiling {} tx/s at client CPU load {}",
                row[0], row[1], row[2], row[4], row[6], row[10]);
        synchronized (ROWS) {
            ROWS.add(row);
        }
        assertTrue(ceiling[0] > 0, "The ceiling run completed no transaction");
    }

    @AfterAll
    public static void writeCsv() {
        if (ROWS.isEmpty()) {
            return;
        }
        try (CsvWriter out = new CsvWriter(CsvWriter.metricsPath("LinkCheck", "link-check.csv"),
                "database", "server", "rtt_p50_us", "rtt_p90_us", "rtt_p99_us", "rtt_mean_us",
                "ceiling_tx_per_s", "pool_size", "threads", "seconds", "client_cpu_load")) {
            for (Object[] row : ROWS) {
                out.writeRow(row);
            }
        }
    }

    /** Sorted round-trip times of a {@code SELECT 1} on one connection, in nanoseconds. */
    private static long[] roundTripNanos(JdbcConfig jdbc) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbc.url(), jdbc.username(), jdbc.password());
             PreparedStatement select = connection.prepareStatement("SELECT 1")) {
            for (int i = 0; i < RTT_WARMUP; i++) {
                selectOne(select);
            }
            long[] samples = new long[RTT_SAMPLES];
            for (int i = 0; i < RTT_SAMPLES; i++) {
                long start = System.nanoTime();
                selectOne(select);
                samples[i] = System.nanoTime() - start;
            }
            Arrays.sort(samples);
            return samples;
        }
    }

    /**
     * Runs read-mostly-shaped trivial transactions on a full pool and returns the transactions per second
     * and the mean CPU load of the client machine over the measured seconds.
     */
    private static double[] ceiling(JdbcConfig jdbc) throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbc.url());
        config.setUsername(jdbc.username());
        config.setPassword(jdbc.password());
        config.setMaximumPoolSize(POOL_SIZE);
        config.setMinimumIdle(POOL_SIZE);
        config.setPoolName("link-check");
        try (HikariDataSource dataSource = new HikariDataSource(config)) {
            prefill(dataSource);
            AtomicBoolean running = new AtomicBoolean(true);
            AtomicBoolean measuring = new AtomicBoolean(false);
            LongAdder transactions = new LongAdder();
            ExecutorService executor = Executors.newFixedThreadPool(THREADS);
            List<Future<?>> clients = new ArrayList<>(THREADS);
            for (int t = 0; t < THREADS; t++) {
                clients.add(executor.submit(() -> {
                    while (running.get()) {
                        try (Connection connection = dataSource.getConnection()) {
                            connection.setAutoCommit(false);
                            try (PreparedStatement select = connection.prepareStatement("SELECT 1")) {
                                selectOne(select);
                            }
                            connection.commit();
                            connection.setAutoCommit(true);
                        }
                        if (measuring.get()) {
                            transactions.increment();
                        }
                    }
                    return null;
                }));
            }
            OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            double cpuLoad = 0;
            long elapsed;
            try {
                Thread.sleep(CEILING_WARMUP_MILLIS);
                os.getCpuLoad(); // the first reading only starts the measurement interval
                measuring.set(true);
                long start = System.nanoTime();
                for (int second = 0; second < SECONDS; second++) {
                    Thread.sleep(1_000);
                    cpuLoad += os.getCpuLoad();
                }
                measuring.set(false);
                elapsed = System.nanoTime() - start;
            } finally {
                running.set(false);
                executor.shutdown();
            }
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS), "Ceiling clients did not stop");
            for (Future<?> client : clients) {
                client.get(); // surfaces any client failure
            }
            return new double[]{transactions.sum() / (elapsed / 1e9), cpuLoad / SECONDS};
        }
    }

    /** Holds every connection at once, so the pool has opened all of them before the measurement. */
    private static void prefill(HikariDataSource dataSource) throws SQLException {
        List<Connection> held = new ArrayList<>(POOL_SIZE);
        try {
            for (int i = 0; i < POOL_SIZE; i++) {
                held.add(dataSource.getConnection());
            }
        } finally {
            for (Connection connection : held) {
                connection.close();
            }
        }
    }

    private static void selectOne(PreparedStatement select) throws SQLException {
        try (ResultSet resultSet = select.executeQuery()) {
            resultSet.next();
        }
    }

    private static long percentile(long[] sorted, double quantile) {
        return sorted[Math.max(0, (int) Math.ceil(quantile * sorted.length) - 1)];
    }

    private static String micros(double nanos) {
        return String.format(Locale.ROOT, "%.1f", nanos / 1_000.0);
    }

    /** The host and port of a JDBC URL, for the CSV. */
    private static String server(String url) {
        return url.replaceFirst("^jdbc:[a-z]+://", "").replaceFirst("[/?].*$", "");
    }
}
