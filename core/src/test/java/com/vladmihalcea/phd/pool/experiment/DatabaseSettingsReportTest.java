package com.vladmihalcea.phd.pool.experiment;

import com.vladmihalcea.phd.pool.adapter.JdbcConfig;
import com.vladmihalcea.phd.pool.common.util.Database;
import com.vladmihalcea.phd.pool.common.util.MySqlSupport;
import com.vladmihalcea.phd.pool.common.util.PostgreSqlSupport;
import com.vladmihalcea.phd.pool.util.CsvWriter;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Records the configuration of every configured database server, so that each results folder of the server
 * campaign says which server version and settings it measured: for PostgreSQL, {@code version()} and every
 * setting that is not at its built-in default ({@code pg_settings}); for MySQL, {@code VERSION()} and all global
 * variables (the default and the tuned profiles of {@code setup-mysql.sh} differ in a few of them). Writes
 * {@code metrics/DatabaseSettings/<database>.csv}. A database without a configured server is skipped.
 *
 * @author Vlad Mihalcea
 */
public class DatabaseSettingsReportTest {

    @ParameterizedTest
    @EnumSource(Database.class)
    public void recordSettings(Database database) throws Exception {
        boolean configured = database == Database.POSTGRESQL
                ? PostgreSqlSupport.isLocalDatabaseAvailable()
                : MySqlSupport.isLocalDatabaseAvailable();
        Assumptions.assumeTrue(configured, "No configured " + database + " server");
        JdbcConfig jdbc = database.jdbcConfig();
        try (Connection connection = DriverManager.getConnection(jdbc.url(), jdbc.username(), jdbc.password());
             Statement statement = connection.createStatement();
             CsvWriter out = new CsvWriter(CsvWriter.metricsPath("DatabaseSettings", database.id() + ".csv"),
                     "name", "setting", "unit", "source")) {
            String versionQuery = database == Database.POSTGRESQL ? "SELECT version()" : "SELECT VERSION()";
            try (ResultSet rs = statement.executeQuery(versionQuery)) {
                rs.next();
                out.writeRow("version", rs.getString(1).replace(',', ';'), "", "server");
            }
            if (database == Database.POSTGRESQL) {
                try (ResultSet rs = statement.executeQuery("SELECT name, setting, coalesce(unit, ''), source "
                        + "FROM pg_settings WHERE source NOT IN ('default', 'override') ORDER BY name")) {
                    while (rs.next()) {
                        out.writeRow(rs.getString(1), rs.getString(2).replace(',', ';'), rs.getString(3),
                                rs.getString(4));
                    }
                }
            } else {
                try (ResultSet rs = statement.executeQuery("SHOW GLOBAL VARIABLES")) {
                    while (rs.next()) {
                        out.writeRow(rs.getString(1), String.valueOf(rs.getString(2)).replace(',', ';')
                                .replace('\n', ' '), "", "global");
                    }
                }
            }
        }
    }
}
