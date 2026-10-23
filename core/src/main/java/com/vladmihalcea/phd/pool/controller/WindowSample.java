package com.vladmihalcea.phd.pool.controller;

/**
 * One observation window handed to a {@link ScalingPolicy}: the pool size that was in effect during the
 * window and the resulting throughput, tail acquisition latency and mean queue length. These are exactly
 * the quantities a real FlexyPool deployment already exposes (committed transactions, concurrent
 * connection-request histogram, acquisition timer), so the same controller runs unchanged over the
 * PostgreSQL-backed harness and the in-memory simulator.
 *
 * @param poolSize             the configured maximum pool size during the window
 * @param throughput           committed transactions per second observed in the window
 * @param p99AcquisitionNanos  99th-percentile connection-acquisition latency (nanoseconds)
 * @param queueLength          mean number of threads waiting for a connection
 * @param busyConnections      mean number of leased (busy) connections (FlexyPool's concurrent-connections
 *                             histogram), or {@link Double#NaN} when the source does not observe it
 * @param meanAcquisitionNanos mean time a request waited for a connection (nanoseconds), or {@link Double#NaN}
 *                             when the source does not time acquisitions
 *
 * @author Vlad Mihalcea
 */
public record WindowSample(int poolSize, double throughput, double p99AcquisitionNanos, double queueLength,
                           double busyConnections, double meanAcquisitionNanos) {

    /** A window without the mean acquisition time. */
    public WindowSample(int poolSize, double throughput, double p99AcquisitionNanos, double queueLength,
                        double busyConnections) {
        this(poolSize, throughput, p99AcquisitionNanos, queueLength, busyConnections, Double.NaN);
    }

    /**
     * A window without connection-occupancy information, as produced by sources that only count
     * transactions.
     */
    public WindowSample(int poolSize, double throughput, double p99AcquisitionNanos, double queueLength) {
        this(poolSize, throughput, p99AcquisitionNanos, queueLength, Double.NaN);
    }

    /** Whether the window reports the mean number of busy connections. */
    public boolean hasOccupancy() {
        return !Double.isNaN(busyConnections);
    }

    /**
     * The mean connection lease time in nanoseconds, derived with Little's Law ({@code W = L / X}), or
     * {@link Double#NaN} when the occupancy is unknown or no transaction completed.
     */
    public double leaseNanos() {
        if (!hasOccupancy() || throughput <= 0) {
            return Double.NaN;
        }
        return busyConnections / throughput * 1e9;
    }
}
