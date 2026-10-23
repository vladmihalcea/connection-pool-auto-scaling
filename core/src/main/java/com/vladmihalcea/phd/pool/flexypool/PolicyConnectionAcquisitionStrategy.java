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
import com.vladmihalcea.phd.pool.controller.ScalingPolicy;
import com.vladmihalcea.phd.pool.controller.WindowSample;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.Recorder;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A FlexyPool {@link ConnectionAcquisitionStrategy} that sizes the pool with any {@link ScalingPolicy}, so the
 * live experiments drive every policy (the USL controllers, the genetic and PID baselines, the reactive and
 * static ones) through the same monitoring and actuation path.
 *
 * <p>Every {@code windowMillis}, a single daemon thread builds a {@link WindowSample} from FlexyPool's own
 * streams ({@link WindowedPoolMetrics}: the released leases, the time-weighted busy connections and wait queue)
 * and from the acquisition times this strategy measures itself (mean and p99, from the call to
 * {@link #getConnection} to its return), asks the policy for the next size, and applies it through the FlexyPool
 * {@link PoolAdapter}. A listener receives every decision, so an experiment can trace the control loop.
 *
 * <p>Acquisition goes through a {@link RetryConnectionAcquisitionStrategy}, so a borrow that times out while
 * the pool grows is retried rather than failed. With {@code retireOnShrink}, a shrink closes exactly the
 * surplus connections as they are returned ({@link SurplusConnectionRetirer}), as in
 * {@link UslPlateauConnectionAcquisitionStrategy}.
 *
 * @author Vlad Mihalcea
 */
public final class PolicyConnectionAcquisitionStrategy<T extends DataSource>
        extends AbstractConnectionAcquisitionStrategy implements LifeCycleCallback, Closeable {

    private static final Logger LOGGER = LoggerFactory.getLogger(PolicyConnectionAcquisitionStrategy.class);

    /**
     * @param minPoolSize     the smallest size the strategy applies
     * @param maxPoolSize     the largest size the strategy applies
     * @param initialPoolSize the size set when the strategy starts
     * @param windowMillis    the decision cadence
     * @param retryAttempts   the retries of a timed-out borrow
     * @param retireOnShrink  whether a shrink closes the surplus connections as they are returned
     */
    public record Config(int minPoolSize, int maxPoolSize, int initialPoolSize, long windowMillis,
                         int retryAttempts, boolean retireOnShrink) {

        public static Config defaults(int minPoolSize, int maxPoolSize, int initialPoolSize) {
            return new Config(minPoolSize, maxPoolSize, initialPoolSize, 1_000L, 2, true);
        }
    }

    /** One decision of the control loop: the window the policy saw and the size it chose. */
    public record Decision(long nanoTime, WindowSample sample, int targetSize, int openConnections) {
    }

    /** Creates the strategy for FlexyPool and remembers the instance, so a test can inspect or close it. */
    public static class Factory<T extends DataSource>
            implements ConnectionAcquisitionStrategyFactory<PolicyConnectionAcquisitionStrategy, T> {

        private final Config config;
        private final Supplier<ScalingPolicy> policy;
        private final Consumer<Decision> listener;
        private volatile PolicyConnectionAcquisitionStrategy<T> lastInstance;

        public Factory(Config config, Supplier<ScalingPolicy> policy, Consumer<Decision> listener) {
            this.config = config;
            this.policy = policy;
            this.listener = listener;
        }

        @Override
        @SuppressWarnings("unchecked")
        public PolicyConnectionAcquisitionStrategy newInstance(
                ConfigurationProperties<T, Metrics, PoolAdapter<T>> configurationProperties) {
            ConnectionAcquisitionStrategy retry =
                    new RetryConnectionAcquisitionStrategy.Factory<T>(config.retryAttempts())
                            .newInstance(configurationProperties);
            PolicyConnectionAcquisitionStrategy<T> instance = new PolicyConnectionAcquisitionStrategy(
                    configurationProperties, config, policy.get(), listener, retry);
            lastInstance = instance;
            return instance;
        }

        public PolicyConnectionAcquisitionStrategy<T> strategy() {
            return lastInstance;
        }
    }

    private final Config config;
    private final ScalingPolicy policy;
    private final Consumer<Decision> listener;
    private final PoolAdapter poolAdapter;
    private final WindowedPoolMetrics windowedMetrics;
    private final ConnectionAcquisitionStrategy retry;
    private final SurplusConnectionRetirer retirer;
    private final AtomicInteger retirementsInFlight = new AtomicInteger();
    private final LongAdder acquisitions = new LongAdder();
    private final LongAdder acquisitionNanos = new LongAdder();
    private final Recorder acquisitionRecorder = new Recorder(3);
    private final ScheduledExecutorService scheduler;
    private Histogram recycled;
    private long windowStartNanos = System.nanoTime();

    private PolicyConnectionAcquisitionStrategy(
            ConfigurationProperties<? extends DataSource, Metrics, PoolAdapter> configurationProperties,
            Config config, ScalingPolicy policy, Consumer<Decision> listener, ConnectionAcquisitionStrategy retry) {
        super(configurationProperties);
        this.config = config;
        this.policy = policy;
        this.listener = listener;
        this.poolAdapter = configurationProperties.getPoolAdapter();
        this.windowedMetrics = configurationProperties.getMetrics() instanceof WindowedPoolMetrics metrics
                ? metrics : null;
        this.retirer = config.retireOnShrink()
                ? SurplusConnectionRetirer.forDataSource(poolAdapter.getTargetDataSource()) : null;
        this.retry = retry;
        if (windowedMetrics == null) {
            LOGGER.warn("FlexyPool is not configured with WindowedPoolMetrics: the windows carry no occupancy");
        }
        policy.reset();
        poolAdapter.setMaxPoolSize(clamp(config.initialPoolSize()));
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "pool-policy-" + policy.id());
            thread.setDaemon(true);
            return thread;
        });
        this.scheduler.scheduleAtFixedRate(this::tick, config.windowMillis(), config.windowMillis(),
                TimeUnit.MILLISECONDS);
    }

    @Override
    public Connection getConnection(ConnectionRequestContext requestContext) throws SQLException {
        long start = System.nanoTime();
        Connection connection = retry.getConnection(requestContext);
        long waited = System.nanoTime() - start;
        acquisitions.increment();
        acquisitionNanos.add(waited);
        acquisitionRecorder.recordValue(Math.max(1, waited));
        return retirer != null && retirer.openConnections() > poolAdapter.getMaxPoolSize()
                ? retiring(connection) : connection;
    }

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

    private void tick() {
        try {
            long now = System.nanoTime();
            double seconds = (now - windowStartNanos) / 1e9;
            windowStartNanos = now;
            if (seconds <= 0) {
                return;
            }
            long count = acquisitions.sumThenReset();
            long waitedNanos = acquisitionNanos.sumThenReset();
            recycled = acquisitionRecorder.getIntervalHistogram(recycled);
            double meanAcquisition = count == 0 ? Double.NaN : (double) waitedNanos / count;
            double p99Acquisition = recycled.getTotalCount() == 0 ? 0 : recycled.getValueAtPercentile(99);
            int currentMaxSize = poolAdapter.getMaxPoolSize();
            WindowSample sample;
            if (windowedMetrics != null) {
                WindowedPoolMetrics.Window window = windowedMetrics.drain();
                sample = new WindowSample(currentMaxSize, window.releasedConnections() / seconds, p99Acquisition,
                        window.meanConnectionRequests(), window.meanBusyConnections(), meanAcquisition);
            } else {
                sample = new WindowSample(currentMaxSize, count / seconds, p99Acquisition, 0, Double.NaN,
                        meanAcquisition);
            }
            int target = clamp(policy.decide(sample));
            if (target != currentMaxSize) {
                poolAdapter.setMaxPoolSize(target);
            }
            if (listener != null) {
                listener.accept(new Decision(now, sample, target, retirer == null ? -1 : retirer.openConnections()));
            }
        } catch (RuntimeException e) {
            LOGGER.warn("Pool policy {} tick failed; will retry next window", policy.id(), e);
        }
    }

    /** The policy this strategy drives. */
    public ScalingPolicy policy() {
        return policy;
    }

    private int clamp(int size) {
        return Math.max(config.minPoolSize(), Math.min(config.maxPoolSize(), size));
    }

    @Override
    public void start() {
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
        return "PolicyConnectionAcquisitionStrategy{policy=" + policy.id() + ", minPoolSize=" + config.minPoolSize()
                + ", maxPoolSize=" + config.maxPoolSize() + ", windowMillis=" + config.windowMillis() + '}';
    }
}
