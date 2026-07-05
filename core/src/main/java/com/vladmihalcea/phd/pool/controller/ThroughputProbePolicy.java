package com.vladmihalcea.phd.pool.controller;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A <em>model-free</em> pool-sizing policy that hill-climbs directly on measured throughput to find the
 * size that maximises it — the same optimum {@link UslAutoScalingController} reaches by fitting the USL,
 * but without any model. It is the decision half of the FlexyPool
 * {@code ThroughputOptimizingConnectionAcquisitionStrategy}: FlexyPool's
 * {@code IncrementPoolOnTimeoutConnectionAcquisitionStrategy} grows the pool one connection at a time
 * (probing larger sizes) and its {@code RetryConnectionAcquisitionStrategy} re-acquires immediately (so a
 * probe pays no extra wait), and this policy decides, from the throughput each probed size produced, when
 * to stop growing and settle at the peak.
 *
 * <p>The throughput-vs-size curve is concave (it rises, peaks at {@code N*}, then falls in the USL
 * retrograde region), so a gradient climb suffices: keep stepping the size up while throughput keeps
 * improving, and once a larger size fails to beat the best seen — i.e. the peak has been passed — settle
 * back at the best size. Per-size throughput is exponentially smoothed so noise does not trigger a false
 * peak. If, once settled, the best size's throughput later collapses (a workload regime change), the
 * history is flushed and the climb restarts, so the optimum can be re-discovered — including
 * <em>shrinking</em> the pool, which FlexyPool's grow-only reactive strategy cannot do.
 *
 * <p>Like the USL controller this is a pure {@link #decide} function with no threads or clock, so it is
 * unit-testable against the simulator and equally embeddable behind a periodic tick in a live pool.
 *
 * @author Vlad Mihalcea
 */
public final class ThroughputProbePolicy implements ScalingPolicy {

    /** Tunable knobs; the defaults are what the experiments use. */
    public record Config(
            int minSize,
            int maxSize,
            int step,
            double emaAlpha,
            double improveEps,
            double regimeDropThreshold) {

        public static Config defaults(int minSize, int maxSize) {
            return new Config(minSize, maxSize, 4, 0.5, 0.03, 0.25);
        }
    }

    private enum Phase {CLIMBING, SETTLED}

    private final Config config;

    // Per-size exponentially-weighted throughput history (the estimator's memory).
    private final Map<Integer, Double> throughputBySize = new LinkedHashMap<>();

    private Phase phase = Phase.CLIMBING;
    private int bestSize = -1;
    private double bestThroughput = 0.0;
    private int highWaterSize = -1;

    public ThroughputProbePolicy(Config config) {
        this.config = config;
    }

    public ThroughputProbePolicy(int minSize, int maxSize) {
        this(Config.defaults(minSize, maxSize));
    }

    @Override
    public int decide(WindowSample sample) {
        int size = sample.poolSize();
        double smoothed = learn(sample);

        double previousBest = bestThroughput;
        if (smoothed > bestThroughput) {
            bestThroughput = smoothed;
            bestSize = size;
        }

        if (phase == Phase.SETTLED) {
            // Regime change: the throughput the settled optimum used to deliver has collapsed. Because the
            // new optimum may lie either side of the current size, flush and restart the climb from the
            // bottom of the range so it can re-discover a smaller optimum too (i.e. shrink the pool).
            if (smoothed > 0 && smoothed < bestThroughput * (1 - config.regimeDropThreshold())) {
                reset();
                return clamp(config.minSize());
            }
            return clamp(bestSize);
        }

        // CLIMBING: keep growing only while a larger size still buys a meaningful throughput gain. Once
        // the frontier stops improving on the best seen — the concave peak, or a flat plateau where more
        // connections no longer help — settle at the smallest size that reached (near-)peak throughput,
        // rather than running on to the ceiling.
        highWaterSize = Math.max(highWaterSize, size);
        boolean atFrontier = size >= highWaterSize;
        boolean improving = smoothed > previousBest * (1 + config.improveEps());
        if (atFrontier && improving && size < config.maxSize()) {
            return clamp(size + config.step());
        }
        phase = Phase.SETTLED;
        return clamp(bestSize);
    }

    /** Folds the observed throughput into the per-size EW history and returns the smoothed value. */
    private double learn(WindowSample sample) {
        if (sample.throughput() <= 0) {
            return throughputBySize.getOrDefault(sample.poolSize(), 0.0);
        }
        return throughputBySize.merge(sample.poolSize(), sample.throughput(),
                (oldValue, obs) -> (1 - config.emaAlpha()) * oldValue + config.emaAlpha() * obs);
    }

    private int clamp(int size) {
        return Math.max(config.minSize(), Math.min(config.maxSize(), size));
    }

    @Override
    public void reset() {
        throughputBySize.clear();
        phase = Phase.CLIMBING;
        bestSize = -1;
        bestThroughput = 0.0;
        highWaterSize = -1;
    }

    @Override
    public String id() {
        return "probe";
    }

    /** The best (throughput-maximising) size discovered so far, or -1 before the first window. */
    public int bestSize() {
        return bestSize;
    }

    /** {@code true} once the climb has settled at the optimum (no longer exploring larger sizes). */
    public boolean isSettled() {
        return phase == Phase.SETTLED;
    }
}
