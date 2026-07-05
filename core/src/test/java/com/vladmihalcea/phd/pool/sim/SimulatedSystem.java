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
 * Tail acquisition latency is modelled as the queueing delay a saturating population of
 * {@code clientThreads} experiences when the pool is smaller than the offered concurrency: it is large
 * while the pool is under-sized and vanishes once the pool can serve every client. That signal is what a
 * reactive, latency-chasing policy climbs — driving it toward the client-thread count and thus past the
 * throughput optimum, exactly the failure mode the paper contrasts against the USL controller.
 *
 * @author Vlad Mihalcea
 */
public final class SimulatedSystem {

    private final int clientThreads;
    private final double noiseFraction;
    private final Random random;
    private UslModel truth;

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
     * Produce one observation window at the given pool size.
     */
    public WindowSample sample(int poolSize) {
        double throughput = truth.throughputAt(poolSize) * (1.0 + noiseFraction * random.nextGaussian());
        throughput = Math.max(1.0, throughput);

        double serviceNanos = 1e9 / truth.lambda();
        double deficit = Math.max(0, clientThreads - poolSize);
        double p99AcquisitionNanos = serviceNanos * (deficit / Math.max(1, poolSize));
        double queueLength = deficit;

        return new WindowSample(poolSize, throughput, p99AcquisitionNanos, queueLength);
    }

    /** The service-time-based timeout the reactive policy uses (half of a single-connection service time). */
    public long reactiveTimeoutNanos() {
        return (long) (0.5 * 1e9 / truth.lambda());
    }
}
