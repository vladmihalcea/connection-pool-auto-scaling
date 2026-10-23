package com.vladmihalcea.phd.pool.experiment;

import com.vladmihalcea.phd.pool.common.util.Database;
import com.vladmihalcea.phd.pool.common.util.Workloads;
import com.vladmihalcea.phd.pool.controller.Policies;
import com.vladmihalcea.phd.pool.controller.ScalingPolicy;
import com.vladmihalcea.phd.pool.controller.WindowSample;
import com.vladmihalcea.phd.pool.flexypool.PolicyConnectionAcquisitionStrategy;
import com.vladmihalcea.phd.pool.flexypool.PolicyPool;
import com.vladmihalcea.phd.pool.usl.SizingTarget;
import com.vladmihalcea.phd.pool.usl.UslModel;
import com.vladmihalcea.phd.pool.util.CsvWriter;
import org.HdrHistogram.ConcurrentHistogram;
import org.HdrHistogram.Histogram;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Experiment K4 of the plateau-start article (RQ3, H3): what the saved connections are worth when several application
 * instances share one database server and its {@code max_connections}. The instances run in one JVM, each with its
 * own FlexyPool-wrapped HikariCP pool, its own sizing policy, and its own closed loop of {@code usl.shared.clients}
 * (64) clients without think time, like application servers of 64 worker threads. They join one at a time, one per
 * phase of {@code usl.shared.phaseSeconds} (60 s), up to {@code usl.shared.instances} (6), so the last phases ask
 * more connections of the server than it admits under {@code N*} sizing.
 * <p>
 * A {@link Calibration} sweep first measures the single-instance curve; the static policies {@code static-nstar},
 * {@code static-neta}, and {@code static-n005} are its {@code N*}, point of diminishing returns, and 95% plateau
 * start, next to HikariCP's default {@code static-10} and the adaptive controllers ({@code usl.shared.policies}),
 * every instance of a run under the same policy. Since the database sees only connections, the instances' aggregate
 * throughput should follow the database's curve in the <em>total</em> number of busy connections, so each phase also
 * reports the calibrated curve at that total (extrapolated past the largest calibrated size), which tells whether
 * per-instance sizing at {@code N*} over-subscribes the server long before it reaches {@code max_connections}.
 * <p>
 * Per phase: the instances, their total configured and open connections against the server's {@code max_connections},
 * the aggregate throughput (the steady second half of the phase), the calibrated prediction, the failed
 * acquisitions (a pool that the server refuses connections times out), the response-time mean and p99 over all
 * instances, and the smallest and largest throughput of an instance (fairness). Outputs
 * ({@code metrics/SharedDatabase/}): {@code <db>-<workload>-calibration.csv}, {@code -windows.csv} (per instance and
 * window), {@code -phases.csv}.
 *
 * @author Vlad Mihalcea
 */
public class SharedDatabaseExperimentTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(SharedDatabaseExperimentTest.class);

    private static final String FIGURE = "SharedDatabase";
    private static final List<String> DATABASES = OpenArrivalExperimentTest.list("usl.shared.databases",
            "postgresql,mysql");
    private static final List<String> WORKLOADS = OpenArrivalExperimentTest.list("usl.shared.workloads",
            "readMostly,oltpReadWrite");
    private static final List<String> POLICIES = OpenArrivalExperimentTest.list("usl.shared.policies",
            "static-nstar,static-neta,static-10,usl-optimum,usl-response-time");
    private static final int INSTANCES = Integer.getInteger("usl.shared.instances", 6);
    private static final long PHASE_SECONDS = Long.getLong("usl.shared.phaseSeconds", 60);
    private static final int CLIENTS = Integer.getInteger("usl.shared.clients", 64);
    private static final int REPS = Integer.getInteger("usl.reps", 3);
    private static final int MAX_SIZE = Integer.getInteger("usl.shared.maxSize", 96);
    private static final long WINDOW_MILLIS = 1_000;
    private static final long ACQUISITION_TIMEOUT_MILLIS = 10_000;
    private static final long CALIBRATION_WARMUP_MILLIS = Long.getLong("usl.calibration.warmupMillis", 2_000);
    private static final long CALIBRATION_MILLIS = Long.getLong("usl.calibration.measureMillis", 5_000);

    @Test
    public void measureSharedDatabase() throws Exception {
        boolean ranAny = false;
        for (String databaseId : DATABASES) {
            Database database = Database.valueOf(databaseId.toUpperCase(Locale.ROOT));
            if (!database.isAvailable()) {
                LOGGER.warn("Skipping {}: not available", databaseId);
                continue;
            }
            int maxConnections = maxConnections(database);
            for (String workloadName : WORKLOADS) {
                ranAny = true;
                run(database, workloadName, maxConnections);
            }
        }
        Assumptions.assumeTrue(ranAny, "No database available");
    }

    private void run(Database database, String workloadName, int maxConnections) throws Exception {
        String base = database.id() + "-" + workloadName;
        Workloads.Prepared workload = Workloads.prepare(database, workloadName);
        Workloads.settle(database);
        Workloads.warmUp(database, workload);
        Calibration.Result calibration = Calibration.run(database, "hikari", workload, Calibration.SIZES, MAX_SIZE,
                CALIBRATION_WARMUP_MILLIS, CALIBRATION_MILLIS, 23L);
        OpenArrivalExperimentTest.writeCalibration(CsvWriter.metricsPath(FIGURE, base + "-calibration.csv"),
                calibration);
        try (CsvWriter windows = new CsvWriter(CsvWriter.metricsPath(FIGURE, base + "-windows.csv"),
                "rep", "policy", "instance", "time_s", "phase", "pool_size", "target_size", "open_connections",
                "throughput", "busy", "queue", "state");
             CsvWriter phases = new CsvWriter(CsvWriter.metricsPath(FIGURE, base + "-phases.csv"),
                     "rep", "policy", "resolved", "phase", "instances", "max_connections", "configured_connections",
                     "open_connections", "busy_connections", "throughput", "predicted_throughput",
                     "failed_acquisitions", "response_mean_ms", "response_p99_ms", "instance_throughput_min",
                     "instance_throughput_max")) {
            for (int rep = 0; rep < REPS; rep++) {
                List<String> order = new ArrayList<>(POLICIES);
                Collections.shuffle(order, new Random(2_000L + rep));
                for (String policyId : order) {
                    String resolved = resolve(policyId, calibration);
                    Workloads.settle(database);
                    runPhases(database, workload, resolved, policyId, rep, maxConnections, calibration.model(),
                            windows, phases);
                }
            }
        }
    }

    /** {@code static-nstar}, {@code static-neta}, and {@code static-n005} become the calibrated sizes. */
    static String resolve(String policyId, Calibration.Result calibration) {
        return switch (policyId) {
            case "static-nstar" -> "static-" + calibration.nStar();
            case "static-neta" -> "static-" + calibration.nEta();
            case "static-n005" -> "static-" + calibration.nEps05();
            default -> policyId;
        };
    }

    /** One instance: its pool, its policy, its clients, and its counters. */
    private static final class Instance {
        final int index;
        final AtomicBoolean active = new AtomicBoolean();
        final LongAdder committed = new LongAdder();
        final LongAdder failures = new LongAdder();
        final List<Thread> clients = new ArrayList<>();
        volatile PolicyPool pool;
        volatile int poolSize;
        volatile int openConnections;
        volatile double busy;
        volatile double queue;
        // committed at the start of the second half of the current phase
        long halfMark;

        Instance(int index) {
            this.index = index;
        }
    }

    private void runPhases(Database database, Workloads.Prepared workload, String resolved, String policyId, int rep,
                           int maxConnections, UslModel model, CsvWriter windowsOut, CsvWriter phasesOut)
            throws Exception {
        AtomicBoolean stop = new AtomicBoolean();
        AtomicInteger phase = new AtomicInteger();
        long start = System.nanoTime();
        Histogram response = new ConcurrentHistogram(TimeUnit.SECONDS.toNanos(60), 3);
        AtomicBoolean measuring = new AtomicBoolean();
        List<Instance> instances = new ArrayList<>();
        try {
            for (int p = 0; p < INSTANCES; p++) {
                Instance instance = new Instance(p);
                instances.add(instance);
                start(instance, database, workload, resolved, rep, stop, phase, start, response, measuring, windowsOut,
                        policyId);
                phase.set(p);
                response.reset();
                Thread.sleep(TimeUnit.SECONDS.toMillis(PHASE_SECONDS) / 2);
                // The steady second half of the phase.
                for (Instance i : instances) {
                    i.halfMark = i.committed.sum();
                }
                long failuresBefore = instances.stream().mapToLong(i -> i.failures.sum()).sum();
                measuring.set(true);
                long halfStart = System.nanoTime();
                double configured = 0;
                double open = 0;
                double busy = 0;
                int samples = 0;
                long halfEnd = halfStart + TimeUnit.SECONDS.toNanos(PHASE_SECONDS) / 2;
                while (System.nanoTime() < halfEnd) {
                    Thread.sleep(WINDOW_MILLIS);
                    configured += instances.stream().mapToInt(i -> i.poolSize).sum();
                    open += instances.stream().mapToInt(i -> i.openConnections).sum();
                    busy += instances.stream().mapToDouble(i -> i.busy).sum();
                    samples++;
                }
                measuring.set(false);
                double seconds = (System.nanoTime() - halfStart) / 1e9;
                double[] perInstance = instances.stream()
                        .mapToDouble(i -> (i.committed.sum() - i.halfMark) / seconds).toArray();
                double total = java.util.Arrays.stream(perInstance).sum();
                double meanBusy = busy / samples;
                long failed = instances.stream().mapToLong(i -> i.failures.sum()).sum() - failuresBefore;
                double predicted = model.throughputAt(Math.max(1.0, meanBusy));
                phasesOut.writeRow(rep, policyId, resolved, p, p + 1, maxConnections, configured / samples,
                        open / samples, meanBusy, total, predicted, failed,
                        response.getTotalCount() == 0 ? Double.NaN : response.getMean() / 1e6,
                        response.getTotalCount() == 0 ? Double.NaN : response.getValueAtPercentile(99) / 1e6,
                        java.util.Arrays.stream(perInstance).min().orElse(Double.NaN),
                        java.util.Arrays.stream(perInstance).max().orElse(Double.NaN));
                LOGGER.info("rep {} {} ({}), {} instances: {} configured, {} open, {} busy of max_connections {}; "
                                + "{} tx/s (curve at the busy total: {}), {} failed acquisitions, R {} / p99 {} ms",
                        rep, policyId, resolved, p + 1, String.format("%.0f", configured / samples),
                        String.format("%.0f", open / samples), String.format("%.1f", meanBusy), maxConnections,
                        String.format("%.0f", total), String.format("%.0f", predicted), failed,
                        String.format("%.2f", response.getMean() / 1e6),
                        String.format("%.2f", response.getValueAtPercentile(99) / 1e6));
            }
        } finally {
            stop.set(true);
            for (Instance instance : instances) {
                for (Thread client : instance.clients) {
                    client.join(30_000);
                }
                if (instance.pool != null) {
                    instance.pool.stopDecisions();
                    instance.pool.close();
                }
            }
        }
    }

    private static void start(Instance instance, Database database, Workloads.Prepared workload, String resolved,
                              int rep, AtomicBoolean stop, AtomicInteger phase, long start, Histogram response,
                              AtomicBoolean measuring, CsvWriter windowsOut, String policyId) {
        long seed = 97L * rep + 13L * instance.index + resolved.hashCode();
        PolicyConnectionAcquisitionStrategy.Config config = new PolicyConnectionAcquisitionStrategy.Config(
                1, MAX_SIZE, Policies.initialSize(resolved, 1), WINDOW_MILLIS, 2, true);
        instance.pool = PolicyPool.open(database, "hikari", config,
                () -> Policies.create(resolved, 1, MAX_SIZE, SizingTarget.DEFAULT, seed, 1_000_000L),
                decision -> {
                    WindowSample s = decision.sample();
                    instance.poolSize = s.poolSize();
                    instance.openConnections = decision.openConnections();
                    instance.busy = s.busyConnections();
                    instance.queue = s.queueLength();
                    ScalingPolicy policy = instance.pool == null ? null : instance.pool.policy();
                    synchronized (windowsOut) {
                        windowsOut.writeRow(rep, policyId, instance.index, (decision.nanoTime() - start) / 1e9,
                                phase.get(), s.poolSize(), decision.targetSize(), decision.openConnections(),
                                s.throughput(), s.busyConnections(), s.queueLength(),
                                policy == null ? "" : policy.state());
                    }
                }, ACQUISITION_TIMEOUT_MILLIS);
        DataSource dataSource = instance.pool.dataSource();
        // A static instance opens its connections before its clients start, as HikariCP's default fixed-size pool
        // (minimumIdle = maximumPoolSize) does at startup. PolicyPool keeps minimumIdle at 1 for the adaptive
        // policies, and HikariCP then opens connections one at a time on demand: on the loaded laptop, about one per
        // second, so a static-49 instance still had 30 connections half a phase after it joined.
        if (resolved.startsWith("static-")) {
            prefill(dataSource, Policies.initialSize(resolved, 1));
        }
        for (int c = 0; c < CLIENTS; c++) {
            SplittableRandom random = new SplittableRandom(seed * 10_007L + c);
            instance.clients.add(Thread.ofPlatform().daemon().name("instance-" + instance.index + "-" + c).start(() -> {
                while (!stop.get()) {
                    long begin = System.nanoTime();
                    try (Connection connection = dataSource.getConnection()) {
                        workload.transaction().run(connection, random);
                    } catch (Exception e) {
                        instance.failures.increment();
                        continue;
                    }
                    instance.committed.increment();
                    if (measuring.get()) {
                        response.recordValue(Math.min(System.nanoTime() - begin, TimeUnit.SECONDS.toNanos(60)));
                    }
                }
            }));
        }
    }

    /**
     * Holds up to {@code size} connections at once, then returns them. The server may refuse some once the instances
     * ask more than {@code max_connections}: the instance then starts with the connections it got, as it would at
     * startup, and its pool keeps trying on demand.
     */
    static int prefill(DataSource dataSource, int size) {
        List<Connection> held = new ArrayList<>(size);
        try {
            for (int i = 0; i < size; i++) {
                held.add(dataSource.getConnection());
            }
        } catch (Exception e) {
            LOGGER.info("Opened {} of {} connections before the clients started: {}", held.size(), size,
                    e.getMessage());
        } finally {
            for (Connection connection : held) {
                try {
                    connection.close();
                } catch (Exception ignore) {
                    // returning a connection to the pool; nothing to recover
                }
            }
        }
        return held.size();
    }

    /** The server's connection limit, for the phases CSV. */
    static int maxConnections(Database database) {
        DataSource admin = database.adminDataSource();
        try (Connection connection = admin.getConnection(); Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(database == Database.MYSQL ? "SELECT @@max_connections"
                     : "SHOW max_connections")) {
            rs.next();
            return Integer.parseInt(rs.getString(1));
        } catch (Exception e) {
            LOGGER.warn("Could not read max_connections", e);
            return -1;
        } finally {
            if (admin instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception ignore) {
                    // best-effort close
                }
            }
        }
    }
}
