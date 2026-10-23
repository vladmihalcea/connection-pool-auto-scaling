package com.vladmihalcea.phd.pool.controller;

import com.vladmihalcea.phd.pool.usl.UslFit;
import com.vladmihalcea.phd.pool.usl.UslFitter;
import com.vladmihalcea.phd.pool.usl.SizingTarget;
import com.vladmihalcea.phd.pool.usl.UslModel;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

/**
 * A USL auto-scaler that sizes the pool at the <em>start of the plateau</em> instead of at the peak. For a
 * tolerance {@code epsilon}, its set-point is
 *
 * <pre>
 *     N_eps = min { N : X(N) >= (1 - epsilon) * X(N*) },
 * </pre>
 *
 * the fewest connections that still deliver near-peak throughput (see {@link UslModel#poolSizeAtFraction}), or,
 * with an elasticity {@link SizingTarget}, at the point of diminishing returns between the plateau start and
 * {@code N*} ({@link UslModel#poolSizeAtElasticity}).
 * Near {@code N*} the USL curve is flat, so the last connections up to the peak add almost nothing; with the
 * ICASC coefficients, 95% of the peak needs about half of {@code N*}'s connections.
 *
 * <p>It shares the estimation loop of {@link UslAutoScalingController} (probe-ladder bootstrap,
 * exponentially-weighted per-size history, online fit, dead-band, saturated step and regime-change flush)
 * and adds what the plateau objective needs:
 * <ul>
 *   <li><b>Saturation filter.</b> A window is a point on the USL curve only when the pool limits the
 *       throughput, that is, when at least {@code min((1 - saturationSlack) * N, N - 0.5)} connections
 *       are busy on average (the absolute bound matters at small {@code N}, where the hand-off gap between
 *       a return and the next borrow is a large share of one connection). A closed workload with exactly {@code N} clients keeps every connection busy with no one
 *       waiting, and that window is still a valid point at concurrency {@code N}. The other windows split
 *       on the wait queue:
 *       <ul>
 *         <li>with more than {@code (1 + saturationSlack) * N} busy connections, or with fewer busy
 *             connections and a mean queue of at least {@code minQueueLength} while the busy count still
 *             moves by {@code stableBusyDelta} or more between windows, the pool has not reached its new
 *             size yet (HikariCP, for instance, opens connections lazily after a grow), so the controller
 *             <em>holds</em> the size and waits for a clean window. Fewer busy connections that hold
 *             steady under a queue mean a full pool whose return path keeps part of each connection out
 *             of the lease (DBCP2), so that window counts as saturated;</li>
 *         <li>with an empty queue, demand, not the pool, sets the throughput (light load), and fitting
 *             those points would bias {@code sigma} and {@code kappa}.</li>
 *       </ul>
 *       Windows without occupancy data (a strategy without the metrics tap) count as saturated.</li>
 *   <li><b>Little's Law floor.</b> To sustain a throughput {@code X}, the pool needs on average
 *       {@code X * W_0} busy connections, where {@code W_0} is the uncontended lease time. The controller
 *       never targets fewer than {@code ceil(X * W_0 * (1 + littleMargin))} connections, with {@code W_0}
 *       taken as the shortest lease time observed so far, or {@code 1 / lambda} without occupancy data.</li>
 *   <li><b>Probes past the plateau start.</b> Holding the pool at {@code N_eps} never shows the flat region, so
 *       {@code X(N*)}, and therefore {@code N_eps} itself, would rest on stale points. Every
 *       {@code probeInterval} saturated windows the controller spends one window at the estimated
 *       {@code N*} and then returns to the plateau start. A pool that opens connections slowly may need several
 *       windows to reach {@code N*}; after {@code maxProbeResizeWindows} of them the probe is abandoned,
 *       so a probe never holds the extra connections for long.</li>
 * </ul>
 *
 * <p>Under light load, the controller holds the current size, except that it shrinks a pool larger than
 * the last estimated plateau start. Like the other policies, it is a pure decision function with no threads or
 * clock.
 *
 * @author Vlad Mihalcea
 */
public final class UslPlateauController implements ScalingPolicy {

    /** Tunable knobs; the defaults are what the experiments use. */
    public record Config(
            int minSize,
            int maxSize,
            double tolerance,
            int stepLimit,
            int deadBand,
            int minPointsForFit,
            double minRSquared,
            double emaAlpha,
            double regimeRelativeThreshold,
            int probeInterval,
            double saturationSlack,
            double minQueueLength,
            double stableBusyDelta,
            double littleMargin,
            int maxProbeResizeWindows,
            SizingTarget target) {

        public Config {
            if (!(tolerance > 0.0 && tolerance < 1.0)) {
                throw new IllegalArgumentException("tolerance must be in (0, 1): " + tolerance);
            }
            if (target == null) {
                target = new SizingTarget.Tolerance(tolerance);
            }
        }

        public static Config defaults(int minSize, int maxSize) {
            return defaults(minSize, maxSize, 0.05);
        }

        /** The plateau start for a fixed tolerance (the configuration of the K3/K5, L1, and L2 experiments). */
        public static Config defaults(int minSize, int maxSize, double tolerance) {
            return new Config(minSize, maxSize, tolerance, 8, 2, 4, 0.80, 0.5, 0.25, 15, 0.10, 1.0, 0.5, 0.20, 3,
                    new SizingTarget.Tolerance(tolerance));
        }

        /**
         * The same configuration aimed at another target, for example the point of diminishing returns
         * ({@link SizingTarget.Elasticity}). A tolerance target also sets {@code tolerance}.
         */
        public Config withTarget(SizingTarget newTarget) {
            double newTolerance = newTarget instanceof SizingTarget.Tolerance t ? t.epsilon() : tolerance;
            return new Config(minSize, maxSize, newTolerance, stepLimit, deadBand, minPointsForFit, minRSquared,
                    emaAlpha, regimeRelativeThreshold, probeInterval, saturationSlack, minQueueLength, stableBusyDelta,
                    littleMargin, maxProbeResizeWindows, newTarget);
        }
    }

    private enum Phase {EXPLORING, TRACKING}

    /** What limited a window's throughput. */
    enum Load {SATURATED, RESIZING, DEMAND_LIMITED}

    private final Config config;
    private final int[] probeLadder;

    // Per-size exponentially-weighted throughput history, fed by saturated windows only.
    private final Map<Integer, Double> throughputBySize = new LinkedHashMap<>();

    // Ladder sizes the bootstrap visited while demand, not the pool, limited throughput.
    private final TreeSet<Integer> demandLimitedSizes = new TreeSet<>();

    private Phase phase = Phase.EXPLORING;
    private UslModel lastModel;
    private UslFit lastFit;
    private int regimeStrikes;
    private int lastPlateauStart = -1;
    private int lastNStar = -1;
    private double minLeaseNanos = Double.NaN;
    private int saturatedWindowsSinceProbe;
    private boolean probing;
    private int probeResizeWindows;
    private WindowSample previous;

    public UslPlateauController(Config config) {
        this.config = config;
        this.probeLadder = UslAutoScalingController.buildProbeLadder(config.minSize(), config.maxSize());
    }

    public UslPlateauController(int minSize, int maxSize) {
        this(Config.defaults(minSize, maxSize));
    }

    @Override
    public int decide(WindowSample sample) {
        int current = sample.poolSize();
        Load load = classify(sample);
        previous = sample;
        observeLease(sample);
        if (load == Load.RESIZING) {
            // The pool has not reached its configured size: not a point on the curve.
            if (probing && ++probeResizeWindows > config.maxProbeResizeWindows()) {
                probing = false;
                return lastPlateauStart > 0 ? lastPlateauStart : current;
            }
            return current;
        }
        boolean saturated = load == Load.SATURATED;
        if (saturated) {
            learn(sample);
            detectRegimeChange(sample);
        } else if (phase == Phase.EXPLORING) {
            demandLimitedSizes.add(current);
        }

        // Bootstrap / re-exploration: walk the probe ladder, skipping sizes demand cannot saturate.
        if (phase == Phase.EXPLORING) {
            int probe = nextUnsampledProbe();
            if (probe > 0) {
                return clamp(probe);
            }
            phase = Phase.TRACKING;
        }
        if (throughputBySize.size() < config.minPointsForFit()) {
            if (saturated && !demandLimitedSizes.isEmpty()) {
                // Demand has grown since the bootstrap: the skipped sizes may now be saturated.
                demandLimitedSizes.clear();
                phase = Phase.EXPLORING;
                int probe = nextUnsampledProbe();
                return probe > 0 ? clamp(probe) : current;
            }
            // Too few saturated sizes to fit: settle at the smallest size demand did not saturate.
            return demandLimitedSizes.isEmpty() ? current : clamp(demandLimitedSizes.first());
        }
        return track(sample, saturated);
    }

    /**
     * Classifies what limited the window's throughput. Windows without occupancy data count as saturated.
     */
    Load classify(WindowSample sample) {
        if (!sample.hasOccupancy()) {
            return Load.SATURATED;
        }
        double busy = sample.busyConnections();
        if (busy > (1.0 + config.saturationSlack()) * sample.poolSize()) {
            return Load.RESIZING;
        }
        // At small sizes a relative slack is too strict: with 2 connections, the hand-off gap between a
        // return and the next borrow alone leaves 1.7 busy on average (85%). Allow at most half a connection.
        double saturatedBusy = Math.min((1.0 - config.saturationSlack()) * sample.poolSize(),
                sample.poolSize() - 0.5);
        if (busy >= saturatedBusy) {
            return Load.SATURATED;
        }
        if (sample.queueLength() < config.minQueueLength()) {
            return Load.DEMAND_LIMITED;
        }
        // Threads wait, yet fewer connections than the cap are busy. A pool still opening connections
        // gains busy connections window over window; a full pool whose own return path keeps part of each
        // connection out of the lease (DBCP2 rolls back and resets auto-commit after FlexyPool's lease
        // ends) holds steady. The second is a valid point on the curve.
        boolean stable = previous != null && previous.poolSize() == sample.poolSize() && previous.hasOccupancy()
                && Math.abs(busy - previous.busyConnections()) < config.stableBusyDelta();
        return stable ? Load.SATURATED : Load.RESIZING;
    }

    private void observeLease(WindowSample sample) {
        double lease = sample.leaseNanos();
        if (lease > 0 && (Double.isNaN(minLeaseNanos) || lease < minLeaseNanos)) {
            minLeaseNanos = lease;
        }
    }

    private void learn(WindowSample sample) {
        if (sample.throughput() <= 0) {
            return;
        }
        throughputBySize.merge(sample.poolSize(), sample.throughput(),
                (oldValue, obs) -> (1 - config.emaAlpha()) * oldValue + config.emaAlpha() * obs);
    }

    private void detectRegimeChange(WindowSample sample) {
        if (lastModel == null) {
            return;
        }
        double predicted = lastModel.throughputAt(sample.poolSize());
        double relativeError = Math.abs(sample.throughput() - predicted) / Math.max(1.0, predicted);
        if (relativeError > config.regimeRelativeThreshold()) {
            if (++regimeStrikes >= 2) {
                reset();
                throughputBySize.put(sample.poolSize(), sample.throughput());
            }
        } else {
            regimeStrikes = 0;
        }
    }

    private int nextUnsampledProbe() {
        for (int probe : probeLadder) {
            if (!throughputBySize.containsKey(probe) && !demandLimitedSizes.contains(probe)) {
                return probe;
            }
        }
        return -1;
    }

    private int track(WindowSample sample, boolean saturated) {
        int current = sample.poolSize();
        if (probing) {
            // The probe window at N* is over: return straight to the plateau start.
            probing = false;
            return lastPlateauStart > 0 ? lastPlateauStart : current;
        }
        if (!saturated) {
            // Light load: the pool is not the bottleneck. Do not chase demand noise, but give back
            // connections held above the plateau start or, before the first fit, above the smallest size the
            // bootstrap found demand-limited.
            int cap = lastPlateauStart > 0 ? lastPlateauStart
                    : demandLimitedSizes.isEmpty() ? current : demandLimitedSizes.first();
            int target = clamp(Math.max(cap, littleFloor(sample.throughput())));
            return current > target ? stepToward(current, target) : current;
        }

        UslFit fit = fit();
        if (fit == null) {
            return current;
        }
        lastFit = fit;
        lastModel = fit.model();
        lastNStar = clamp(boundedNStar(lastModel));
        lastPlateauStart = clamp(Math.min(lastNStar, boundedPlateauStart(lastModel)));

        if (config.probeInterval() > 0 && ++saturatedWindowsSinceProbe >= config.probeInterval()
                && lastNStar > lastPlateauStart + config.deadBand()) {
            saturatedWindowsSinceProbe = 0;
            probing = true;
            probeResizeWindows = 0;
            return lastNStar;
        }

        int target = clamp(Math.max(lastPlateauStart, littleFloor(sample.throughput())));
        return stepToward(current, target);
    }

    /** Fits the USL to the saturated history, or returns {@code null} when the fit is unusable. */
    private UslFit fit() {
        double[] sizes = new double[throughputBySize.size()];
        double[] tput = new double[throughputBySize.size()];
        int i = 0;
        for (Map.Entry<Integer, Double> e : throughputBySize.entrySet()) {
            sizes[i] = e.getKey();
            tput[i] = e.getValue();
            i++;
        }
        try {
            UslFit fit = UslFitter.fit(sizes, tput);
            return fit.rSquared() < config.minRSquared() ? null : fit;
        } catch (RuntimeException fitFailure) {
            return null;
        }
    }

    private int stepToward(int current, int target) {
        if (Math.abs(current - target) < config.deadBand()) {
            return current;
        }
        int delta = Integer.signum(target - current) * Math.min(config.stepLimit(), Math.abs(target - current));
        return clamp(current + delta);
    }

    /**
     * Little's Law lower bound for the throughput {@code X}: {@code ceil(X * W_0 * (1 + littleMargin))},
     * where {@code W_0} is the shortest observed lease time, or {@code 1 / lambda} from the fit.
     */
    int littleFloor(double throughput) {
        double w0Seconds;
        if (!Double.isNaN(minLeaseNanos)) {
            w0Seconds = minLeaseNanos / 1e9;
        } else if (lastModel != null) {
            w0Seconds = 1.0 / lastModel.lambda();
        } else {
            return config.minSize();
        }
        return (int) Math.ceil(throughput * w0Seconds * (1.0 + config.littleMargin()));
    }

    private int boundedNStar(UslModel model) {
        int nStar = model.optimalPoolSize();
        return nStar == Integer.MAX_VALUE ? config.maxSize() : nStar;
    }

    /**
     * The target below the optimum: the plateau start relative to the best throughput reachable within
     * {@code maxSize} for a tolerance target, or the point of diminishing returns for an elasticity target.
     */
    private int boundedPlateauStart(UslModel model) {
        return config.target().poolSize(model, config.maxSize());
    }

    private int clamp(int size) {
        return Math.max(config.minSize(), Math.min(config.maxSize(), size));
    }

    @Override
    public void reset() {
        throughputBySize.clear();
        demandLimitedSizes.clear();
        phase = Phase.EXPLORING;
        lastModel = null;
        lastFit = null;
        regimeStrikes = 0;
        lastPlateauStart = -1;
        lastNStar = -1;
        minLeaseNanos = Double.NaN;
        saturatedWindowsSinceProbe = 0;
        probing = false;
        probeResizeWindows = 0;
        previous = null;
    }

    @Override
    public String id() {
        return "usl-plateau";
    }

    /** The most recent estimated plateau start {@code N_eps}, or -1 before the first accepted fit. */
    public int lastEstimatedPlateauStart() {
        return lastPlateauStart;
    }

    /** The most recent estimated optimum {@code N*}, or -1 before the first accepted fit. */
    public int lastEstimatedOptimum() {
        return lastNStar;
    }

    /** The most recent accepted fit, or {@code null}. */
    public UslFit lastFit() {
        return lastFit;
    }
}
