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
 *
 * @author Vlad Mihalcea
 */
public record WindowSample(int poolSize, double throughput, double p99AcquisitionNanos, double queueLength) {
}
