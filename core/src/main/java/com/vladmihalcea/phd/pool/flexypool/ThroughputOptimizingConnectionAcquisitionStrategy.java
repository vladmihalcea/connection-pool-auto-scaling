package com.vladmihalcea.phd.pool.flexypool;

import com.vladmihalcea.flexypool.adaptor.PoolAdapter;
import com.vladmihalcea.flexypool.common.ConfigurationProperties;
import com.vladmihalcea.flexypool.connection.ConnectionRequestContext;
import com.vladmihalcea.flexypool.exception.ConnectionAcquisitionTimeoutException;
import com.vladmihalcea.flexypool.metric.Metrics;
import com.vladmihalcea.flexypool.strategy.AbstractConnectionAcquisitionStrategy;
import com.vladmihalcea.flexypool.strategy.ConnectionAcquisitionStrategy;
import com.vladmihalcea.flexypool.strategy.ConnectionAcquisitionStrategyFactory;
import com.vladmihalcea.flexypool.strategy.IncrementPoolOnTimeoutConnectionAcquisitionStrategy;
import com.vladmihalcea.flexypool.strategy.RetryConnectionAcquisitionStrategy;
import com.vladmihalcea.phd.pool.controller.ThroughputProbePolicy;
import com.vladmihalcea.phd.pool.controller.WindowSample;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A FlexyPool {@link ConnectionAcquisitionStrategy} that drives the pool toward the size that
 * <em>maximises throughput</em>, built entirely on FlexyPool's two existing dynamic-sizing strategies:
 *
 * <ul>
 *   <li>{@link IncrementPoolOnTimeoutConnectionAcquisitionStrategy} grows the pool one connection at a
 *       time beyond its initial maximum whenever an acquisition times out — the exploration force that
 *       lets larger sizes be probed under load;</li>
 *   <li>{@link RetryConnectionAcquisitionStrategy} re-attempts acquisition immediately (no back-off), so a
 *       just-grown pool is used at once and a transient burst does not surface as a failure.</li>
 * </ul>
 *
 * On its own the increment strategy is grow-only and monotonic: it chases "no timeouts", which drives the
 * pool toward the client-thread count and overshoots the throughput optimum {@code N*} (past which the
 * database's own contention and coherency costs — the USL retrograde region — make extra connections
 * <em>reduce</em> throughput). This strategy adds the missing feedback: over fixed observation windows it
 * measures the achieved throughput and asks a model-free {@link ThroughputProbePolicy} where the peak is,
 * then sets the pool's maximum size to that peak — capping (and, after a workload change, shrinking) the
 * pool at the throughput optimum rather than growing without bound.
 *
 * <p>FlexyPool's strategy SPI is acquisition-driven and has no scheduler, so the window evaluation runs
 * lazily inside {@link #getConnection}: the first thread to cross a window boundary recomputes throughput
 * and re-targets the size while the others proceed unblocked.
 *
 * @author Vlad Mihalcea
 */
public final class ThroughputOptimizingConnectionAcquisitionStrategy<T extends DataSource>
        extends AbstractConnectionAcquisitionStrategy {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(ThroughputOptimizingConnectionAcquisitionStrategy.class);

    /** Tunable knobs. {@code maxOvergrowPoolSize} is both the increment ceiling and the search ceiling. */
    public record Config(
            int minPoolSize,
            int maxOvergrowPoolSize,
            int incrementTimeoutMillis,
            int retryAttempts,
            long windowMillis,
            int probeStep) {

        public static Config defaults(int minPoolSize, int maxOvergrowPoolSize) {
            // Integer.MAX_VALUE: let the increment strategy grow only on a genuine acquisition timeout
            // (pool exhaustion), not on a merely-slow-but-successful acquire — the throughput policy owns
            // the exploratory sizing, so increment is only transient headroom while the pool is saturated.
            return new Config(minPoolSize, maxOvergrowPoolSize, Integer.MAX_VALUE, 2, 1_000L, 4);
        }
    }

    /**
     * Creates the strategy for a given {@link ConfigurationProperties}, mirroring the {@code Factory}
     * pattern of the built-in FlexyPool strategies so it can be passed to a {@code FlexyPoolDataSource}.
     */
    public static class Factory<T extends DataSource>
            implements ConnectionAcquisitionStrategyFactory<ThroughputOptimizingConnectionAcquisitionStrategy, T> {

        private final Config config;

        public Factory(Config config) {
            this.config = config;
        }

        public Factory(int minPoolSize, int maxOvergrowPoolSize) {
            this(Config.defaults(minPoolSize, maxOvergrowPoolSize));
        }

        @Override
        @SuppressWarnings("unchecked")
        public ThroughputOptimizingConnectionAcquisitionStrategy newInstance(
                ConfigurationProperties<T, Metrics, PoolAdapter<T>> configurationProperties) {
            // Build the two reused strategies here, where the exact ConfigurationProperties type their
            // factories require is in scope, then hand them to the strategy.
            ConnectionAcquisitionStrategy increment =
                    new IncrementPoolOnTimeoutConnectionAcquisitionStrategy.Factory<T>(
                            config.maxOvergrowPoolSize(), config.incrementTimeoutMillis())
                            .newInstance(configurationProperties);
            ConnectionAcquisitionStrategy retry =
                    new RetryConnectionAcquisitionStrategy.Factory<T>(config.retryAttempts())
                            .newInstance(configurationProperties);
            return new ThroughputOptimizingConnectionAcquisitionStrategy(
                    configurationProperties, config, increment, retry);
        }
    }

    private final Config config;
    private final PoolAdapter poolAdapter;
    private final ConnectionAcquisitionStrategy increment;
    private final ConnectionAcquisitionStrategy retry;
    private final ThroughputProbePolicy sizingPolicy;

    private final AtomicLong acquisitionsInWindow = new AtomicLong();
    private final AtomicLong windowStartNanos = new AtomicLong(System.nanoTime());
    private final ReentrantLock evaluationLock = new ReentrantLock();

    private ThroughputOptimizingConnectionAcquisitionStrategy(
            ConfigurationProperties<? extends DataSource, Metrics, PoolAdapter> configurationProperties,
            Config config, ConnectionAcquisitionStrategy increment, ConnectionAcquisitionStrategy retry) {
        super(configurationProperties);
        this.config = config;
        this.poolAdapter = configurationProperties.getPoolAdapter();
        this.increment = increment;
        this.retry = retry;
        this.sizingPolicy = new ThroughputProbePolicy(
                new ThroughputProbePolicy.Config(config.minPoolSize(), config.maxOvergrowPoolSize(),
                        config.probeStep(), 0.5, 0.03, 0.25));
    }

    @Override
    public Connection getConnection(ConnectionRequestContext requestContext) throws SQLException {
        Connection connection;
        if (sizingPolicy.isSettled()) {
            // Optimum found: hold the pool at it. Retry (immediate, no back-off) absorbs a transient burst
            // without growing the pool past the throughput peak.
            connection = retry.getConnection(requestContext);
        } else {
            try {
                // Exploring: increment grows the pool on a genuine timeout while larger sizes are probed.
                connection = increment.getConnection(requestContext);
            } catch (ConnectionAcquisitionTimeoutException e) {
                connection = retry.getConnection(requestContext);
            }
        }
        acquisitionsInWindow.incrementAndGet();
        maybeRetargetPoolSize();
        return connection;
    }

    /**
     * Once per {@code windowMillis}, turn the acquisitions counted in the window into a throughput and let
     * the {@link ThroughputProbePolicy} re-target the pool's maximum size toward the throughput optimum.
     */
    private void maybeRetargetPoolSize() {
        long now = System.nanoTime();
        long windowNanos = config.windowMillis() * 1_000_000L;
        if (now - windowStartNanos.get() < windowNanos) {
            return;
        }
        if (!evaluationLock.tryLock()) {
            return; // another thread is already retargeting this window
        }
        try {
            long elapsedNanos = now - windowStartNanos.get();
            if (elapsedNanos < windowNanos) {
                return;
            }
            long acquisitions = acquisitionsInWindow.getAndSet(0);
            windowStartNanos.set(now);

            double throughput = acquisitions / (elapsedNanos / 1e9);
            int currentMaxSize = poolAdapter.getMaxPoolSize();
            int targetSize = sizingPolicy.decide(new WindowSample(currentMaxSize, throughput, 0, 0));
            if (targetSize != currentMaxSize) {
                poolAdapter.setMaxPoolSize(targetSize);
                LOGGER.info("Retargeted pool size {} -> {} (throughput {} tx/s, best size {})",
                        currentMaxSize, targetSize, String.format("%.0f", throughput), sizingPolicy.bestSize());
            }
        } finally {
            evaluationLock.unlock();
        }
    }

    /** The throughput-maximising size discovered so far, or -1 before the first window elapses. */
    public int bestSize() {
        return sizingPolicy.bestSize();
    }

    @Override
    public String toString() {
        return "ThroughputOptimizingConnectionAcquisitionStrategy{" +
                "minPoolSize=" + config.minPoolSize() +
                ", maxOvergrowPoolSize=" + config.maxOvergrowPoolSize() +
                '}';
    }
}
