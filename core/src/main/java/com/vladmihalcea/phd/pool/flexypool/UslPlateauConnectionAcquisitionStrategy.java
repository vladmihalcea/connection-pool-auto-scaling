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
import com.vladmihalcea.phd.pool.controller.UslPlateauController;
import com.vladmihalcea.phd.pool.controller.WindowSample;
import com.vladmihalcea.phd.pool.usl.UslFit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.Closeable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A FlexyPool {@link ConnectionAcquisitionStrategy} that grows and shrinks the pool to follow the start of
 * the USL plateau, {@code N_eps}: the fewest connections whose throughput is within a tolerance
 * {@code epsilon} of the peak. It is the plateau-targeting sibling of
 * {@link UslAutoScalingConnectionAcquisitionStrategy}, which targets the peak {@code N*} itself; the
 * decisions come from a {@link UslPlateauController}.
 *
 * <p>Like its sibling, it counts acquisitions in {@link #getConnection} (delegating to a
 * {@link RetryConnectionAcquisitionStrategy} so a resize in flight never surfaces as a failure) and runs
 * the estimation on a single daemon scheduler thread every {@code windowMillis}, applying the target size
 * through the FlexyPool {@link PoolAdapter}. It adds occupancy: when the {@code FlexyPoolDataSource} is
 * configured with {@link WindowedPoolMetrics#factory}, each window also carries the mean number of busy
 * connections and the mean wait queue from FlexyPool's own concurrency histograms. The controller uses
 * them to fit only the windows in which the pool limited the throughput, and to keep the pool above the
 * Little's Law floor. Without the metrics tap, every window counts as saturated.
 *
 * <p>Shrinking needs help from the pool. Lowering the cap only stops new connections from being opened:
 * HikariCP and DBCP2 keep the connections above the cap while the pool is saturated (Agroal closes them on
 * return by itself). With {@code retireOnShrink}, the strategy therefore retires the surplus itself: after
 * as long as the pool holds more connections than its cap, wraps each borrowed connection so that
 * returning it also closes its physical connection, through the pool's {@link SurplusConnectionRetirer}.
 * The check is made against the live connection count, so exactly the surplus is closed and the rest kept, which matters because
 * reopening connections is slow (about one per second against the local PostgreSQL in our runs), so
 * evicting the whole pool would cost tens of seconds of capacity.
 *
 * <p>The scheduler is stopped by {@link #close()} (or the FlexyPool {@link LifeCycleCallback#stop()}).
 *
 * @author Vlad Mihalcea
 */
public final class UslPlateauConnectionAcquisitionStrategy<T extends DataSource>
        extends AbstractConnectionAcquisitionStrategy implements LifeCycleCallback, Closeable {

    private static final Logger LOGGER = LoggerFactory.getLogger(UslPlateauConnectionAcquisitionStrategy.class);

    /**
     * Tunable knobs. {@code windowMillis} is the estimation cadence; the rest are forwarded to the
     * {@link UslPlateauController}, whose tolerance sets the target {@code N_eps}.
     */
    public record Config(
            int minPoolSize,
            int maxPoolSize,
            long windowMillis,
            int retryAttempts,
            boolean retireOnShrink,
            UslPlateauController.Config controllerConfig) {

        public static Config defaults(int minPoolSize, int maxPoolSize, double tolerance) {
            return new Config(minPoolSize, maxPoolSize, 1_000L, 2, true,
                    UslPlateauController.Config.defaults(minPoolSize, maxPoolSize, tolerance));
        }
    }

    /**
     * Mirrors the {@code Factory} pattern of the built-in FlexyPool strategies and remembers the instance
     * it created, so a test or an embedding application can inspect the estimate or {@link #close()} the
     * scheduler.
     */
    public static class Factory<T extends DataSource>
            implements ConnectionAcquisitionStrategyFactory<UslPlateauConnectionAcquisitionStrategy, T> {

        private final Config config;
        private volatile UslPlateauConnectionAcquisitionStrategy<T> lastInstance;

        public Factory(Config config) {
            this.config = config;
        }

        public Factory(int minPoolSize, int maxPoolSize, double tolerance) {
            this(Config.defaults(minPoolSize, maxPoolSize, tolerance));
        }

        @Override
        @SuppressWarnings("unchecked")
        public UslPlateauConnectionAcquisitionStrategy newInstance(
                ConfigurationProperties<T, Metrics, PoolAdapter<T>> configurationProperties) {
            ConnectionAcquisitionStrategy retry =
                    new RetryConnectionAcquisitionStrategy.Factory<T>(config.retryAttempts())
                            .newInstance(configurationProperties);
            UslPlateauConnectionAcquisitionStrategy<T> instance =
                    new UslPlateauConnectionAcquisitionStrategy(configurationProperties, config, retry);
            lastInstance = instance;
            return instance;
        }

        /** The most recently created strategy, or {@code null} if {@link #newInstance} has not run yet. */
        public UslPlateauConnectionAcquisitionStrategy<T> strategy() {
            return lastInstance;
        }
    }

    private final Config config;
    private final PoolAdapter poolAdapter;
    private final WindowedPoolMetrics windowedMetrics;
    private final ConnectionAcquisitionStrategy retry;
    private final UslPlateauController controller;

    private final SurplusConnectionRetirer retirer;
    private final AtomicInteger retirementsInFlight = new AtomicInteger();
    private final AtomicLong acquisitionsInWindow = new AtomicLong();
    private final AtomicLong windowStartNanos = new AtomicLong(System.nanoTime());
    private final ScheduledExecutorService scheduler;

    private UslPlateauConnectionAcquisitionStrategy(
            ConfigurationProperties<? extends DataSource, Metrics, PoolAdapter> configurationProperties,
            Config config, ConnectionAcquisitionStrategy retry) {
        super(configurationProperties);
        this.config = config;
        this.poolAdapter = configurationProperties.getPoolAdapter();
        this.windowedMetrics = configurationProperties.getMetrics() instanceof WindowedPoolMetrics metrics
                ? metrics : null;
        this.retirer = config.retireOnShrink()
                ? SurplusConnectionRetirer.forDataSource(poolAdapter.getTargetDataSource()) : null;
        this.retry = retry;
        this.controller = new UslPlateauController(config.controllerConfig());
        if (windowedMetrics == null) {
            LOGGER.warn("FlexyPool is not configured with WindowedPoolMetrics: the plateau controller cannot "
                    + "tell saturated windows from demand-limited ones and will fit every window");
        }

        // Start the bootstrap from the bottom of the range; the controller walks its probe ladder upward.
        this.poolAdapter.setMaxPoolSize(clamp(config.minPoolSize()));

        this.scheduler = Executors.newSingleThreadScheduledExecutor(daemonThreadFactory());
        this.scheduler.scheduleAtFixedRate(this::estimationTick,
                config.windowMillis(), config.windowMillis(), TimeUnit.MILLISECONDS);
    }

    private static ThreadFactory daemonThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "usl-plateau-estimator");
            thread.setDaemon(true);
            return thread;
        };
    }

    @Override
    public Connection getConnection(ConnectionRequestContext requestContext) throws SQLException {
        Connection connection = retry.getConnection(requestContext);
        acquisitionsInWindow.incrementAndGet();
        return retirer != null && retirer.openConnections() > poolAdapter.getMaxPoolSize()
                ? retiring(connection) : connection;
    }

    /**
     * Claims one retirement while the pool holds more connections than its cap, counting the retirements
     * already in progress so that concurrent returns do not close more than the surplus.
     */
    private boolean claimRetirement() {
        while (true) {
            int inFlight = retirementsInFlight.get();
            if (retirer.openConnections() - inFlight <= poolAdapter.getMaxPoolSize()) {
                return false;
            }
            if (retirementsInFlight.compareAndSet(inFlight, inFlight + 1)) {
                return true;
            }
        }
    }

    /**
     * Wraps a borrowed connection so that, while the pool holds more connections than its cap, returning
     * it also closes its physical connection through the pool's {@link SurplusConnectionRetirer}.
     */
    private Connection retiring(Connection connection) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    try {
                        if ("close".equals(method.getName()) && claimRetirement()) {
                            try {
                                retirer.closeAndRetire(connection);
                            } finally {
                                retirementsInFlight.decrementAndGet();
                            }
                            return null;
                        }
                        return method.invoke(connection, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    /**
     * One estimation window: build a {@link WindowSample} from the acquisitions (or, with the metrics tap,
     * the released leases and the occupancy), let the controller decide the next size, and apply it.
     */
    private void estimationTick() {
        try {
            long now = System.nanoTime();
            long elapsedNanos = now - windowStartNanos.getAndSet(now);
            long acquisitions = acquisitionsInWindow.getAndSet(0);
            if (elapsedNanos <= 0) {
                return;
            }
            double seconds = elapsedNanos / 1e9;
            int currentMaxSize = poolAdapter.getMaxPoolSize();
            WindowSample sample;
            if (windowedMetrics != null) {
                WindowedPoolMetrics.Window window = windowedMetrics.drain();
                sample = new WindowSample(currentMaxSize, window.releasedConnections() / seconds, 0,
                        window.meanConnectionRequests(), window.meanBusyConnections());
            } else {
                sample = new WindowSample(currentMaxSize, acquisitions / seconds, 0, 0);
            }
            int targetSize = controller.decide(sample);
            LOGGER.debug("USL plateau window: size {}, throughput {} tx/s, busy {}, queue {} -> {}",
                    currentMaxSize, String.format("%.0f", sample.throughput()),
                    String.format("%.1f", sample.busyConnections()), String.format("%.1f", sample.queueLength()),
                    targetSize);
            if (targetSize != currentMaxSize) {
                poolAdapter.setMaxPoolSize(targetSize);
                UslFit fit = controller.lastFit();
                LOGGER.info("USL plateau retargeted pool size {} -> {} (throughput {} tx/s, busy {}, queue {}, "
                                + "N_eps={}, N*={}, R2={})",
                        currentMaxSize, targetSize, String.format("%.0f", sample.throughput()),
                        String.format("%.1f", sample.busyConnections()),
                        String.format("%.1f", sample.queueLength()),
                        controller.lastEstimatedPlateauStart(), controller.lastEstimatedOptimum(),
                        fit == null ? "n/a" : String.format("%.3f", fit.rSquared()));
            }
        } catch (RuntimeException e) {
            // A transient pool-adapter or fit failure must not kill the estimator thread.
            LOGGER.warn("USL plateau estimation tick failed; will retry next window", e);
        }
    }

    /** The controller's most recent plateau start {@code N_eps}, or -1 before the first accepted fit. */
    public int estimatedPlateauStart() {
        return controller.lastEstimatedPlateauStart();
    }

    /** The controller's most recent optimum {@code N*}, or -1 before the first accepted fit. */
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
        return "UslPlateauConnectionAcquisitionStrategy{" +
                "minPoolSize=" + config.minPoolSize() +
                ", maxPoolSize=" + config.maxPoolSize() +
                ", tolerance=" + config.controllerConfig().tolerance() +
                ", windowMillis=" + config.windowMillis() +
                '}';
    }
}
