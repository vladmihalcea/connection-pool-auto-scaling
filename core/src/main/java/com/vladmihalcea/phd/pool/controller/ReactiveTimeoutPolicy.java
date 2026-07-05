package com.vladmihalcea.phd.pool.controller;

/**
 * A model-free reactive baseline modelling FlexyPool's
 * {@code IncrementPoolOnTimeoutConnectionAcquisitionStrategy}: whenever the tail acquisition latency
 * exceeds a timeout threshold the pool is grown by one connection, up to a hard ceiling; it never
 * shrinks. This faithfully captures the two properties the paper critiques — the policy is
 * <em>reactive</em> (it responds to symptoms, not to a throughput model) and <em>monotonic</em> (it
 * chases the latency signal toward the client-thread count, overshooting the throughput optimum, and
 * cannot recover when the optimum later drops).
 *
 * @author Vlad Mihalcea
 */
public final class ReactiveTimeoutPolicy implements ScalingPolicy {

    private final int minSize;
    private final int maxSize;
    private final long timeoutNanos;
    private final int growStep;

    public ReactiveTimeoutPolicy(int minSize, int maxSize, long timeoutNanos) {
        this(minSize, maxSize, timeoutNanos, 1);
    }

    public ReactiveTimeoutPolicy(int minSize, int maxSize, long timeoutNanos, int growStep) {
        this.minSize = minSize;
        this.maxSize = maxSize;
        this.timeoutNanos = timeoutNanos;
        this.growStep = growStep;
    }

    @Override
    public int decide(WindowSample sample) {
        int current = sample.poolSize();
        if (sample.p99AcquisitionNanos() > timeoutNanos && current < maxSize) {
            return Math.min(maxSize, current + growStep);
        }
        return Math.max(minSize, current);
    }

    @Override
    public String id() {
        return "reactive";
    }
}
