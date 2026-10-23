package com.vladmihalcea.phd.pool.common.util;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.MySQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * MySQL provider for the DB-backed experiments, mirroring {@link PostgreSqlSupport}: a <strong>local MySQL
 * server first</strong>, and a JVM-wide singleton {@code mysql:8.0} Testcontainer only when no local server
 * is reachable.
 * <ol>
 *   <li>If a local {@code uslpool} database answers on {@code localhost} it is used directly. Create it once
 *       with {@code CREATE DATABASE uslpool;}.</li>
 *   <li>Otherwise the container is started with a {@code max_connections} that leaves room for the
 *       largest pool the experiments use.</li>
 * </ol>
 * The local URL/credentials can be overridden with the {@code usl.mysql.url}, {@code usl.mysql.username}
 * and {@code usl.mysql.password} system properties; the password also comes from the {@code MYSQL_PASSWORD}
 * environment variable. The URL enables {@code rewriteBatchedStatements} so the
 * schema loaders insert in multi-row batches.
 *
 * @author Vlad Mihalcea
 */
public final class MySqlSupport {

    private static final Logger LOGGER = LoggerFactory.getLogger(MySqlSupport.class);

    private static final String URL_OPTIONS =
            "useSSL=false&allowPublicKeyRetrieval=true&rewriteBatchedStatements=true";

    private static final String LOCAL_JDBC_URL =
            System.getProperty("usl.mysql.url", "jdbc:mysql://localhost/uslpool?" + URL_OPTIONS);
    private static final String LOCAL_USERNAME = System.getProperty("usl.mysql.username", "mysql");
    // The password may come from the environment, so that it never appears on a command line (ps, pidstat).
    private static final String LOCAL_PASSWORD = System.getProperty("usl.mysql.password",
            System.getenv().getOrDefault("MYSQL_PASSWORD", "admin"));

    private static volatile Boolean localAvailable;
    private static volatile MySQLContainer<?> container;

    private MySqlSupport() {
    }

    /** Probes (once, memoized) whether the local {@code uslpool} MySQL database is reachable. */
    public static synchronized boolean isLocalDatabaseAvailable() {
        if (localAvailable == null) {
            try (Connection ignored =
                         DriverManager.getConnection(LOCAL_JDBC_URL, LOCAL_USERNAME, LOCAL_PASSWORD)) {
                localAvailable = Boolean.TRUE;
                LOGGER.info("Using local MySQL database at {}", LOCAL_JDBC_URL);
            } catch (SQLException e) {
                // An explicitly configured server must never be silently replaced by a local container.
                if (System.getProperty("usl.mysql.url") != null) {
                    throw new IllegalStateException("usl.mysql.url is set to " + LOCAL_JDBC_URL
                            + ", but the server does not answer; refusing to fall back to Docker", e);
                }
                localAvailable = Boolean.FALSE;
                LOGGER.info("Local MySQL database not reachable at {} ({}) - will fall back to Docker",
                        LOCAL_JDBC_URL, e.getMessage());
            }
        }
        return localAvailable;
    }

    public static synchronized MySQLContainer<?> container() {
        if (container == null) {
            MySQLContainer<?> instance = new MySQLContainer<>("mysql:8.0")
                    .withDatabaseName("uslpool")
                    .withUsername("mysql")
                    .withPassword("admin")
                    .withCommand("--max-connections=" + PostgreSqlSupport.MAX_CONNECTIONS)
                    .withUrlParam("useSSL", "false")
                    .withUrlParam("allowPublicKeyRetrieval", "true")
                    .withUrlParam("rewriteBatchedStatements", "true")
                    .withReuse(true);
            instance.start();
            container = instance;
            LOGGER.info("Started MySQL Testcontainer at {}", instance.getJdbcUrl());
        }
        return container;
    }

    /** A minimal administrative data source for one-off schema creation, separate from the pool under test. */
    public static DataSource adminDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl());
        config.setUsername(username());
        config.setPassword(password());
        config.setMaximumPoolSize(2);
        config.setPoolName("admin-mysql");
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

    /** Whether a local {@code uslpool} server is reachable, or Docker so a container can be started. */
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
}
