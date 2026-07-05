package com.vladmihalcea.phd.pool.controller;

/**
 * A pool-sizing policy: given the latest observation window, decide the pool's next maximum size. The
 * auto-scaling experiments (E4/E5) drive every policy through this one interface so the USL controller,
 * the static baselines and the FlexyPool-style reactive strategy are compared on identical footing.
 *
 * @author Vlad Mihalcea
 */
public interface ScalingPolicy {

    /**
     * @param sample the most recent observation window
     * @return the desired maximum pool size for the next window
     */
    int decide(WindowSample sample);

    /**
     * Reset any internal estimator state (called between repetitions).
     */
    default void reset() {
    }

    /**
     * Short identifier used as a CSV series name and figure legend entry.
     */
    String id();
}
