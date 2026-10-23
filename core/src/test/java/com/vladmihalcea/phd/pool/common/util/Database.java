package com.vladmihalcea.phd.pool.common.util;

import com.vladmihalcea.phd.pool.adapter.JdbcConfig;
import org.junit.jupiter.api.Assumptions;

import javax.sql.DataSource;

/**
 * The databases the DB-backed experiments run against, so one parameterized test covers both. Each
 * constant resolves its coordinates through {@link PostgreSqlSupport} or {@link MySqlSupport} (local server
 * first, Testcontainers otherwise).
 *
 * @author Vlad Mihalcea
 */
public enum Database {

    POSTGRESQL {
        @Override
        public JdbcConfig jdbcConfig() {
            return new JdbcConfig(PostgreSqlSupport.jdbcUrl(), PostgreSqlSupport.username(),
                    PostgreSqlSupport.password());
        }

        @Override
        public DataSource adminDataSource() {
            return PostgreSqlSupport.adminDataSource();
        }

        @Override
        public boolean isAvailable() {
            return PostgreSqlSupport.isDatabaseAvailable();
        }
    },

    MYSQL {
        @Override
        public JdbcConfig jdbcConfig() {
            return new JdbcConfig(MySqlSupport.jdbcUrl(), MySqlSupport.username(), MySqlSupport.password());
        }

        @Override
        public DataSource adminDataSource() {
            return MySqlSupport.adminDataSource();
        }

        @Override
        public boolean isAvailable() {
            return MySqlSupport.isDatabaseAvailable();
        }
    };

    public abstract JdbcConfig jdbcConfig();

    public abstract DataSource adminDataSource();

    public abstract boolean isAvailable();

    /** Lower-case id used in CSV rows and log lines. */
    public String id() {
        return name().toLowerCase();
    }

    /** JUnit assumption guard: skips (rather than fails) the test when this database is unreachable. */
    public void assumeAvailable() {
        Assumptions.assumeTrue(isAvailable(), "No " + name() + " database (local or Docker) - skipping");
    }
}
