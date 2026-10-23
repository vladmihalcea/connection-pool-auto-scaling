package com.vladmihalcea.phd.pool.flexypool;

import com.vladmihalcea.flexypool.FlexyPoolDataSource;
import com.vladmihalcea.flexypool.metric.Histogram;
import com.vladmihalcea.flexypool.metric.Metrics;
import com.vladmihalcea.flexypool.metric.MetricsFactory;
import com.vladmihalcea.flexypool.metric.Timer;

import java.util.concurrent.atomic.LongAdder;

/**
 * A FlexyPool {@link Metrics} decorator that taps the streams FlexyPool already records and aggregates them
 * per observation window, so a sizing strategy can read the pool's occupancy without a metrics backend:
 * <ul>
 *   <li>{@value FlexyPoolDataSource#CONCURRENT_CONNECTIONS_HISTOGRAM}: the number of leased connections,
 *       updated on every acquire and release, gives the mean number of busy connections;</li>
 *   <li>{@value FlexyPoolDataSource#CONCURRENT_CONNECTION_REQUESTS_HISTOGRAM}: the number of threads inside
 *       {@code getConnection}, gives the mean wait queue;</li>
 *   <li>{@value FlexyPoolDataSource#CONNECTION_LEASE_MILLIS}: one update per released connection, gives the
 *       completed-lease throughput.</li>
 * </ul>
 * The lease timer is only counted, not summed: FlexyPool truncates lease times to whole milliseconds, which
 * rounds sub-millisecond OLTP leases to zero. The mean lease time follows from Little's Law instead
 * ({@code W = busy / throughput}), see {@code WindowSample#leaseNanos()}.
 *
 * <p>The means are time-weighted: each value counts for as long as it held. An event-sampled mean would be
 * biased low, because a saturated pool reports {@code N} after every acquire and {@code N - 1} after every
 * release, which averages to {@code N - 0.5} even though the pool is full almost all the time. The price
 * is a short critical section on every acquire and release. Every metric is still forwarded to the wrapped
 * {@link Metrics}, so an application keeps its Dropwizard or Micrometer reporting.
 *
 * @author Vlad Mihalcea
 */
public final class WindowedPoolMetrics implements Metrics {

    /** One drained observation window. */
    public record Window(long releasedConnections, double meanBusyConnections, double meanConnectionRequests) {
    }

    /**
     * @param delegate the factory of the metrics to forward to, or {@code null} to forward nothing
     */
    public static MetricsFactory factory(MetricsFactory delegate) {
        return configurationProperties -> new WindowedPoolMetrics(
                delegate == null ? null : delegate.newInstance(configurationProperties));
    }

    private static final Histogram NO_HISTOGRAM = value -> {
    };
    private static final Timer NO_TIMER = (duration, unit) -> {
    };

    private final Metrics delegate;

    private final TimeWeightedGauge busy = new TimeWeightedGauge();
    private final TimeWeightedGauge requests = new TimeWeightedGauge();
    private final LongAdder releases = new LongAdder();

    public WindowedPoolMetrics(Metrics delegate) {
        this.delegate = delegate;
    }

    @Override
    public Histogram histogram(String name) {
        Histogram forward = delegate == null ? NO_HISTOGRAM : delegate.histogram(name);
        if (FlexyPoolDataSource.CONCURRENT_CONNECTIONS_HISTOGRAM.equals(name)) {
            return value -> {
                busy.update(value);
                forward.update(value);
            };
        }
        if (FlexyPoolDataSource.CONCURRENT_CONNECTION_REQUESTS_HISTOGRAM.equals(name)) {
            return value -> {
                requests.update(value);
                forward.update(value);
            };
        }
        return forward;
    }

    @Override
    public Timer timer(String name) {
        Timer forward = delegate == null ? NO_TIMER : delegate.timer(name);
        if (FlexyPoolDataSource.CONNECTION_LEASE_MILLIS.equals(name)) {
            return (duration, unit) -> {
                releases.increment();
                forward.update(duration, unit);
            };
        }
        return forward;
    }

    /** Returns the aggregates since the previous call and starts a new window. */
    public Window drain() {
        return new Window(releases.sumThenReset(), busy.drain(), requests.drain());
    }

    /** The time-weighted mean of a step function that changes on every {@link #update}. */
    private static final class TimeWeightedGauge {

        private long value;
        private long lastChangeNanos = System.nanoTime();
        private long windowStartNanos = lastChangeNanos;
        private double area;

        synchronized void update(long newValue) {
            long now = System.nanoTime();
            area += value * (double) (now - lastChangeNanos);
            value = newValue;
            lastChangeNanos = now;
        }

        synchronized double drain() {
            long now = System.nanoTime();
            area += value * (double) (now - lastChangeNanos);
            lastChangeNanos = now;
            long elapsed = now - windowStartNanos;
            double mean = elapsed > 0 ? area / elapsed : value;
            area = 0;
            windowStartNanos = now;
            return mean;
        }
    }

    @Override
    public void start() {
        if (delegate != null) {
            delegate.start();
        }
    }

    @Override
    public void stop() {
        if (delegate != null) {
            delegate.stop();
        }
    }
}
