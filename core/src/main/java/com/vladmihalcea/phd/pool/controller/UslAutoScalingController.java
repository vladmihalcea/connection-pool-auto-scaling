package com.vladmihalcea.phd.pool.controller;

import com.vladmihalcea.phd.pool.usl.UslFit;
import com.vladmihalcea.phd.pool.usl.UslFitter;
import com.vladmihalcea.phd.pool.usl.UslModel;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The paper's contribution: a control-theoretic connection-pool auto-scaler driven by an <em>online</em>
 * Universal Scalability Law estimator. It is a saturated proportional controller with a dead-band, whose
 * set-point {@code N*} is not fixed but continuously re-estimated by fitting the USL to the pool's own
 * throughput-vs-size history.
 *
 * <p>Each observation window the controller:
 * <ol>
 *   <li><b>Observes</b> the window's (pool size, throughput).</li>
 *   <li><b>Learns</b> — folds the point into an exponentially-weighted per-size throughput history so
 *       recent behaviour dominates; refits the USL once at least {@code minPointsForFit} distinct sizes
 *       are known.</li>
 *   <li><b>Decides</b> — computes {@code N* = floor(sqrt((1-sigma)/kappa))} from the fit; if the fit is
 *       trustworthy ({@code R^2 >= minRSquared}) and the pool is outside the dead-band around {@code N*},
 *       moves toward it by at most {@code stepLimit} connections (actuator saturation).</li>
 *   <li><b>Forgets</b> — when observed throughput departs from the current model's prediction for two
 *       consecutive windows (a regime change), flushes the history so the estimator re-learns the new
 *       operating curve. This is what lets the controller re-track a moved optimum — including
 *       <em>shrinking</em> the pool, which the reactive baseline cannot do.</li>
 * </ol>
 *
 * <p>Until three distinct sizes have been seen the controller runs a bounded exploration phase (a
 * design-of-experiments bootstrap), stepping the pool upward so the first fit has points spanning both
 * sides of the curve. The controller is deliberately a pure decision function ({@link #decide}) with no
 * threads or clock of its own, so it is unit-testable against the simulator and equally embeddable behind
 * a {@code ScheduledExecutorService} tick in a live FlexyPool deployment.
 *
 * @author Vlad Mihalcea
 */
public final class UslAutoScalingController implements ScalingPolicy {

    /** Tunable knobs; the defaults are what the experiments use. */
    public record Config(
            int minSize,
            int maxSize,
            int stepLimit,
            int explorationStep,
            int deadBand,
            int minPointsForFit,
            double minRSquared,
            double emaAlpha,
            double regimeRelativeThreshold) {

        public static Config defaults(int minSize, int maxSize) {
            return new Config(minSize, maxSize, 8, 6, 2, 4, 0.80, 0.5, 0.25);
        }
    }

    private enum Phase {EXPLORING, TRACKING}

    private final Config config;

    // A fixed ladder of probe sizes spanning [minSize, maxSize] so the bootstrap (and every post-regime
    // re-exploration) collects points on both sides of the curve — essential for the fit to locate an
    // optimum that may be well below the current operating point.
    private final int[] probeLadder;

    // Per-size exponentially-weighted throughput history (the estimator's memory).
    private final Map<Integer, Double> throughputBySize = new LinkedHashMap<>();

    private Phase phase = Phase.EXPLORING;
    private UslModel lastModel;
    private UslFit lastFit;
    private int regimeStrikes;
    private int lastNStar = -1;

    public UslAutoScalingController(Config config) {
        this.config = config;
        this.probeLadder = buildProbeLadder(config.minSize(), config.maxSize());
    }

    /**
     * A spread of probe sizes across the range: the two ends plus geometric-ish interior points, so the
     * bootstrap fit spans low and high concurrency.
     */
    private static int[] buildProbeLadder(int minSize, int maxSize) {
        double[] fractions = {0.0, 0.08, 0.16, 0.33, 0.66, 1.0};
        java.util.TreeSet<Integer> ladder = new java.util.TreeSet<>();
        for (double f : fractions) {
            int size = (int) Math.round(minSize + f * (maxSize - minSize));
            ladder.add(Math.max(minSize, Math.min(maxSize, size)));
        }
        int[] result = new int[ladder.size()];
        int i = 0;
        for (int size : ladder) {
            result[i++] = size;
        }
        return result;
    }

    public UslAutoScalingController(int minSize, int maxSize) {
        this(Config.defaults(minSize, maxSize));
    }

    @Override
    public int decide(WindowSample sample) {
        learn(sample);
        detectRegimeChange(sample);

        int current = sample.poolSize();

        // Bootstrap / re-exploration: walk the probe ladder until it is fully sampled.
        if (phase == Phase.EXPLORING) {
            int probe = nextUnsampledProbe();
            if (probe > 0) {
                return clamp(probe);
            }
            phase = Phase.TRACKING; // ladder complete — enough spread to fit
        }
        if (throughputBySize.size() < config.minPointsForFit()) {
            int probe = nextUnsampledProbe();
            return probe > 0 ? clamp(probe) : current;
        }
        return track(current);
    }

    /**
     * Folds the observed throughput into the per-size EW history.
     */
    private void learn(WindowSample sample) {
        if (sample.throughput() <= 0) {
            return;
        }
        throughputBySize.merge(sample.poolSize(), sample.throughput(),
                (oldValue, obs) -> (1 - config.emaAlpha()) * oldValue + config.emaAlpha() * obs);
    }

    /**
     * Compares the freshest observation against the last accepted model; two consecutive large
     * deviations flush the estimator so it re-learns the new operating curve.
     */
    private void detectRegimeChange(WindowSample sample) {
        if (lastModel == null) {
            return;
        }
        double predicted = lastModel.throughputAt(sample.poolSize());
        double relativeError = Math.abs(sample.throughput() - predicted) / Math.max(1.0, predicted);
        if (relativeError > config.regimeRelativeThreshold()) {
            if (++regimeStrikes >= 2) {
                throughputBySize.clear();
                throughputBySize.put(sample.poolSize(), sample.throughput());
                phase = Phase.EXPLORING;
                lastModel = null;
                lastFit = null;
                regimeStrikes = 0;
                lastNStar = -1;
            }
        } else {
            regimeStrikes = 0;
        }
    }

    /**
     * @return the first probe-ladder size not yet in the history, or -1 if the ladder is fully sampled.
     */
    private int nextUnsampledProbe() {
        for (int probe : probeLadder) {
            if (!throughputBySize.containsKey(probe)) {
                return probe;
            }
        }
        return -1;
    }

    private int track(int current) {
        double[] sizes = new double[throughputBySize.size()];
        double[] tput = new double[throughputBySize.size()];
        int i = 0;
        for (Map.Entry<Integer, Double> e : throughputBySize.entrySet()) {
            sizes[i] = e.getKey();
            tput[i] = e.getValue();
            i++;
        }

        UslFit fit;
        try {
            fit = UslFitter.fit(sizes, tput);
        } catch (RuntimeException fitFailure) {
            return current; // singular fit despite a full ladder — hold and let the EMA settle
        }

        if (fit.rSquared() < config.minRSquared()) {
            return current; // untrustworthy fit — hold rather than chase noise
        }

        lastFit = fit;
        lastModel = fit.model();
        int nStar = clamp(boundedNStar(fit.model()));
        lastNStar = nStar;

        if (Math.abs(current - nStar) < config.deadBand()) {
            return current; // inside the dead-band: hold to avoid actuator chatter
        }
        int delta = Integer.signum(nStar - current) * Math.min(config.stepLimit(), Math.abs(nStar - current));
        return clamp(current + delta);
    }

    private int boundedNStar(UslModel model) {
        int nStar = model.optimalPoolSize();
        if (nStar == Integer.MAX_VALUE) {
            return config.maxSize(); // no retrograde region within reach — push to the ceiling
        }
        return nStar;
    }

    private int clamp(int size) {
        return Math.max(config.minSize(), Math.min(config.maxSize(), size));
    }

    @Override
    public void reset() {
        throughputBySize.clear();
        phase = Phase.EXPLORING;
        lastModel = null;
        lastFit = null;
        regimeStrikes = 0;
        lastNStar = -1;
    }

    @Override
    public String id() {
        return "usl";
    }

    /** The most recent estimated optimum (or -1 before the first accepted fit) — for logging/figures. */
    public int lastEstimatedOptimum() {
        return lastNStar;
    }

    /** The most recent accepted fit (or {@code null}) — for logging/figures. */
    public UslFit lastFit() {
        return lastFit;
    }
}
