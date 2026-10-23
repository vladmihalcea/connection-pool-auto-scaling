package com.vladmihalcea.phd.pool.flexypool;

import com.zaxxer.hikari.HikariDataSource;
import org.apache.commons.dbcp2.BasicDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Closes the physical connections a pool keeps above a lowered maximum size, one returned connection at
 * a time, removing each from the pool synchronously so that {@link #openConnections()} drops at once. Lowering the cap only stops a
 * pool from opening new connections; whether it also gives back the connections above the cap depends on
 * the pool:
 * <ul>
 *   <li><b>HikariCP</b> retires a connection above the cap only once it sits idle, which never happens
 *       while the pool is saturated. Evicting the connection while it is still borrowed
 *       ({@link HikariDataSource#evictConnection}) removes it from the pool at once; the following
 *       {@code close()} then only releases the proxy. (Evicting after {@code close()} would race with the
 *       hand-off of the returned connection to a waiting thread.)</li>
 *   <li><b>DBCP2</b> keeps returned connections as long as fewer than {@code maxIdle} are idle, so a
 *       saturated pool never shrinks either. Invalidating the connection before it is returned
 *       ({@link BasicDataSource#invalidateConnection}) destroys it and frees its slot.</li>
 *   <li><b>Agroal</b> already closes a returned connection when the pool holds more than {@code maxSize},
 *       so it needs no retirer ({@link #forDataSource} returns {@code null}).</li>
 * </ul>
 *
 * @author Vlad Mihalcea
 */
public interface SurplusConnectionRetirer {

    /** The number of physical connections the pool holds (busy and idle), or -1 if unknown. */
    int openConnections();

    /** Returns the borrowed connection to the pool and closes its physical connection. */
    void closeAndRetire(Connection connection) throws SQLException;

    /**
     * The retirer for the given pool, or {@code null} when the pool shrinks on its own (Agroal) or is not
     * supported.
     */
    static SurplusConnectionRetirer forDataSource(DataSource dataSource) {
        if (dataSource instanceof HikariDataSource hikari) {
            return new SurplusConnectionRetirer() {
                @Override
                public int openConnections() {
                    return hikari.getHikariPoolMXBean() == null ? -1
                            : hikari.getHikariPoolMXBean().getTotalConnections();
                }

                @Override
                public void closeAndRetire(Connection connection) throws SQLException {
                    // The caller is done with the connection, so it can be evicted while still borrowed;
                    // HikariCP's close() tolerates the evicted connection and skips the recycle.
                    hikari.evictConnection(connection);
                    connection.close();
                }
            };
        }
        if (dataSource instanceof BasicDataSource dbcp2) {
            return new SurplusConnectionRetirer() {
                @Override
                public int openConnections() {
                    return dbcp2.getNumActive() + dbcp2.getNumIdle();
                }

                @Override
                public void closeAndRetire(Connection connection) throws SQLException {
                    // DBCP2 can only invalidate a connection that is still borrowed; closing it afterwards
                    // is a no-op on the already-destroyed connection.
                    dbcp2.invalidateConnection(connection);
                    connection.close();
                }
            };
        }
        // Agroal shrinks on its own; other pools are not supported.
        return null;
    }
}
