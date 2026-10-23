package com.vladmihalcea.phd.pool.sim;

import com.vladmihalcea.phd.pool.controller.WindowSample;
import com.vladmihalcea.phd.pool.usl.UslModel;

import java.util.Random;

/**
 * A deterministic, in-memory stand-in for the PostgreSQL-backed harness, used to unit-test the
 * auto-scaling controller and to generate the convergence/regime-shift figures (E4/E5) without Docker.
 * <p>
 * Throughput as a function of pool size follows a ground-truth {@link UslModel} (with seeded
 * multiplicative noise), so the simulator has a known optimum {@code N*} the controller should discover.
 * Tail acquisition latency is modeled as the queueing delay a saturating population of
 * {@code clientThreads} experiences when the pool is smaller than the offered concurrency: it is large
 * while the pool is under-sized and vanishes once the pool can serve every client. That signal is what a
 * reactive, latency-chasing policy climbs — driving it toward the client-thread count and thus past the
 * throughput optimum, exactly the failure mode the paper contrasts against the USL controller.
 *
 * @author Vlad Mihalcea
 */
public final class SimulatedSystem {

    private int clientThreads;
    private final double noiseFraction;
    private final Random random;
    private UslModel truth;
    private double demand = Double.POSITIVE_INFINITY;

    public SimulatedSystem(UslModel truth, int clientThreads, double noiseFraction, long seed) {
        this.truth = truth;
        this.clientThreads = clientThreads;
        this.noiseFraction = noiseFraction;
        this.random = new Random(seed);
    }

    /** Swap the ground-truth operating curve to model a workload regime change (E5). */
    public void setTruth(UslModel newTruth) {
        this.truth = newTruth;
    }

    public UslModel truth() {
        return truth;
    }

    /**
     * Change the number of closed-loop clients (with no think time), to model a change in the offered
     * concurrency. With fewer clients than connections, only the clients' connections are busy, the queue is
     * empty, and the throughput is that of the client concurrency.
     */
    public void setClientThreads(int clients) {
        this.clientThreads = clients;
    }

    public int clientThreads() {
        return clientThreads;
    }

    /**
     * Cap the offered load at {@code transactionsPerSecond} (an open workload), or remove the cap with
     * {@link Double#POSITIVE_INFINITY} (the default: a closed system of saturating client threads). When the
     * pool can serve more than the demand, the window is demand-limited: throughput equals the demand, the
     * queue is empty, and only as many connections are busy as the demand needs.
     */
    public void setDemand(double transactionsPerSecond) {
        this.demand = transactionsPerSecond;
    }

    /**
     * Produce one observation window at the given pool size.
     */
    public WindowSample sample(int poolSize) {
        double noise = 1.0 + noiseFraction * random.nextGaussian();
        double capacity = truth.throughputAt(poolSize);
        double serviceNanos = 1e9 / truth.lambda();

        if (demand < capacity) {
            // Demand-limited: the busy connections are the concurrency at which the curve delivers the demand.
            double throughput = Math.max(1.0, demand * noise);
            return new WindowSample(poolSize, throughput, 0, 0, concurrencyFor(demand, poolSize));
        }

        if (clientThreads < poolSize) {
            // Fewer closed-loop clients than connections: the clients, not the pool, set the concurrency.
            double throughput = Math.max(1.0, truth.throughputAt(clientThreads) * noise);
            return new WindowSample(poolSize, throughput, 0, 0, clientThreads, 0);
        }

        double throughput = Math.max(1.0, capacity * noise);
        double deficit = Math.max(0, clientThreads - poolSize);
        double p99AcquisitionNanos = serviceNanos * (deficit / Math.max(1, poolSize));
        double queueLength = deficit;

        // Little's Law over the wait queue: the mean wait is the queue length divided by the throughput.
        return new WindowSample(poolSize, throughput, p99AcquisitionNanos, queueLength,
                Math.min(poolSize, clientThreads), queueLength / throughput * 1e9);
    }

    /** The smallest concurrency {@code c <= poolSize} with {@code X(c) = throughput}, by bisection. */
    private double concurrencyFor(double throughput, int poolSize) {
        double low = 0.0;
        double high = Math.min(poolSize, truth.optimalPoolSizeReal());
        for (int i = 0; i < 60; i++) {
            double mid = (low + high) / 2;
            if (truth.throughputAt(mid) < throughput) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return high;
    }

    /** The service-time-based timeout the reactive policy uses (half of a single-connection service time). */
    public long reactiveTimeoutNanos() {
        return (long) (0.5 * 1e9 / truth.lambda());
    }
}
