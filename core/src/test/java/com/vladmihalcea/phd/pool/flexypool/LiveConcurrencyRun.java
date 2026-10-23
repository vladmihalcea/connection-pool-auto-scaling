package com.vladmihalcea.phd.pool.flexypool;

import com.vladmihalcea.flexypool.FlexyPoolDataSource;
import com.vladmihalcea.phd.pool.common.util.Database;
import com.vladmihalcea.phd.pool.common.util.Workloads;
import com.vladmihalcea.phd.pool.controller.GeneticPoolSizePolicy;
import com.vladmihalcea.phd.pool.controller.Policies;
import com.vladmihalcea.phd.pool.controller.ScalingPolicy;
import com.vladmihalcea.phd.pool.controller.UslAutoScalingController;
import com.vladmihalcea.phd.pool.controller.UslPlateauController;
import com.vladmihalcea.phd.pool.controller.UslResponseTimeController;
import com.vladmihalcea.phd.pool.controller.WindowSample;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.Recorder;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.SplittableRandom;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * Runs one sizing policy live while the client concurrency changes: a closed loop of client threads with no
 * think time executes a workload through a {@link FlexyPoolDataSource} wrapping HikariCP, DBCP2, or Agroal, and a
 * {@link PolicyConnectionAcquisitionStrategy} resizes the pool every window. The number of active clients
 * follows a profile of phases (for example 8 clients for 2 minutes, then 32, then 128); the inactive clients
 * park, so changing the concurrency starts no threads.
 *
 * <p>Every transaction is timed from the request for a connection to its return (the response time {@code R}
 * of a client), and its acquisition wait separately, into per-window and per-phase HdrHistograms. Every
 * window records the policy's view (pool size, throughput, busy connections, wait queue) next to the measured
 * response time, and the estimates of the USL policies. The phase summaries give the mean pool size, the
 * connection-seconds held, the throughput, and the response-time percentiles per phase.
 *
 * @author Vlad Mihalcea
 */
public final class LiveConcurrencyRun {

    /** The pools the live experiments exercise through FlexyPool. */
    public static final String[] POOLS = {"hikari", "dbcp2", "agroal"};

    /**
     * The pool's acquisition timeout. In a closed loop, a client waits about {@code (M - N) / X} for a connection,
     * which with 128 clients, a small pool, and a TPC-C transaction exceeds a second; a timeout would count as a
     * failed transaction and remove the client from the loop for that attempt, so the timeout is long (10 s, as in
     * the JMH sweep) and the wait itself is measured instead.
     */
    private static final long ACQUISITION_TIMEOUT_MILLIS = Long.getLong("usl.live.acquisitionTimeoutMillis", 10_000);

    /** One phase of the concurrency profile. */
    public record Phase(int clients, long millis) {
    }

    /** Parses a profile such as {@code 8:120,32:120,128:120} (clients:seconds). */
    public static List<Phase> parseProfile(String spec) {
        List<Phase> phases = new ArrayList<>();
        for (String part : spec.split(",")) {
            String[] fields = part.trim().split(":");
            phases.add(new Phase(Integer.parseInt(fields[0].trim()), Long.parseLong(fields[1].trim()) * 1_000L));
        }
        return phases;
    }

    /** One decision window. Times are in milliseconds, sizes in connections. */
    public record WindowRow(double timeSeconds, int phase, int clients, int poolSize, int targetSize,
                            int openConnections, double throughput, double busy, double queue,
                            double acquisitionMeanMillis, double leaseMeanMillis, double responseMeanMillis,
                            double responseP99Millis, int plateauEstimate, int optimumEstimate, String state) {
    }

    /** The outcome of one phase. */
    public record PhaseSummary(int phase, int clients, double seconds, double meanPoolSize,
                               double steadyPoolSize, double connectionSeconds, double throughput,
                               double steadyThroughput, double responseMeanMillis, double responseP50Millis,
                               double responseP99Millis, double responseP999Millis, double acquisitionMeanMillis,
                               long committed, long failures) {
    }

    /** The outcome of a run. */
    public record Result(List<WindowRow> windows, List<PhaseSummary> phases, long committed, long failures,
                         long retries, Map<String, Long> failureTypes) {
    }

    private final Database database;
    private final String poolId;
    private final Workloads.Prepared workload;
    private final Supplier<ScalingPolicy> policy;
    private final PolicyConnectionAcquisitionStrategy.Config config;
    private final List<Phase> profile;
    private final long seed;

    public LiveConcurrencyRun(Database database, String poolId, Workloads.Prepared workload,
                              Supplier<ScalingPolicy> policy, PolicyConnectionAcquisitionStrategy.Config config,
                              List<Phase> profile, long seed) {
        this.database = database;
        this.poolId = poolId;
        this.workload = workload;
        this.policy = policy;
        this.config = config;
        this.profile = profile;
        this.seed = seed;
    }

    public Result run() throws Exception {
        int maxClients = profile.stream().mapToInt(Phase::clients).max().orElse(1);
        AtomicInteger activeClients = new AtomicInteger(profile.getFirst().clients());
        AtomicInteger currentPhase = new AtomicInteger(0);
        AtomicBoolean stop = new AtomicBoolean();
        LongAdder committed = new LongAdder();
        LongAdder failures = new LongAdder();
        Map<String, LongAdder> failureTypes = new ConcurrentHashMap<>();
        Recorder windowResponse = new Recorder(3);
        List<Histogram> phaseResponse = new ArrayList<>();
        List<LongAdder> phaseCommitted = new ArrayList<>();
        List<LongAdder> phaseFailures = new ArrayList<>();
        List<LongAdder> phaseAcquisitionNanos = new ArrayList<>();
        for (int p = 0; p < profile.size(); p++) {
            phaseResponse.add(new org.HdrHistogram.ConcurrentHistogram(3));
            phaseCommitted.add(new LongAdder());
            phaseFailures.add(new LongAdder());
            phaseAcquisitionNanos.add(new LongAdder());
        }

        List<WindowRow> windows = Collections.synchronizedList(new ArrayList<>());
        long startNanos = System.nanoTime();
        Histogram[] recycled = new Histogram[1];
        java.util.function.Consumer<PolicyConnectionAcquisitionStrategy.Decision> listener = decision -> {
                    recycled[0] = windowResponse.getIntervalHistogram(recycled[0]);
                    WindowSample s = decision.sample();
                    ScalingPolicy p = currentPolicy[0];
                    windows.add(new WindowRow((decision.nanoTime() - startNanos) / 1e9, currentPhase.get(),
                            activeClients.get(), s.poolSize(), decision.targetSize(), decision.openConnections(),
                            s.throughput(), s.busyConnections(), s.queueLength(), s.meanAcquisitionNanos() / 1e6,
                            s.leaseNanos() / 1e6,
                            recycled[0].getTotalCount() == 0 ? Double.NaN : recycled[0].getMean() / 1e6,
                            recycled[0].getTotalCount() == 0 ? Double.NaN
                                    : recycled[0].getValueAtPercentile(99) / 1e6,
                            plateauEstimate(p), optimumEstimate(p), p == null ? "" : p.state()));
                };
        PolicyPool pool = PolicyPool.open(database, poolId, config, policy, listener, ACQUISITION_TIMEOUT_MILLIS);
        DataSource flexyPool = pool.dataSource();
        currentPolicy[0] = pool.policy();

        List<Thread> clients = new ArrayList<>(maxClients);
        try {
            for (int t = 0; t < maxClients; t++) {
                final int index = t;
                Thread client = Thread.ofPlatform().daemon().name("client-" + t).start(() -> {
                    SplittableRandom random = new SplittableRandom(seed * 10_007L + index);
                    while (!stop.get()) {
                        if (index >= activeClients.get()) {
                            LockSupport.parkNanos(5_000_000L);
                            continue;
                        }
                        int phase = currentPhase.get();
                        long start = System.nanoTime();
                        try (Connection connection = flexyPool.getConnection()) {
                            phaseAcquisitionNanos.get(phase).add(System.nanoTime() - start);
                            workload.transaction().run(connection, random);
                        } catch (Exception e) {
                            failures.increment();
                            failureTypes.computeIfAbsent(failureType(e), k -> new LongAdder()).increment();
                            phaseFailures.get(phase).increment();
                            continue;
                        }
                        long response = System.nanoTime() - start;
                        windowResponse.recordValue(response);
                        phaseResponse.get(phase).recordValue(response);
                        committed.increment();
                        phaseCommitted.get(phase).increment();
                    }
                });
                clients.add(client);
            }
            long[] phaseStart = new long[profile.size()];
            long[] phaseEnd = new long[profile.size()];
            for (int p = 0; p < profile.size(); p++) {
                activeClients.set(profile.get(p).clients());
                currentPhase.set(p);
                phaseStart[p] = System.nanoTime();
                Thread.sleep(profile.get(p).millis());
                phaseEnd[p] = System.nanoTime();
            }
            stop.set(true);
            for (Thread client : clients) {
                client.join(30_000);
            }
            pool.stopDecisions();

            List<WindowRow> snapshot;
            synchronized (windows) {
                snapshot = new ArrayList<>(windows);
            }
            List<PhaseSummary> phases = new ArrayList<>();
            for (int p = 0; p < profile.size(); p++) {
                double seconds = (phaseEnd[p] - phaseStart[p]) / 1e9;
                phases.add(summarize(p, seconds, snapshot, phaseResponse.get(p), phaseCommitted.get(p).sum(),
                        phaseFailures.get(p).sum(), phaseAcquisitionNanos.get(p).sum()));
            }
            Map<String, Long> types = new TreeMap<>();
            failureTypes.forEach((type, count) -> types.put(type, count.sum()));
            return new Result(snapshot, phases, committed.sum(), failures.sum(), workload.retries().getAsLong(),
                    types);
        } finally {
            stop.set(true);
            pool.close();
        }
    }

    // The listener runs on the strategy's thread and needs the policy the factory created.
    private final ScalingPolicy[] currentPolicy = new ScalingPolicy[1];

    private PhaseSummary summarize(int p, double seconds, List<WindowRow> windows, Histogram response,
                                   long committed, long failures, long acquisitionNanos) {
        List<WindowRow> inPhase = windows.stream().filter(w -> w.phase() == p).toList();
        double meanPool = inPhase.stream().mapToInt(WindowRow::poolSize).average().orElse(Double.NaN);
        List<WindowRow> steady = inPhase.subList(inPhase.size() / 2, inPhase.size());
        double steadyPool = steady.stream().mapToInt(WindowRow::poolSize).average().orElse(Double.NaN);
        double steadyThroughput = steady.stream().mapToDouble(WindowRow::throughput).average().orElse(Double.NaN);
        double windowSeconds = config.windowMillis() / 1000.0;
        double connectionSeconds = inPhase.stream()
                .mapToDouble(w -> (w.openConnections() >= 0 ? w.openConnections() : w.poolSize()) * windowSeconds)
                .sum();
        long count = response.getTotalCount();
        return new PhaseSummary(p, profile.get(p).clients(), seconds, meanPool, steadyPool, connectionSeconds,
                committed / seconds, steadyThroughput,
                count == 0 ? Double.NaN : response.getMean() / 1e6,
                count == 0 ? Double.NaN : response.getValueAtPercentile(50) / 1e6,
                count == 0 ? Double.NaN : response.getValueAtPercentile(99) / 1e6,
                count == 0 ? Double.NaN : response.getValueAtPercentile(99.9) / 1e6,
                committed == 0 ? Double.NaN : acquisitionNanos / 1e6 / committed, committed, failures);
    }

    /** The exception class, with the SQL state for an {@link SQLException}, for example {@code SQLException[40001]}. */
    private static String failureType(Exception e) {
        Throwable root = e;
        while (root.getCause() != null && !(root instanceof SQLException)) {
            root = root.getCause();
        }
        String name = root.getClass().getSimpleName();
        return root instanceof SQLException sql && sql.getSQLState() != null ? name + "[" + sql.getSQLState() + "]" : name;
    }

    private static int plateauEstimate(ScalingPolicy named) {
        ScalingPolicy policy = Policies.unwrap(named);
        if (policy instanceof UslPlateauController c) {
            return c.lastEstimatedPlateauStart();
        }
        if (policy instanceof UslResponseTimeController c) {
            return c.lastEstimatedPlateauStart();
        }
        if (policy instanceof GeneticPoolSizePolicy g) {
            return g.heldSize();
        }
        return -1;
    }

    private static int optimumEstimate(ScalingPolicy named) {
        ScalingPolicy policy = Policies.unwrap(named);
        if (policy instanceof UslPlateauController c) {
            return c.lastEstimatedOptimum();
        }
        if (policy instanceof UslResponseTimeController c) {
            return c.lastEstimatedOptimum();
        }
        if (policy instanceof UslAutoScalingController c) {
            return c.lastEstimatedOptimum();
        }
        return -1;
    }
}
