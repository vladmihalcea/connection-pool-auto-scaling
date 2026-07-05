package com.vladmihalcea.phd.pool.controller;

/**
 * A do-nothing baseline that holds the pool at a fixed size — the status quo the paper argues against.
 * Three instances bracket the comparison: an under-provisioned pool, an over-provisioned pool, and the
 * "oracle" static size equal to the offline USL optimum {@code N*} (the best any static configuration
 * can achieve for a stationary workload).
 *
 * @author Vlad Mihalcea
 */
public final class StaticPolicy implements ScalingPolicy {

    private final String id;
    private final int fixedSize;

    public StaticPolicy(String id, int fixedSize) {
        this.id = id;
        this.fixedSize = fixedSize;
    }

    @Override
    public int decide(WindowSample sample) {
        return fixedSize;
    }

    @Override
    public String id() {
        return id;
    }
}
