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
 * <p>A regime change (the throughput off the model by more than {@code regimeRelativeThreshold} in two saturated
 * windows in a row) flushes the history and restarts the probe ladder. With {@link #rescaleOnDrift()}, it rescales
 * the history instead and keeps the shape, since the target does not depend on the throughput level; only drifts
 * that repeat within a short span, which signal a change of shape, still flush.
 *
 * <p>Under light load (demand-limited windows), the controller does not fit, but it gives idle connections back: it
 * shrinks the pool toward the smaller of the last estimated plateau start and the smallest size at which the busy
 * connections still leave the pool demand-limited ({@link #idleCap}), never below the Little's Law floor. If the
 * probe ladder ends on a size that demand cannot saturate, the first demand-limited window fits the saturated ladder
 * sizes before it shrinks. Like the other policies, it is a pure decision function with no threads or clock.
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
    private boolean rescaleOnDrift;
    private int rescales;
    private int recentRescales;
    private int windowsSinceRescale = Integer.MAX_VALUE / 2;

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
        windowsSinceRescale++;
        if (lastModel == null) {
            return;
        }
        double predicted = lastModel.throughputAt(sample.poolSize());
        double relativeError = Math.abs(sample.throughput() - predicted) / Math.max(1.0, predicted);
        if (relativeError > config.regimeRelativeThreshold()) {
            if (++regimeStrikes >= 2) {
                regimeStrikes = 0;
                if (rescaleOnDrift && rescale(sample, predicted)) {
                    return;
                }
                reset();
                throughputBySize.put(sample.poolSize(), sample.throughput());
            }
        } else {
            regimeStrikes = 0;
        }
    }

    /**
     * Drift handling without re-exploration: the throughput moved away from the model at the current size, so the
     * whole history is rescaled by the same factor and the shape is kept. The target depends on {@code sigma} and
     * {@code kappa} only, never on {@code lambda}, so a drift of the throughput level (a TPC-C load slowing down as
     * its hot rows accumulate versions) leaves the plateau start where it was and needs no new ladder.
     * <p>
     * A change of shape cannot be told from a change of level at the size the controller holds: on the simulator, after
     * a shift from the ICASC read-mostly shape ({@code N* = 45}) to {@code N* = 26}, the old shape rescaled to the new
     * level predicted the throughput at the held size and at {@code N*} within 4%, inside the noise; only the sizes
     * below the target, which the controller never visits, would show it. What gives a workload change away is the size
     * of the jump. A level drift trips the regime detector (25%) long before it grows large, so only a jump within a
     * factor of {@link #MAX_RESCALE_RATIO} is rescaled; a larger one, or {@link #SHAPE_CHANGE_RESCALES} rescales within
     * {@link #SHAPE_CHANGE_WINDOWS} windows, falls back to the flush and the re-exploration.
     *
     * @return {@code false} when the drift looks like a change of workload, so the caller flushes
     */
    private boolean rescale(WindowSample sample, double predicted) {
        double ratio = sample.throughput() / Math.max(1.0, predicted);
        if (ratio > MAX_RESCALE_RATIO || ratio < 1 / MAX_RESCALE_RATIO) {
            return false;
        }
        if (windowsSinceRescale <= SHAPE_CHANGE_WINDOWS) {
            if (++recentRescales >= SHAPE_CHANGE_RESCALES) {
                recentRescales = 0;
                windowsSinceRescale = Integer.MAX_VALUE / 2;
                return false;
            }
        } else {
            recentRescales = 1;
        }
        throughputBySize.replaceAll((size, throughput) -> throughput * ratio);
        // The current size has just been averaged with the observation; it is the observation now.
        throughputBySize.put(sample.poolSize(), sample.throughput());
        lastModel = new UslModel(lastModel.lambda() * ratio, lastModel.sigma(), lastModel.kappa());
        windowsSinceRescale = 0;
        rescales++;
        return true;
    }

    /** The largest jump of the throughput level that a rescale absorbs, in either direction. */
    static final double MAX_RESCALE_RATIO = 1.5;

    /** Rescales within this many windows of each other suggest a change of shape, not of level. */
    static final int SHAPE_CHANGE_WINDOWS = 30;

    /** The number of rescales within {@link #SHAPE_CHANGE_WINDOWS} windows that falls back to the flush. */
    static final int SHAPE_CHANGE_RESCALES = 3;

    /**
     * Handles a regime change by rescaling the history instead of flushing it (see {@link #rescale}); the id gains
     * {@code @drift:rescale} through {@link Policies}. Off by default, so the draft's results stay as published.
     */
    public UslPlateauController rescaleOnDrift() {
        this.rescaleOnDrift = true;
        return this;
    }

    /** How many drifts were handled by rescaling the history. */
    public int rescales() {
        return rescales;
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
            // Light load: the pool is not the bottleneck. Do not chase demand noise, but give back idle
            // connections. Without a model yet, fit the saturated history first: when the ladder ends on a size
            // demand cannot saturate (96 connections for 64 clients), the ladder's smaller sizes are valid points,
            // and without this fit every later window is demand-limited, so the controller held the cap for the
            // whole run (TPC-C, 18 Nov).
            if (lastModel == null) {
                refit();
            }
            int cap = lastPlateauStart > 0 ? lastPlateauStart
                    : demandLimitedSizes.isEmpty() ? current : demandLimitedSizes.first();
            if (sample.hasOccupancy()) {
                cap = Math.min(cap, idleCap(sample.busyConnections()));
            }
            int target = clamp(Math.max(cap, littleFloor(sample.throughput())));
            return current > target ? stepToward(current, target) : current;
        }

        if (!refit()) {
            return current;
        }

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

    /**
     * Fits the saturated history and updates the estimated optimum and plateau start.
     *
     * @return {@code false} when the fit is unusable, leaving the previous estimates in place
     */
    private boolean refit() {
        UslFit fit = fit();
        if (fit == null) {
            return false;
        }
        lastFit = fit;
        lastModel = fit.model();
        lastNStar = clamp(boundedNStar(lastModel));
        lastPlateauStart = clamp(Math.min(lastNStar, boundedPlateauStart(lastModel)));
        return true;
    }

    /**
     * The elastic shrink of an idle pool: the smallest size at which {@code busy} connections still classify as
     * demand-limited, one connection above {@code busy / (1 - saturationSlack)}. A pool sized at the busy count
     * itself would look saturated and teach the history a point that demand, not the pool, limited.
     */
    int idleCap(double busy) {
        return (int) Math.ceil(busy / (1.0 - config.saturationSlack())) + 1;
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
        recentRescales = 0;
        windowsSinceRescale = Integer.MAX_VALUE / 2;
    }

    @Override
    public String id() {
        return "usl-plateau";
    }

    /** {@code exploring} while it walks the probe ladder, {@code probe} during a probe at {@code N*}, else {@code tracking}. */
    @Override
    public String state() {
        return phase == Phase.EXPLORING ? "exploring" : probing ? "probe" : "tracking";
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
