package com.vladmihalcea.phd.pool.common.util;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * PostgreSQL provider for every DB-backed benchmark and experiment. Following the reuse pattern from
 * dto-entity-auto-sync, it prefers a <strong>local PostgreSQL server first</strong> and only starts a
 * Testcontainers instance when no local server is reachable:
 * <ol>
 *   <li>If a local {@code uslpool} database answers on {@code localhost} it is used directly — no Docker
 *       needed. Create it once with {@code CREATE DATABASE uslpool;} on your local server.</li>
 *   <li>Otherwise a JVM-wide singleton {@code postgres:17-alpine} container is started, configured with a
 *       generous {@code max_connections} so pool-size sweeps up to 96 connections never hit the server
 *       cap.</li>
 * </ol>
 * The local URL/credentials can be overridden with the {@code usl.db.url}, {@code usl.db.username} and
 * {@code usl.db.password} system properties; the password also comes from the {@code JDBC_PASSWORD}
 * environment variable, which keeps it off the command line of the server campaign.
 * <p>
 * DB-backed tests must first call {@link #assumeDatabaseAvailable()} so that, on a host without a local
 * server <em>and</em> without Docker, they are skipped rather than failed — the USL math and
 * simulated-pool experiments run without any database.
 *
 * @author Vlad Mihalcea
 */
public final class PostgreSqlSupport {

    private static final Logger LOGGER = LoggerFactory.getLogger(PostgreSqlSupport.class);

    /** Must exceed the largest pool size the sweep visits (96) with head-room for admin connections. */
    public static final int MAX_CONNECTIONS = 500;

    private static final String LOCAL_JDBC_URL =
            System.getProperty("usl.db.url", "jdbc:postgresql://localhost/uslpool");
    private static final String LOCAL_USERNAME = System.getProperty("usl.db.username", "postgres");
    // The password may come from the environment, so that it never appears on a command line (ps, pidstat).
    private static final String LOCAL_PASSWORD = System.getProperty("usl.db.password",
            System.getenv().getOrDefault("JDBC_PASSWORD", "admin"));

    private static volatile Boolean localAvailable;
    private static volatile PostgreSQLContainer<?> container;

    private PostgreSqlSupport() {
    }

    /**
     * Probes (once, memoized) whether the local {@code uslpool} PostgreSQL database is reachable. When it
     * is, {@link #jdbcUrl()} / {@link #username()} / {@link #password()} resolve to the local server and no
     * container is ever started.
     */
    public static synchronized boolean isLocalDatabaseAvailable() {
        if (localAvailable == null) {
            try (Connection ignored =
                         DriverManager.getConnection(LOCAL_JDBC_URL, LOCAL_USERNAME, LOCAL_PASSWORD)) {
                localAvailable = Boolean.TRUE;
                LOGGER.info("Using local PostgreSQL database at {}", LOCAL_JDBC_URL);
            } catch (SQLException e) {
                // An explicitly configured server (e.g. the remote database host of the two-machine testbed)
                // must never be silently replaced by a local container, or a run would measure the wrong database.
                if (System.getProperty("usl.db.url") != null) {
                    throw new IllegalStateException("usl.db.url is set to " + LOCAL_JDBC_URL
                            + ", but the server does not answer; refusing to fall back to Docker", e);
                }
                localAvailable = Boolean.FALSE;
                LOGGER.info("Local PostgreSQL database not reachable at {} ({}) - will fall back to Docker",
                        LOCAL_JDBC_URL, e.getMessage());
            }
        }
        return localAvailable;
    }

    public static synchronized PostgreSQLContainer<?> container() {
        if (container == null) {
            PostgreSQLContainer<?> instance = new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("uslpool")
                    .withUsername("postgres")
                    .withPassword("admin")
                    .withCommand("postgres", "-c", "max_connections=" + MAX_CONNECTIONS)
                    .withReuse(true);
            instance.start();
            container = instance;
            LOGGER.info("Started PostgreSQL Testcontainer at {}", instance.getJdbcUrl());
        }
        return container;
    }

    /**
     * A minimal single-connection administrative data source (used for one-off schema creation), kept
     * separate from the pool under test.
     */
    public static DataSource adminDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl());
        config.setUsername(username());
        config.setPassword(password());
        config.setMaximumPoolSize(2);
        config.setPoolName("admin");
        return new HikariDataSource(config);
    }

    public static String jdbcUrl() {
        return isLocalDatabaseAvailable() ? LOCAL_JDBC_URL : container().getJdbcUrl();
    }

    public static String username() {
        return isLocalDatabaseAvailable() ? LOCAL_USERNAME : container().getUsername();
    }

    public static String password() {
        return isLocalDatabaseAvailable() ? LOCAL_PASSWORD : container().getPassword();
    }

    /**
     * Returns {@code true} when a database is reachable at all — a local {@code uslpool} server, or Docker
     * so a container can be started. Used by experiments that gate an optional DB-backed section.
     */
    public static boolean isDatabaseAvailable() {
        if (isLocalDatabaseAvailable()) {
            return true;
        }
        try {
            return org.testcontainers.DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * JUnit assumption guard: returns normally when a database is reachable (a local {@code uslpool}
     * server first, then Docker so a container can be started), otherwise throws a
     * {@link org.junit.jupiter.api.Assumptions} failure so the test is reported as skipped.
     */
    public static void assumeDatabaseAvailable() {
        org.junit.jupiter.api.Assumptions.assumeTrue(isDatabaseAvailable(),
                "Neither a local PostgreSQL 'uslpool' database nor Docker is available - skipping the test");
    }
}
