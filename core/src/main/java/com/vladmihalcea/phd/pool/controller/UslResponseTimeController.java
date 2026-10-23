package com.vladmihalcea.phd.pool.controller;

import com.vladmihalcea.phd.pool.usl.LeaseTimeFitter;
import com.vladmihalcea.phd.pool.usl.SizingTarget;
import com.vladmihalcea.phd.pool.usl.UslFit;
import com.vladmihalcea.phd.pool.usl.UslModel;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * An elastic USL auto-scaler that decides from <em>response time</em>. The pool follows the demand, growing when
 * requests wait and shrinking when connections go unused; when the load holds steady and the pool limits the
 * throughput, the controller characterizes that load: it tries a range of pool sizes to find {@code Nmax}, fits
 * the USL to the lease times it measured, and holds a size below {@code Nmax}, between the start of the plateau and
 * {@code Nmax}, by default at the point of diminishing returns ({@link SizingTarget}).
 *
 * <p><b>The model.</b> Little's Law turns the USL into a lease time that is quadratic in the number of busy
 * connections {@code c} (Schwartz 2015, Eq. 6; Gunther's {@code R(N) = N / X(N)}):
 *
 * <pre>
 *     W(c) = W_0 * [1 + sigma*(c - 1) + kappa*c*(c - 1)],    X(c) = c / W(c).
 * </pre>
 *
 * Every window gives one point {@code (c, W)}, with {@code c} the mean busy connections and {@code W = c / X}; the
 * coefficients follow from linear least squares ({@link LeaseTimeFitter}). Demand-limited windows are points too,
 * at their own concurrency (H5 of the plateau study).
 *
 * <p><b>The offered load.</b> The busy connections plus the waiting requests, {@code M = L + Q}, is the number of
 * requests that want a connection, whatever the pool size: in a closed loop it is the number of clients, under open
 * arrivals the concurrent requests. The controller smooths it and starts a new <em>load episode</em> whenever it
 * leaves a band of {@code stableBand} around the episode's mean. Load levels are grouped in buckets whose bounds
 * grow by {@code loadBucketRatio}, and each bucket keeps its own model, since the curve may depend on the load.
 *
 * <p><b>Following the demand.</b> The pool steps toward {@code ceil(M * (1 + demandMargin)) + 1}, never above the
 * target of the load's model (or, while the load has none, the target of the last model, so a burst does not push
 * the pool past {@code Nmax} before the load is characterized). Growth steps up to half the pool at a time, so a
 * burst is served quickly; a shrink steps by {@code stepLimit}.
 *
 * <p><b>Characterizing a steady load.</b> When an episode has lasted {@code stableWindows}, the pool limits the
 * throughput (requests wait), the demand needs more than {@code minSweepLoad} connections, and the load's bucket
 * has no model:
 * <ol>
 *   <li>if a model of another load predicts the measured lease times within half the regime threshold for
 *       {@code validationWindows} windows, the bucket reuses it; otherwise</li>
 *   <li>the controller sweeps: from {@code M * sweepStartFraction} (a quarter of the load; growing toward the demand already left points
 *       at the smaller sizes) it multiplies the pool by {@code sweepFactor}
 *       every {@code sweepWindowsPerSize} windows, up to {@code M} (more connections than requests cannot help) and
 *       {@code maxSize}, and stops early once the throughput fell more than {@code peakDrop} below the best for
 *       {@code peakDropCount} sizes in a row: the peak, the measured {@code Nmax}, lies behind it. When the first size
 *       was the best, the peak may lie below the start, so the sweep continues downward until the throughput falls
 *       twice again. It then fits the lease-time USL on the bucket's points and sets the target, at most
 *       {@code min(N*, measured Nmax)}; without a usable fit, it holds the best size it measured.</li>
 * </ol>
 * A load change during a sweep aborts it. A saturated lease time 25% off the model, twice in a row, means the
 * workload changed: the bucket's model and points are dropped, and the load is characterized again.
 *
 * <p>Optionally, {@code leaseTimeTargetSeconds} also caps the target at the largest concurrency whose lease time
 * stays within it ({@link UslModel#concurrencyForResponseTime}, Schwartz's Eq. 10). Like the other policies, the
 * controller is a pure decision function with no threads or clock.
 *
 * @author Vlad Mihalcea
 */
public final class UslResponseTimeController implements ScalingPolicy {

    /** Tunable knobs; the defaults are what the experiments use. */
    public record Config(
            int minSize,
            int maxSize,
            SizingTarget target,
            double demandMargin,
            double loadAlpha,
            double stableBand,
            int stableWindows,
            double sweepStartFraction,
            double sweepFactor,
            int sweepWindowsPerSize,
            double peakDrop,
            int peakDropCount,
            int minSweepLoad,
            double loadBucketRatio,
            int validationWindows,
            int minPointsForFit,
            double minRSquared,
            double emaAlpha,
            double regimeRelativeThreshold,
            double saturationSlack,
            double minQueueLength,
            double stableBusyDelta,
            boolean fitDemandLimitedWindows,
            double leaseTimeTargetSeconds,
            int stepLimit,
            int deadBand,
            int maxResizeWindows) {

        /** The point of diminishing returns at {@code eta = 0.1}. */
        public static Config defaults(int minSize, int maxSize) {
            return defaults(minSize, maxSize, SizingTarget.DEFAULT);
        }

        public static Config defaults(int minSize, int maxSize, SizingTarget target) {
            return new Config(minSize, maxSize, target, 0.20, 0.5, 0.15, 4, 0.25, 1.25, 2, 0.03, 2, 16, 1.5, 3, 4,
                    0.80, 0.5, 0.25, 0.10, 1.0, 0.5, true, Double.NaN, 8, 2, 3);
        }

        /** The same configuration with another target. */
        public Config withTarget(SizingTarget newTarget) {
            return new Config(minSize, maxSize, newTarget, demandMargin, loadAlpha, stableBand, stableWindows,
                    sweepStartFraction, sweepFactor, sweepWindowsPerSize, peakDrop, peakDropCount, minSweepLoad,
                    loadBucketRatio, validationWindows, minPointsForFit, minRSquared, emaAlpha,
                    regimeRelativeThreshold, saturationSlack, minQueueLength, stableBusyDelta, fitDemandLimitedWindows,
                    leaseTimeTargetSeconds, stepLimit, deadBand, maxResizeWindows);
        }

        /** The same configuration with a mean lease-time target, in seconds. */
        public Config withLeaseTimeTarget(double seconds) {
            return new Config(minSize, maxSize, target, demandMargin, loadAlpha, stableBand, stableWindows,
                    sweepStartFraction, sweepFactor, sweepWindowsPerSize, peakDrop, peakDropCount, minSweepLoad,
                    loadBucketRatio, validationWindows, minPointsForFit, minRSquared, emaAlpha,
                    regimeRelativeThreshold, saturationSlack, minQueueLength, stableBusyDelta, fitDemandLimitedWindows,
                    seconds, stepLimit, deadBand, maxResizeWindows);
        }
    }

    private enum Mode {FOLLOW, SWEEP}

    enum Load {SATURATED, RESIZING, DEMAND_LIMITED}

    /** The exponentially weighted lease time at one concurrency, with the number of windows behind it. */
    private static final class Point {
        double concurrency;
        double leaseSeconds;
        int windows;
    }

    /** What the controller knows about one load level. */
    private static final class LoadLevel {
        final TreeMap<Integer, Point> points = new TreeMap<>();
        UslFit fit;
        int target = -1;
        int nStar = -1;
        int measuredPeak = -1;
        boolean needsSweep;
        int validOk;
        int validBad;
    }

    private final Config config;
    private final Map<Integer, LoadLevel> levels = new HashMap<>();

    private Mode mode = Mode.FOLLOW;
    private double episodeMean = Double.NaN;
    private int episodeWindows;
    private LoadLevel level;
    private LoadLevel lastModelLevel;
    private int regimeStrikes;
    private WindowSample previous;

    // Sweep state.
    private int sweepSize = -1;
    private int sweepWindows;
    private double sweepSum;
    private int sweepCount;
    private double sweepBest;
    private int sweepBestSize;
    private int sweepDrops;
    private int sweepStart;
    private boolean sweepDown;

    private int sweeps;
    private int reuses;

    public UslResponseTimeController(Config config) {
        this.config = config;
    }

    public UslResponseTimeController(int minSize, int maxSize) {
        this(Config.defaults(minSize, maxSize));
    }

    @Override
    public int decide(WindowSample sample) {
        int current = sample.poolSize();
        Load load = classify(sample);
        previous = sample;
        trackEpisode(sample);

        if (load == Load.RESIZING) {
            if (mode == Mode.SWEEP && ++sweepWindows > config.sweepWindowsPerSize() + config.maxResizeWindows()) {
                return advanceSweep(current); // the pool does not reach this size: move on
            }
            return current;
        }
        if (sample.throughput() <= 0) {
            return current;
        }
        boolean saturated = load == Load.SATURATED;
        double concurrency = concurrencyOf(sample);
        double lease = concurrency / sample.throughput();
        if (saturated) {
            detectRegimeChange(concurrency, lease);
        }
        if (saturated || config.fitDemandLimitedWindows()) {
            learn(level, concurrency, lease);
        }

        if (mode == Mode.SWEEP) {
            if (current == sweepSize) {
                sweepSum += sample.throughput();
                sweepCount++;
            }
            if (++sweepWindows >= config.sweepWindowsPerSize() && sweepCount > 0) {
                return advanceSweep(current);
            }
            return sweepSize;
        }
        return follow(current, saturated, concurrency, lease);
    }

    private int follow(int current, boolean saturated, double concurrency, double lease) {
        int need = demandSize(episodeMean);
        boolean characterized = level.target > 0;
        if (!characterized && saturated && lastModelLevel != null && lastModelLevel != level && !level.needsSweep) {
            validate(concurrency, lease);
        }
        if (!characterized && saturated && episodeWindows >= config.stableWindows()
                && need > config.minSweepLoad() && (lastModelLevel == null || level.needsSweep)) {
            return startSweep();
        }
        int cap = level.target > 0 ? level.target
                : lastModelLevel != null ? lastModelLevel.target : config.maxSize();
        return stepToward(current, clamp(Math.min(need, cap)));
    }

    /** Reuses another load's model when it predicts this load's lease times. */
    private void validate(double concurrency, double lease) {
        double predicted = lastModelLevel.fit.model().responseTimeAt(concurrency);
        if (Math.abs(lease - predicted) / predicted <= config.regimeRelativeThreshold() / 2) {
            if (++level.validOk >= config.validationWindows()) {
                level.fit = lastModelLevel.fit;
                level.target = lastModelLevel.target;
                level.nStar = lastModelLevel.nStar;
                level.measuredPeak = lastModelLevel.measuredPeak;
                reuses++;
            }
        } else if (++level.validBad >= 2) {
            level.needsSweep = true;
        }
    }

    private int startSweep() {
        mode = Mode.SWEEP;
        sweepSize = clamp((int) Math.ceil(episodeMean * config.sweepStartFraction()));
        sweepWindows = 0;
        sweepSum = 0;
        sweepCount = 0;
        sweepBest = 0;
        sweepBestSize = -1;
        sweepDrops = 0;
        sweepStart = sweepSize;
        sweepDown = false;
        return sweepSize;
    }

    /** Records the throughput of the current sweep size and moves to the next size, or ends the sweep. */
    private int advanceSweep(int current) {
        if (sweepCount > 0) {
            double mean = sweepSum / sweepCount;
            if (mean > sweepBest) {
                sweepBest = mean;
                sweepBestSize = sweepSize;
                sweepDrops = 0;
            } else if (mean < (1 - config.peakDrop()) * sweepBest) {
                sweepDrops++;
            } else {
                sweepDrops = 0;
            }
        }
        boolean peakBracketed = sweepDrops >= config.peakDropCount();
        if (!sweepDown) {
            int limit = Math.min(config.maxSize(), (int) Math.ceil(episodeMean));
            if (peakBracketed || sweepSize >= limit) {
                if (sweepBestSize == sweepStart && sweepStart > config.minSize()) {
                    // The first size was the best: the peak may lie below the start, so sweep downward.
                    sweepDown = true;
                    sweepDrops = 0;
                    return nextSweepSize(Math.min(sweepStart - 1, (int) Math.floor(sweepStart / config.sweepFactor())));
                }
                return finishSweep(current);
            }
            return nextSweepSize(Math.min(limit,
                    Math.max(sweepSize + 1, (int) Math.ceil(sweepSize * config.sweepFactor()))));
        }
        if (peakBracketed || sweepSize <= config.minSize()) {
            return finishSweep(current);
        }
        return nextSweepSize(Math.min(sweepSize - 1, (int) Math.floor(sweepSize / config.sweepFactor())));
    }

    private int nextSweepSize(int size) {
        sweepSize = clamp(size);
        sweepWindows = 0;
        sweepSum = 0;
        sweepCount = 0;
        return sweepSize;
    }

    private int finishSweep(int current) {
        mode = Mode.FOLLOW;
        sweeps++;
        // Upward, two drops bracket the peak on its right; downward, on its left (the upward part saw the right).
        boolean peakFound = sweepDrops >= config.peakDropCount() || sweepDown;
        refit(level, peakFound ? sweepBestSize : -1);
        if (level.fit == null && sweepBestSize > 0) {
            // No usable fit: hold the best size the sweep measured, without sweeping this load again until the
            // workload changes.
            level.target = sweepBestSize;
            level.nStar = sweepBestSize;
        }
        level.measuredPeak = peakFound ? sweepBestSize : -1;
        level.needsSweep = false;
        if (level.fit != null) {
            lastModelLevel = level;
        }
        int need = demandSize(episodeMean);
        return clamp(Math.min(need, level.target > 0 ? level.target : current));
    }

    /** What limited the window's throughput; windows without occupancy data count as saturated. */
    Load classify(WindowSample sample) {
        if (!sample.hasOccupancy()) {
            return Load.SATURATED;
        }
        double busy = sample.busyConnections();
        int size = sample.poolSize();
        if (busy > (1.0 + config.saturationSlack()) * size) {
            return Load.RESIZING;
        }
        if (busy >= Math.min((1.0 - config.saturationSlack()) * size, size - 0.5)) {
            return Load.SATURATED;
        }
        if (sample.queueLength() < config.minQueueLength()) {
            return Load.DEMAND_LIMITED;
        }
        boolean stable = previous != null && previous.poolSize() == size && previous.hasOccupancy()
                && Math.abs(busy - previous.busyConnections()) < config.stableBusyDelta();
        return stable ? Load.SATURATED : Load.RESIZING;
    }

    /** Updates the offered load and the episode; a load change aborts a sweep. */
    private void trackEpisode(WindowSample sample) {
        double offered = sample.hasOccupancy() ? sample.busyConnections() + sample.queueLength()
                : sample.poolSize() + sample.queueLength();
        offered = Math.max(offered, 1e-3);
        if (Double.isNaN(episodeMean)
                || Math.abs(offered - episodeMean) > config.stableBand() * Math.max(episodeMean, 1.0)) {
            episodeMean = offered;
            episodeWindows = 1;
            if (mode == Mode.SWEEP) {
                mode = Mode.FOLLOW;
            }
        } else {
            episodeMean = (1 - config.loadAlpha()) * episodeMean + config.loadAlpha() * offered;
            episodeWindows++;
        }
        level = levels.computeIfAbsent(bucket(episodeMean), b -> new LoadLevel());
    }

    private int bucket(double load) {
        return (int) Math.floor(Math.log(Math.max(1.0, load)) / Math.log(config.loadBucketRatio()));
    }

    private static double concurrencyOf(WindowSample sample) {
        return sample.hasOccupancy() ? Math.max(sample.busyConnections(), 1e-3) : sample.poolSize();
    }

    private void learn(LoadLevel into, double concurrency, double lease) {
        if (concurrency < 0.5) {
            return;
        }
        int key = Math.max(1, (int) Math.round(concurrency));
        Point point = into.points.computeIfAbsent(key, k -> new Point());
        if (point.windows == 0) {
            point.concurrency = concurrency;
            point.leaseSeconds = lease;
        } else {
            double alpha = config.emaAlpha();
            point.concurrency = (1 - alpha) * point.concurrency + alpha * concurrency;
            point.leaseSeconds = (1 - alpha) * point.leaseSeconds + alpha * lease;
        }
        point.windows++;
    }

    /** A saturated lease time far from the load's model, twice in a row, means the workload changed. */
    private void detectRegimeChange(double concurrency, double lease) {
        if (level.fit == null) {
            return;
        }
        double predicted = level.fit.model().responseTimeAt(concurrency);
        if (Math.abs(lease - predicted) / predicted > config.regimeRelativeThreshold()) {
            if (++regimeStrikes >= 2) {
                UslFit stale = level.fit;
                for (LoadLevel other : levels.values()) {
                    if (other.fit == stale) {
                        other.fit = null;
                        other.target = -1;
                        other.nStar = -1;
                        other.points.clear();
                        other.needsSweep = true;
                    }
                }
                if (lastModelLevel != null && lastModelLevel.fit == null) {
                    lastModelLevel = null;
                }
                regimeStrikes = 0;
            }
        } else {
            regimeStrikes = 0;
        }
    }

    /** Fits the load's points; {@code measuredPeak > 0} caps the target at the peak the sweep measured. */
    private void refit(LoadLevel into, int measuredPeak) {
        if (into.points.size() < Math.max(3, config.minPointsForFit())) {
            return;
        }
        double[] c = new double[into.points.size()];
        double[] w = new double[into.points.size()];
        double[] weights = new double[into.points.size()];
        int i = 0;
        for (Point point : into.points.values()) {
            c[i] = point.concurrency;
            w[i] = point.leaseSeconds;
            weights[i] = Math.min(point.windows, 5);
            i++;
        }
        try {
            UslFit fit = LeaseTimeFitter.fit(c, w, weights);
            // On a flat top the throughput barely varies, so R^2 is small even for a good fit; accept a fit that is
            // either explanatory (R^2) or simply close (a relative RMS error of at most 5% in throughput).
            if (fit.rSquared() < config.minRSquared() && relativeRmse(fit.model(), c, w) > MAX_RELATIVE_RMSE) {
                return;
            }
            UslModel model = fit.model();
            int nStar = model.optimalPoolSize();
            int boundedNStar = clamp(nStar == Integer.MAX_VALUE ? config.maxSize() : nStar);
            int target = config.target().poolSize(model, config.maxSize());
            if (measuredPeak > 0) {
                target = Math.min(target, measuredPeak);
            }
            if (!Double.isNaN(config.leaseTimeTargetSeconds())) {
                double withinTarget = model.concurrencyForResponseTime(config.leaseTimeTargetSeconds());
                if (!Double.isInfinite(withinTarget)) {
                    target = Math.min(target, Math.max(config.minSize(), (int) Math.floor(withinTarget)));
                }
            }
            into.fit = fit;
            into.nStar = boundedNStar;
            into.target = clamp(Math.min(target, boundedNStar));
        } catch (IllegalArgumentException degenerate) {
            // Not enough spread in the points.
        }
    }

    private static final double MAX_RELATIVE_RMSE = 0.05;

    /** The RMS error of the model's throughput {@code X(c)} against {@code c / W}, relative to the mean throughput. */
    private static double relativeRmse(UslModel model, double[] c, double[] w) {
        double sum = 0;
        double squares = 0;
        for (int i = 0; i < c.length; i++) {
            double measured = c[i] / w[i];
            double error = measured - model.throughputAt(c[i]);
            sum += measured;
            squares += error * error;
        }
        return Math.sqrt(squares / c.length) / (sum / c.length);
    }

    private int demandSize(double offeredLoad) {
        return (int) Math.ceil(offeredLoad * (1.0 + config.demandMargin())) + 1;
    }

    private int stepToward(int current, int target) {
        if (Math.abs(current - target) < config.deadBand()) {
            return current;
        }
        int step = target > current ? Math.max(config.stepLimit(), current / 2) : config.stepLimit();
        int delta = Integer.signum(target - current) * Math.min(step, Math.abs(target - current));
        return clamp(current + delta);
    }

    private int clamp(int size) {
        return Math.max(config.minSize(), Math.min(config.maxSize(), size));
    }

    @Override
    public void reset() {
        levels.clear();
        mode = Mode.FOLLOW;
        episodeMean = Double.NaN;
        episodeWindows = 0;
        level = null;
        lastModelLevel = null;
        regimeStrikes = 0;
        previous = null;
        sweepSize = -1;
        sweeps = 0;
        reuses = 0;
    }

    @Override
    public String id() {
        return "usl-response-time";
    }

    @Override
    public String state() {
        return mode == Mode.SWEEP ? "sweep" : level != null && level.fit != null ? "follow-model" : "follow";
    }

    /** The current load's target (between the plateau start and {@code Nmax}), or -1 while it has no model. */
    public int lastEstimatedPlateauStart() {
        return level == null ? -1 : level.target;
    }

    /** The current load's estimated {@code N*}, or -1 while it has no model. */
    public int lastEstimatedOptimum() {
        return level == null ? -1 : level.nStar;
    }

    /** The current load's target, or -1 while it has no model. */
    public int lastTarget() {
        return lastEstimatedPlateauStart();
    }

    /** The peak the last sweep of the current load measured ({@code Nmax}), or -1 if it found none. */
    public int measuredPeak() {
        return level == null ? -1 : level.measuredPeak;
    }

    /** The current load's lease-time fit, or {@code null}. */
    public UslFit lastFit() {
        return level == null ? null : level.fit;
    }

    /** The sweeps run so far (one per characterized load, plus re-characterizations after a regime change). */
    public int sweeps() {
        return sweeps;
    }

    /** How many load levels reused the model of another load instead of sweeping. */
    public int reuses() {
        return reuses;
    }
}
