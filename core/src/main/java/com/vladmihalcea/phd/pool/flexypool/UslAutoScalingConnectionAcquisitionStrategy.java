package com.vladmihalcea.phd.pool.flexypool;

import com.vladmihalcea.flexypool.adaptor.PoolAdapter;
import com.vladmihalcea.flexypool.common.ConfigurationProperties;
import com.vladmihalcea.flexypool.connection.ConnectionRequestContext;
import com.vladmihalcea.flexypool.lifecycle.LifeCycleCallback;
import com.vladmihalcea.flexypool.metric.Metrics;
import com.vladmihalcea.flexypool.strategy.AbstractConnectionAcquisitionStrategy;
import com.vladmihalcea.flexypool.strategy.ConnectionAcquisitionStrategy;
import com.vladmihalcea.flexypool.strategy.ConnectionAcquisitionStrategyFactory;
import com.vladmihalcea.flexypool.strategy.RetryConnectionAcquisitionStrategy;
import com.vladmihalcea.phd.pool.controller.UslAutoScalingController;
import com.vladmihalcea.phd.pool.controller.WindowSample;
import com.vladmihalcea.phd.pool.usl.UslFit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.Closeable;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The paper's {@link UslAutoScalingController} packaged as a real FlexyPool
 * {@link ConnectionAcquisitionStrategy} — the upstream artifact called for in the plan (§5). It is the
 * <em>model-driven</em> sibling of {@link ThroughputOptimizingConnectionAcquisitionStrategy}: where that
 * one hill-climbs on measured throughput (model-free), this one fits Gunther's Universal Scalability Law
 * online and steers the pool toward the closed-form optimum {@code N* = floor(sqrt((1-sigma)/kappa))}.
 *
 * <p>FlexyPool's strategy SPI is <em>acquisition-driven</em> — a strategy only gets a say when a
 * connection is requested, and there is no scheduler behind it. The USL controller, however, needs a
 * fixed-cadence estimation tick (observe a window's throughput, refit, re-target the size). This class
 * therefore plays both roles the plan describes:
 * <ul>
 *   <li><b>observability</b>: {@link #getConnection} simply counts acquisitions (delegating to a
 *       {@link RetryConnectionAcquisitionStrategy} so a resize in flight never surfaces as a failure), and</li>
 *   <li><b>estimation tick</b>: a companion {@link ScheduledExecutorService} fires every
 *       {@code windowMillis}, turns the acquisition count into a throughput, and hands
 *       {@code (currentMaxSize, throughput)} to the controller, whose returned target size is applied to
 *       the live pool through the FlexyPool {@link PoolAdapter}.</li>
 * </ul>
 *
 * <p>Unlike the model-free strategy this one needs no {@code IncrementPoolOnTimeout} exploration force:
 * the controller <em>itself</em> explores, walking a probe ladder across {@code [minPoolSize, maxPoolSize]}
 * during its bootstrap so the first fit spans both sides of the curve, then tracking the re-estimated
 * {@code N*} with a dead-band and a saturated per-window step. Retry is kept only as transient headroom
 * while a resize settles.
 *
 * <p>The scheduler runs on a single daemon thread and is stopped by {@link #close()} (or the FlexyPool
 * {@link LifeCycleCallback#stop()}); it is a daemon so a caller that forgets to close never blocks JVM
 * exit.
 *
 * @author Vlad Mihalcea
 */
public final class UslAutoScalingConnectionAcquisitionStrategy<T extends DataSource>
        extends AbstractConnectionAcquisitionStrategy implements LifeCycleCallback, Closeable {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(UslAutoScalingConnectionAcquisitionStrategy.class);

    /**
     * Tunable knobs. {@code windowMillis} is the estimation cadence (the plan's {@code W}); the rest are
     * forwarded to the {@link UslAutoScalingController}. {@code maxPoolSize} is both the exploration ceiling
     * and the clamp on {@code N*}.
     */
    public record Config(
            int minPoolSize,
            int maxPoolSize,
            long windowMillis,
            int retryAttempts,
            UslAutoScalingController.Config controllerConfig) {

        public static Config defaults(int minPoolSize, int maxPoolSize) {
            // 1 s window (the plan uses W = 5 s in production; 1 s keeps the experiments quick), 2 retries.
            return new Config(minPoolSize, maxPoolSize, 1_000L, 2,
                    UslAutoScalingController.Config.defaults(minPoolSize, maxPoolSize));
        }
    }

    /**
     * Mirrors the {@code Factory} pattern of the built-in FlexyPool strategies so the strategy can be
     * handed to a {@code FlexyPoolDataSource}. It also remembers the instance it created so a test (or an
     * embedding application) can reach the strategy to inspect its estimate or {@link #close()} its
     * scheduler — FlexyPool does not propagate its own lifecycle to acquisition strategies.
     */
    public static class Factory<T extends DataSource>
            implements ConnectionAcquisitionStrategyFactory<UslAutoScalingConnectionAcquisitionStrategy, T> {

        private final Config config;
        private volatile UslAutoScalingConnectionAcquisitionStrategy<T> lastInstance;

        public Factory(Config config) {
            this.config = config;
        }

        public Factory(int minPoolSize, int maxPoolSize) {
            this(Config.defaults(minPoolSize, maxPoolSize));
        }

        @Override
        @SuppressWarnings("unchecked")
        public UslAutoScalingConnectionAcquisitionStrategy newInstance(
                ConfigurationProperties<T, Metrics, PoolAdapter<T>> configurationProperties) {
            ConnectionAcquisitionStrategy retry =
                    new RetryConnectionAcquisitionStrategy.Factory<T>(config.retryAttempts())
                            .newInstance(configurationProperties);
            // Raw constructor call (as the built-in FlexyPool strategy factories do) so the incoming
            // ConfigurationProperties<T, Metrics, PoolAdapter<T>> is accepted by the strategy's
            // wildcard-typed constructor; the result is the strategy parameterised on T.
            UslAutoScalingConnectionAcquisitionStrategy<T> instance =
                    new UslAutoScalingConnectionAcquisitionStrategy(configurationProperties, config, retry);
            lastInstance = instance;
            return instance;
        }

        /** The most recently created strategy, or {@code null} if {@link #newInstance} has not run yet. */
        public UslAutoScalingConnectionAcquisitionStrategy<T> strategy() {
            return lastInstance;
        }
    }

    private final Config config;
    private final PoolAdapter poolAdapter;
    private final ConnectionAcquisitionStrategy retry;
    private final UslAutoScalingController controller;

    private final AtomicLong acquisitionsInWindow = new AtomicLong();
    private final AtomicLong windowStartNanos = new AtomicLong(System.nanoTime());
    private final ScheduledExecutorService scheduler;

    private UslAutoScalingConnectionAcquisitionStrategy(
            ConfigurationProperties<? extends DataSource, Metrics, PoolAdapter> configurationProperties,
            Config config, ConnectionAcquisitionStrategy retry) {
        super(configurationProperties);
        this.config = config;
        this.poolAdapter = configurationProperties.getPoolAdapter();
        this.retry = retry;
        this.controller = new UslAutoScalingController(config.controllerConfig());

        // Start the bootstrap from the bottom of the range so the very first window observes minPoolSize;
        // the controller then walks its probe ladder upward from there.
        this.poolAdapter.setMaxPoolSize(clamp(config.minPoolSize()));

        this.scheduler = Executors.newSingleThreadScheduledExecutor(daemonThreadFactory());
        this.scheduler.scheduleAtFixedRate(this::estimationTick,
                config.windowMillis(), config.windowMillis(), TimeUnit.MILLISECONDS);
    }

    private static ThreadFactory daemonThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "usl-autoscaling-estimator");
            thread.setDaemon(true);
            return thread;
        };
    }

    @Override
    public Connection getConnection(ConnectionRequestContext requestContext) throws SQLException {
        // Observability only: the size decision belongs to the scheduled estimation tick. Retry (immediate,
        // no back-off) absorbs a transient acquisition timeout while a resize is settling.
        Connection connection = retry.getConnection(requestContext);
        acquisitionsInWindow.incrementAndGet();
        return connection;
    }

    /**
     * One estimation window: convert the acquisitions counted since the last tick into a throughput, let
     * the {@link UslAutoScalingController} re-estimate {@code N*} and decide the next size, and apply it to
     * the live pool. Runs on the single scheduler thread, so no locking is needed around the controller.
     */
    private void estimationTick() {
        try {
            long now = System.nanoTime();
            long elapsedNanos = now - windowStartNanos.getAndSet(now);
            long acquisitions = acquisitionsInWindow.getAndSet(0);
            if (elapsedNanos <= 0) {
                return;
            }
            double throughput = acquisitions / (elapsedNanos / 1e9);
            int currentMaxSize = poolAdapter.getMaxPoolSize();
            int targetSize = controller.decide(new WindowSample(currentMaxSize, throughput, 0, 0));
            if (targetSize != currentMaxSize) {
                poolAdapter.setMaxPoolSize(targetSize);
                UslFit fit = controller.lastFit();
                LOGGER.info("USL retargeted pool size {} -> {} (throughput {} tx/s, N*={}, R2={})",
                        currentMaxSize, targetSize, String.format("%.0f", throughput),
                        controller.lastEstimatedOptimum(),
                        fit == null ? "n/a" : String.format("%.3f", fit.rSquared()));
            }
        } catch (RuntimeException e) {
            // A transient pool-adapter or fit failure must not kill the estimator thread.
            LOGGER.warn("USL estimation tick failed; will retry next window", e);
        }
    }

    /** The controller's most recent estimated optimum {@code N*}, or -1 before the first accepted fit. */
    public int estimatedOptimum() {
        return controller.lastEstimatedOptimum();
    }

    /** The controller's most recent accepted USL fit, or {@code null} before one is accepted. */
    public UslFit lastFit() {
        return controller.lastFit();
    }

    private int clamp(int size) {
        return Math.max(config.minPoolSize(), Math.min(config.maxPoolSize(), size));
    }

    @Override
    public void start() {
        // The scheduler is started in the constructor; nothing to do on the FlexyPool start callback.
    }

    @Override
    public void stop() {
        close();
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }

    @Override
    public String toString() {
        return "UslAutoScalingConnectionAcquisitionStrategy{" +
                "minPoolSize=" + config.minPoolSize() +
                ", maxPoolSize=" + config.maxPoolSize() +
                ", windowMillis=" + config.windowMillis() +
                '}';
    }
}
