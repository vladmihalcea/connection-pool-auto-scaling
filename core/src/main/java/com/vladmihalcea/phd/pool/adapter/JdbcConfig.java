package com.vladmihalcea.phd.pool.adapter;

/**
 * Immutable JDBC connection coordinates shared by every {@link com.vladmihalcea.phd.pool.api.PoolUnderTest}
 * adapter, so the same target database can be wrapped by HikariCP, DBCP2 or Agroal identically.
 *
 * @author Vlad Mihalcea
 */
public record JdbcConfig(String url, String username, String password) {
}
