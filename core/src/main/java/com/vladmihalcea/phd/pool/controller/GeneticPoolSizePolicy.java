package com.vladmihalcea.phd.pool.controller;

import com.vladmihalcea.phd.pool.usl.SizingTarget;
import com.vladmihalcea.phd.pool.usl.UslModel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * A model-free, search-based pool sizer: a genetic algorithm over the pool size, the search-based baseline of
 * Newsom (2022), run online. Each candidate size is evaluated by holding the pool at it for one window (after
 * {@code settleWindows} windows for the resize to take effect) and reading the throughput; a size measured once
 * is not measured again within a search, so the cost of a search is the number of distinct sizes it measured.
 *
 * <p>The objective matches the controllers' target ({@link SizingTarget}). A size {@code N} must reach a
 * threshold {@code T(N)} relative to the best measurements, {@code X_ref} (the median of the three best, robust to
 * one lucky window) at {@code N_ref} (the smallest of those three sizes: on a flat top, the sizes right of the
 * near-peak cluster are never worth choosing):
 * <ul>
 *   <li>tolerance {@code epsilon}: {@code T(N) = (1 - epsilon) * X_ref}, the plateau start;</li>
 *   <li>elasticity {@code eta}: {@code T(N) = (1 - noiseAllowance) * X_ref * min(1, (N / N_ref)^eta)}, so that a
 *       size qualifies when the
 *       rest of the climb to the best measured size buys at most {@code eta} relative throughput per relative
 *       connection, the model-free counterpart of the point of diminishing returns. It is an arc elasticity
 *       over {@code [N, N_ref]}, which averages the falling point elasticity, so it stops somewhat earlier than the
 *       model-based {@link UslModel#poolSizeAtElasticity} for the same {@code eta}. Every size is measured in one
 *       window, so differences within {@code noiseAllowance} (1%) count as ties; without it, one lucky window
 *       near the peak would be the only size to qualify.</li>
 * </ul>
 * The score is {@code min(1, X(N) / T(N)) - 1e-6 * N}: every size that reaches its threshold ties on the first
 * term, and the smallest one wins. Under light load the throughput is the demand at every size large enough to
 * serve it, so the search lands at the smallest such size.
 *
 * <p>The population starts stratified over {@code [minSize, maxSize]}; every generation keeps the
 * {@code eliteCount} best sizes and breeds the rest by binary tournament, a blend crossover (a random point
 * between the two parents), and a Gaussian mutation of {@code mutationSpread} times the range. After
 * {@code generations} generations, the pool holds the best size. If the throughput at the held size moves by
 * more than {@code regimeRelativeThreshold} for two windows (a workload shift, or a change in the client
 * concurrency), the search restarts from a population centered on the held size.
 *
 * @author Vlad Mihalcea
 */
public final class GeneticPoolSizePolicy implements ScalingPolicy {

    /** Tunable knobs; the defaults are what the experiments use. */
    public record Config(
            int minSize,
            int maxSize,
            SizingTarget target,
            int populationSize,
            int generations,
            int eliteCount,
            double mutationRate,
            double mutationSpread,
            int settleWindows,
            double regimeRelativeThreshold,
            double noiseAllowance,
            long seed) {

        public static Config defaults(int minSize, int maxSize) {
            return defaults(minSize, maxSize, SizingTarget.DEFAULT, 42L);
        }

        /** A tolerance target: the smallest size within {@code tolerance} of the best measured throughput. */
        public static Config defaults(int minSize, int maxSize, double tolerance, long seed) {
            return defaults(minSize, maxSize, new SizingTarget.Tolerance(tolerance), seed);
        }

        public static Config defaults(int minSize, int maxSize, SizingTarget target, long seed) {
            return new Config(minSize, maxSize, target, 8, 5, 2, 0.3, 0.15, 1, 0.25, 0.01, seed);
        }

        /** The same configuration with another seed. */
        public Config withSeed(long newSeed) {
            return new Config(minSize, maxSize, target, populationSize, generations, eliteCount, mutationRate,
                    mutationSpread, settleWindows, regimeRelativeThreshold, noiseAllowance, newSeed);
        }
    }

    private enum Phase {SEARCHING, HOLDING}

    private final Config config;
    private SplittableRandom random;
    private final Map<Integer, Double> measured = new LinkedHashMap<>();

    private Phase phase = Phase.SEARCHING;
    private int[] population;
    private int generation;
    private int pendingSize = -1;
    private int settleLeft;
    private int heldSize = -1;
    private int regimeStrikes;
    private int searches;
    private int evaluations;

    public GeneticPoolSizePolicy(Config config) {
        this.config = config;
        reset();
    }

    @Override
    public int decide(WindowSample sample) {
        int current = sample.poolSize();
        if (settleLeft > 0 && current == pendingSize) {
            settleLeft--;
            return current;
        }
        if (phase == Phase.HOLDING) {
            return hold(sample);
        }
        if (current == pendingSize && sample.throughput() > 0) {
            measured.put(current, sample.throughput());
            evaluations++;
        }
        return nextCandidate();
    }

    private int hold(WindowSample sample) {
        Double reference = measured.get(heldSize);
        if (sample.poolSize() != heldSize || reference == null) {
            return heldSize;
        }
        double change = Math.abs(sample.throughput() - reference) / Math.max(1.0, reference);
        if (change > config.regimeRelativeThreshold()) {
            if (++regimeStrikes >= 2) {
                restartAround(heldSize);
                return nextCandidate();
            }
        } else {
            regimeStrikes = 0;
        }
        return heldSize;
    }

    /** The next unmeasured size of the population, breeding generations until there is one or the search ends. */
    private int nextCandidate() {
        while (true) {
            for (int size : population) {
                if (!measured.containsKey(size)) {
                    return moveTo(size);
                }
            }
            if (++generation >= config.generations()) {
                phase = Phase.HOLDING;
                heldSize = best();
                regimeStrikes = 0;
                pendingSize = -1;
                return heldSize;
            }
            population = breed();
        }
    }

    private int moveTo(int size) {
        pendingSize = size;
        settleLeft = config.settleWindows();
        return size;
    }

    private int[] breed() {
        int[] ranked = rankedPopulation();
        int[] next = new int[config.populationSize()];
        int elites = Math.min(config.eliteCount(), ranked.length);
        System.arraycopy(ranked, 0, next, 0, elites);
        double range = config.maxSize() - config.minSize();
        for (int i = elites; i < next.length; i++) {
            int a = tournament(ranked);
            int b = tournament(ranked);
            double u = random.nextDouble();
            double child = u * a + (1 - u) * b;
            if (random.nextDouble() < config.mutationRate()) {
                child += random.nextGaussian() * config.mutationSpread() * range;
            }
            next[i] = clamp((int) Math.round(child));
        }
        return next;
    }

    private int tournament(int[] ranked) {
        int a = ranked[random.nextInt(ranked.length)];
        int b = ranked[random.nextInt(ranked.length)];
        return score(a) >= score(b) ? a : b;
    }

    /** The population's distinct sizes, best score first. */
    private int[] rankedPopulation() {
        return Arrays.stream(population).distinct().boxed()
                .sorted((x, y) -> Double.compare(score(y), score(x)))
                .mapToInt(Integer::intValue).toArray();
    }

    /** The three best measurements, best first. */
    private List<Map.Entry<Integer, Double>> topThree() {
        return measured.entrySet().stream()
                .sorted(Map.Entry.<Integer, Double>comparingByValue().reversed()).limit(3).toList();
    }

    private double score(int size) {
        Double throughput = measured.get(size);
        if (throughput == null) {
            return Double.NEGATIVE_INFINITY;
        }
        List<Map.Entry<Integer, Double>> top = topThree();
        double reference = top.get(top.size() / 2).getValue(); // the median of the three best
        int referenceSize = top.stream().mapToInt(Map.Entry::getKey).min().orElse(size);
        double threshold = switch (config.target()) {
            case SizingTarget.Tolerance t -> (1 - t.epsilon()) * reference;
            case SizingTarget.Elasticity e -> reference * (1 - config.noiseAllowance())
                    * Math.min(1.0, Math.pow((double) size / referenceSize, e.eta()));
        };
        // Every size that reaches its own threshold ties at 1, so the smallest of them wins; the others rank by how
        // close they came.
        return Math.min(1.0, throughput / threshold) - 1e-6 * size;
    }

    /** The best-scoring measured size: the smallest one that reaches its threshold. */
    private int best() {
        int best = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int size : measured.keySet()) {
            double s = score(size);
            if (s > bestScore) {
                bestScore = s;
                best = size;
            }
        }
        return best < 0 ? config.minSize() : best;
    }

    private void restartAround(int center) {
        measured.clear();
        generation = 0;
        regimeStrikes = 0;
        phase = Phase.SEARCHING;
        searches++;
        int[] next = new int[config.populationSize()];
        double range = config.maxSize() - config.minSize();
        next[0] = clamp(center);
        for (int i = 1; i < next.length; i++) {
            // Half near the held size, half over the whole range, so a large shift is still found.
            next[i] = i % 2 == 1
                    ? clamp((int) Math.round(center + random.nextGaussian() * config.mutationSpread() * range))
                    : stratified(i, next.length);
        }
        population = next;
    }

    private int stratified(int i, int count) {
        double width = (config.maxSize() - config.minSize() + 1) / (double) count;
        return clamp(config.minSize() + (int) Math.floor((i + random.nextDouble()) * width));
    }

    private int clamp(int size) {
        return Math.max(config.minSize(), Math.min(config.maxSize(), size));
    }

    @Override
    public void reset() {
        random = new SplittableRandom(config.seed());
        measured.clear();
        phase = Phase.SEARCHING;
        generation = 0;
        pendingSize = -1;
        settleLeft = 0;
        heldSize = -1;
        regimeStrikes = 0;
        searches = 1;
        evaluations = 0;
        population = new int[config.populationSize()];
        for (int i = 0; i < population.length; i++) {
            population[i] = stratified(i, population.length);
        }
    }

    @Override
    public String id() {
        return "genetic";
    }

    @Override
    public String state() {
        return phase == Phase.HOLDING ? "hold" : "search";
    }

    /** The size held after the last completed search, or -1 while the first search runs. */
    public int heldSize() {
        return phase == Phase.HOLDING ? heldSize : -1;
    }

    /** How many sizes were measured so far, over all searches. */
    public int evaluations() {
        return evaluations;
    }

    /** How many searches started so far (1 plus the restarts). */
    public int searches() {
        return searches;
    }

    /** The throughput measured at each size of the current search, in measurement order. */
    public Map<Integer, Double> measuredThroughput() {
        return new LinkedHashMap<>(measured);
    }

    /** The score of a measured size (package-private, for tests). */
    double scoreOf(int size) {
        return score(size);
    }

    /** The sizes measured by the current search, in measurement order. */
    public List<Integer> measuredSizes() {
        return new ArrayList<>(measured.keySet());
    }
}
