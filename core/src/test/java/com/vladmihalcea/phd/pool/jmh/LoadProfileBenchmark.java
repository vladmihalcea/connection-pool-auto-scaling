package com.vladmihalcea.phd.pool.jmh;

import com.vladmihalcea.phd.pool.common.util.AbstractBenchmarkTest;
import com.vladmihalcea.phd.pool.common.util.Database;
import com.vladmihalcea.phd.pool.common.util.Workloads;
import com.vladmihalcea.phd.pool.controller.GeneticPoolSizePolicy;
import com.vladmihalcea.phd.pool.controller.Policies;
import com.vladmihalcea.phd.pool.controller.ScalingPolicy;
import com.vladmihalcea.phd.pool.controller.UslAutoScalingController;
import com.vladmihalcea.phd.pool.controller.UslPlateauController;
import com.vladmihalcea.phd.pool.controller.UslResponseTimeController;
import com.vladmihalcea.phd.pool.flexypool.PolicyConnectionAcquisitionStrategy;
import com.vladmihalcea.phd.pool.flexypool.PolicyPool;
import com.vladmihalcea.phd.pool.usl.SizingTarget;
import com.vladmihalcea.phd.pool.util.CsvWriter;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.Recorder;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.IterationParams;
import org.openjdk.jmh.runner.IterationType;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * L4: every sizing policy under three load profiles, at the JMH rigor of the dense sweep (10 forks), so that the
 * measured throughput and response time can be matched against the USL. The load is the number of clients that
 * want a connection at once, {@code M}:
 * <ul>
 *   <li>{@code increasing}: {@code M} steps through {@code usl.profile.levels} (8, 16, 32, 64, 128), holding each
 *       level for {@code usl.profile.itersPerLevel} measurement iterations (4);</li>
 *   <li>{@code steady}: {@code M = usl.profile.steadyClients} (64) for the same number of iterations;</li>
 *   <li>{@code shrinking}: the levels in reverse.</li>
 * </ul>
 * JMH runs a fixed number of threads (the largest level); a gate whose permits follow the current level admits
 * exactly {@code M} of them, and the others wait at the gate, outside the pool, so they are not part of the offered
 * load. The warm-up iterations run at the profile's first level. The pool is wrapped in FlexyPool and sized by the
 * {@code policy} every second ({@link PolicyPool}), from a cold start, so the first iterations of each level show
 * the policy adapting and the last ones its steady state.
 *
 * <p>JMH reports the throughput of every iteration (in the JSON results). Each measurement iteration also appends
 * one row to {@link #iterationsCsv()}: the level {@code M}, the mean pool size and open connections, the throughput,
 * the busy connections and wait queue, the mean acquisition and lease times, the response-time percentiles (from
 * the request for a connection to its return), Little's Law's prediction of the mean response time
 * ({@code M / X}), and the policy's state and estimates. Matched with the USL:
 * <ul>
 *   <li>a static pool larger than every level ({@code static-96}) under {@code increasing} load traces the USL in
 *       its load form, {@code X(M)}, which the fit of the dense sweep (K1) predicts;</li>
 *   <li>under {@code steady} load, a policy's settled size {@code N} predicts {@code X(min(M, N))} and a lease time
 *       {@code W(N)} (Schwartz's Eq. 6), and the response time {@code M / X};</li>
 *   <li>under {@code shrinking} load, the policies should give back connections while the throughput follows
 *       {@code X(M)}.</li>
 * </ul>
 *
 * @author Vlad Mihalcea
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
public class LoadProfileBenchmark {

    static final List<Integer> LEVELS = Arrays.stream(System.getProperty("usl.profile.levels", "8,16,32,64,128")
            .split(",")).map(String::trim).map(Integer::valueOf).toList();
    static final int ITERS_PER_LEVEL = Integer.getInteger("usl.profile.itersPerLevel", 4);
    static final int STEADY_CLIENTS = Integer.getInteger("usl.profile.steadyClients", 64);
    private static final SizingTarget TARGET = SizingTarget.parse(System.getProperty("usl.profile.target", "eta:0.1"));
    private static final int MIN_POOL = Integer.getInteger("usl.profile.minPool", 2);
    private static final int MAX_POOL = Integer.getInteger("usl.profile.maxPool", 96);
    private static final long ACQUISITION_TIMEOUT_MILLIS = 10_000;

    /** The per-iteration rows, next to the JMH results of the same run. */
    public static Path iterationsCsv() {
        return CsvWriter.metricsPath("jmh-results",
                AbstractBenchmarkTest.resultsName(LoadProfileBenchmark.class) + "-iterations.csv");
    }

    /** The number of threads JMH must run: the largest level of any profile. */
    static int maxClients() {
        return Math.max(STEADY_CLIENTS, Collections.max(LEVELS));
    }

    /** The load of measurement iteration {@code i} (from 0) of a profile. */
    static int clientsAt(String profile, int iteration) {
        int level = Math.min(iteration / ITERS_PER_LEVEL, LEVELS.size() - 1);
        return switch (profile) {
            case "increasing" -> LEVELS.get(level);
            case "shrinking" -> LEVELS.get(LEVELS.size() - 1 - level);
            case "steady" -> STEADY_CLIENTS;
            default -> throw new IllegalArgumentException("Unknown profile: " + profile);
        };
    }

    /** A semaphore whose number of permits can be set, so it admits exactly the clients of the current level. */
    static final class Gate extends Semaphore {
        private int permits;

        Gate(int permits) {
            super(permits, true);
            this.permits = permits;
        }

        synchronized void setPermits(int target) {
            if (target > permits) {
                release(target - permits);
            } else if (target < permits) {
                reducePermits(permits - target);
            }
            permits = target;
        }
    }

    @State(Scope.Benchmark)
    public static class ProfileState {

        @Param({"postgresql"})
        public String database;

        @Param({"hikari"})
        public String pool;

        @Param({"tpcc", "oltpReadWrite"})
        public String workload;

        @Param({"increasing", "steady", "shrinking"})
        public String profile;

        @Param({"static-10", "static-96", "pid", "genetic", "usl-optimum", "usl-plateau", "usl-response-time",
                "usl-response-time@eps:0.01"})
        public String policy;

        private Workloads.Prepared prepared;
        private PolicyPool policyPool;
        private Gate gate;
        private final Recorder response = new Recorder(3);
        private final LongAdder acquisitionNanos = new LongAdder();
        private final LongAdder committed = new LongAdder();
        private final List<PolicyConnectionAcquisitionStrategy.Decision> decisions =
                Collections.synchronizedList(new ArrayList<>());
        private final String runId = UUID.randomUUID().toString().substring(0, 8);
        private Histogram recycled;
        private volatile boolean measuring;
        private int measurementIteration = -1;
        private int clients;
        private long iterationStartNanos;

        @Setup(Level.Trial)
        public void setUp() {
            Database db = Database.valueOf(database.toUpperCase(Locale.ROOT));
            prepared = Workloads.prepare(db, workload);
            PolicyConnectionAcquisitionStrategy.Config config = new PolicyConnectionAcquisitionStrategy.Config(
                    MIN_POOL, MAX_POOL, Policies.initialSize(policy, MIN_POOL), 1_000L, 2, true);
            long seed = runId.hashCode();
            policyPool = PolicyPool.open(db, pool, config,
                    () -> Policies.create(policy, MIN_POOL, MAX_POOL, TARGET, seed, 2_000_000L),
                    decision -> {
                        if (measuring) {
                            decisions.add(decision);
                        }
                    }, ACQUISITION_TIMEOUT_MILLIS);
            gate = new Gate(clientsAt(profile, 0));
        }

        @Setup(Level.Iteration)
        public void startIteration(IterationParams iteration) {
            measuring = iteration.getType() == IterationType.MEASUREMENT;
            if (measuring) {
                measurementIteration++;
            }
            clients = clientsAt(profile, Math.max(0, measurementIteration));
            gate.setPermits(clients);
            decisions.clear();
            committed.reset();
            acquisitionNanos.reset();
            recycled = response.getIntervalHistogram(recycled);
            iterationStartNanos = System.nanoTime();
        }

        void record(long acquireNanos, long responseNanos) {
            acquisitionNanos.add(acquireNanos);
            response.recordValue(responseNanos);
            committed.increment();
        }

        @TearDown(Level.Iteration)
        public void endIteration() {
            if (!measuring) {
                return;
            }
            double seconds = (System.nanoTime() - iterationStartNanos) / 1e9;
            recycled = response.getIntervalHistogram(recycled);
            List<PolicyConnectionAcquisitionStrategy.Decision> windows;
            synchronized (decisions) {
                windows = new ArrayList<>(decisions);
            }
            long count = committed.sum();
            double tps = count / seconds;
            double poolMean = windows.stream().mapToInt(d -> d.sample().poolSize()).average().orElse(Double.NaN);
            double openMean = windows.stream().mapToInt(PolicyConnectionAcquisitionStrategy.Decision::openConnections)
                    .filter(open -> open >= 0).average().orElse(Double.NaN);
            double busyMean = windows.stream().mapToDouble(d -> d.sample().busyConnections()).average().orElse(Double.NaN);
            double queueMean = windows.stream().mapToDouble(d -> d.sample().queueLength()).average().orElse(Double.NaN);
            ScalingPolicy current = Policies.unwrap(policyPool.policy());
            appendRow(String.join(",", runId, database, pool, workload, profile, policy, TARGET.id(),
                    String.valueOf(measurementIteration), String.valueOf(clients), f(seconds), f(tps), f(poolMean),
                    f(openMean), f(busyMean), f(queueMean),
                    f(count == 0 ? Double.NaN : acquisitionNanos.sum() / 1e6 / count),
                    f(tps == 0 ? Double.NaN : busyMean / tps * 1e3),
                    f(millis(recycled.getTotalCount() == 0 ? Double.NaN : recycled.getMean())),
                    f(millis(recycled.getValueAtPercentile(50))), f(millis(recycled.getValueAtPercentile(90))),
                    f(millis(recycled.getValueAtPercentile(99))), f(millis(recycled.getValueAtPercentile(99.9))),
                    f(tps == 0 ? Double.NaN : clients / tps * 1e3), policyPool.policy().state(),
                    String.valueOf(targetEstimate(current)), String.valueOf(optimumEstimate(current))));
        }

        @TearDown(Level.Trial)
        public void tearDown() throws Exception {
            if (policyPool != null) {
                policyPool.close();
            }
        }

        private static void appendRow(String row) {
            Path csv = iterationsCsv();
            try {
                Files.createDirectories(csv.getParent());
                StringBuilder text = new StringBuilder();
                if (!Files.exists(csv)) {
                    text.append("run,database,pool,workload,profile,policy,target,iteration,clients,seconds,tps,"
                            + "pool_mean,open_mean,busy_mean,queue_mean,acquire_mean_ms,lease_mean_ms,"
                            + "response_mean_ms,response_p50_ms,response_p90_ms,response_p99_ms,response_p999_ms,"
                            + "little_response_ms,state,target_estimate,optimum_estimate\n");
                }
                text.append(row).append('\n');
                Files.writeString(csv, text, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private static double millis(double nanos) {
            return nanos / 1e6;
        }

        private static String f(double value) {
            return Double.isNaN(value) ? "" : String.format(Locale.ROOT, "%.4f", value);
        }

        private static int targetEstimate(ScalingPolicy policy) {
            if (policy instanceof UslResponseTimeController c) {
                return c.lastTarget();
            }
            if (policy instanceof UslPlateauController c) {
                return c.lastEstimatedPlateauStart();
            }
            if (policy instanceof GeneticPoolSizePolicy g) {
                return g.heldSize();
            }
            return -1;
        }

        private static int optimumEstimate(ScalingPolicy policy) {
            if (policy instanceof UslResponseTimeController c) {
                return c.lastEstimatedOptimum();
            }
            if (policy instanceof UslPlateauController c) {
                return c.lastEstimatedOptimum();
            }
            if (policy instanceof UslAutoScalingController c) {
                return c.lastEstimatedOptimum();
            }
            return -1;
        }
    }

    @State(Scope.Thread)
    public static class Rng {
        final SplittableRandom random = new SplittableRandom(System.nanoTime());
    }

    @Benchmark
    public void transaction(ProfileState state, Rng rng) throws Exception {
        // Only the clients of the current level pass the gate; the others are not part of the offered load.
        state.gate.acquireUninterruptibly();
        try {
            long start = System.nanoTime();
            try (Connection connection = state.policyPool.dataSource().getConnection()) {
                long acquired = System.nanoTime();
                state.prepared.transaction().run(connection, rng.random);
                long end = System.nanoTime();
                if (state.measuring) {
                    state.record(acquired - start, end - start);
                }
            }
        } finally {
            state.gate.release();
        }
    }
}
