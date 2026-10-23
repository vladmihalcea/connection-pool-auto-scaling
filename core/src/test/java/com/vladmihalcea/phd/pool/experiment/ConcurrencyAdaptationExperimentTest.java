package com.vladmihalcea.phd.pool.experiment;

import com.vladmihalcea.phd.pool.common.util.Database;
import com.vladmihalcea.phd.pool.common.util.Workloads;
import com.vladmihalcea.phd.pool.controller.Policies;
import com.vladmihalcea.phd.pool.flexypool.LiveConcurrencyRun;
import com.vladmihalcea.phd.pool.flexypool.PolicyConnectionAcquisitionStrategy;
import com.vladmihalcea.phd.pool.usl.SizingTarget;
import com.vladmihalcea.phd.pool.util.CsvWriter;
import com.vladmihalcea.phd.pool.util.Statistics;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L3: how does each sizing policy follow the client concurrency under a standard load? For every database,
 * pool, and workload (TPC-C-derived and sysbench-derived by default), each policy runs live through FlexyPool
 * ({@link LiveConcurrencyRun}) while the number of closed-loop clients follows a profile of phases, by default
 * 8, 32, 128, 64, and 16 clients for 2 minutes each. Every repetition runs every policy once, in an order
 * shuffled per repetition, so a slow drift of the database does not favor the policies that run first.
 *
 * <p>The policies ({@link Policies}): the static pools {@code static-10} (HikariCP's default) and
 * {@code static-96} (the cap), FlexyPool's reactive growth, PID, hill climbing, the genetic search, the ICASC
 * USL controller ({@code N*}), the plateau-seeking controllers ({@code usl-plateau}, a throughput fit, and
 * {@code usl-response-time}, the elastic lease-time controller), all aimed at {@code usl.live.target} (by default
 * {@code eta:0.1}, the point of diminishing returns), and {@code usl-response-time@eps:0.01}, the same controller at
 * a fixed 99% of the peak. Add the oracle sizes from the dense sweep (K1) with
 * {@code -Dusl.live.policies=...,static-<N*>,static-<N_eta>}.
 *
 * <p>Settings: {@code usl.live.databases} (postgresql,mysql), {@code usl.live.pools} (hikari),
 * {@code usl.live.workloads} (tpcc,oltpReadWrite), {@code usl.live.policies}, {@code usl.live.profile}
 * ({@code clients:seconds,...}), {@code usl.reps} (10), {@code usl.live.minPool} (2), {@code usl.live.maxPool}
 * (96), {@code usl.live.windowMillis} (1000), {@code usl.live.reactiveTimeoutMicros} (2000), {@code usl.live.target}
 * ({@code eta:0.1} or {@code eps:<value>}).
 *
 * <p>Outputs in {@code metrics/ConcurrencyAdaptation/}, per database, pool, and workload, appended run by run so
 * an interrupted campaign keeps what it measured: {@code <db>-<pool>-<workload>-windows.csv} (every window of
 * every run), {@code -phases.csv} (one row per run and phase), and, at the end, {@code -summary.csv}: per policy
 * and phase, the mean and 95% confidence half-width over the repetitions of the steady pool size, the
 * connection-seconds, the throughput, and the mean and p99 response time.
 *
 * @author Vlad Mihalcea
 */
public class ConcurrencyAdaptationExperimentTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConcurrencyAdaptationExperimentTest.class);

    static final String FIGURE = "ConcurrencyAdaptation";
    static final String DEFAULT_POLICIES = "static-10,static-96," + String.join(",", Policies.ADAPTIVE)
            + ",usl-response-time@eps:0.01";

    private static final List<String> DATABASES = list("usl.live.databases", "postgresql,mysql");
    private static final List<String> POOLS = list("usl.live.pools", "hikari");
    private static final List<String> WORKLOADS = list("usl.live.workloads", "tpcc,oltpReadWrite");
    private static final List<String> POLICIES = list("usl.live.policies", DEFAULT_POLICIES);
    private static final String PROFILE = System.getProperty("usl.live.profile", "8:120,32:120,128:120,64:120,16:120");
    private static final int REPS = Integer.getInteger("usl.reps", 10);
    private static final int MIN_POOL = Integer.getInteger("usl.live.minPool", 2);
    private static final int MAX_POOL = Integer.getInteger("usl.live.maxPool", 96);
    private static final long WINDOW_MILLIS = Long.getLong("usl.live.windowMillis", 1_000);
    private static final SizingTarget TARGET = SizingTarget.parse(System.getProperty("usl.live.target", "eta:0.1"));
    private static final long REACTIVE_TIMEOUT_NANOS = Long.getLong("usl.live.reactiveTimeoutMicros", 2_000) * 1_000;

    private static List<String> list(String property, String defaults) {
        return Arrays.stream(System.getProperty(property, defaults).split(",")).map(String::trim)
                .filter(s -> !s.isEmpty()).toList();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Database.class)
    public void policiesFollowTheClientConcurrency(Database database) throws Exception {
        Assumptions.assumeTrue(DATABASES.contains(database.id()), database.id() + " is not in usl.live.databases");
        database.assumeAvailable();
        List<LiveConcurrencyRun.Phase> profile = LiveConcurrencyRun.parseProfile(PROFILE);

        for (String pool : POOLS) {
            for (String workloadName : WORKLOADS) {
                if ("longTx".equals(workloadName) && database == Database.MYSQL) {
                    continue; // generate_series is PostgreSQL-only
                }
                String base = database.id() + "-" + pool + "-" + workloadName;
                Path windowsCsv = CsvWriter.metricsPath(FIGURE, base + "-windows.csv");
                Path phasesCsv = CsvWriter.metricsPath(FIGURE, base + "-phases.csv");
                Files.deleteIfExists(windowsCsv);
                Files.deleteIfExists(phasesCsv);
                Map<String, List<LiveConcurrencyRun.Result>> results = new LinkedHashMap<>();
                for (int rep = 0; rep < REPS; rep++) {
                    List<String> order = new ArrayList<>(POLICIES);
                    Collections.shuffle(order, new Random(1_000L + rep));
                    for (String policyId : order) {
                        Workloads.Prepared workload = Workloads.prepare(database, workloadName);
                        long seed = 31L * rep + policyId.hashCode();
                        PolicyConnectionAcquisitionStrategy.Config config = new PolicyConnectionAcquisitionStrategy.Config(
                                MIN_POOL, MAX_POOL, Policies.initialSize(policyId, MIN_POOL), WINDOW_MILLIS, 2, true);
                        LiveConcurrencyRun.Result result = new LiveConcurrencyRun(database, pool, workload,
                                () -> Policies.create(policyId, MIN_POOL, MAX_POOL, TARGET, seed,
                                        REACTIVE_TIMEOUT_NANOS), config, profile, seed).run();
                        results.computeIfAbsent(policyId, k -> new ArrayList<>()).add(result);
                        appendWindows(windowsCsv, policyId, rep, result);
                        appendPhases(phasesCsv, policyId, rep, result);
                        LOGGER.info("{} {} rep {}: committed {}, failures {} {}, phases {}", base, policyId, rep,
                                result.committed(), result.failures(), result.failureTypes(), describe(result));
                        assertTrue(result.committed() > 0, base + " " + policyId + " committed nothing");
                    }
                }
                writeSummary(CsvWriter.metricsPath(FIGURE, base + "-summary.csv"), profile, results);
            }
        }
    }

    private static String describe(LiveConcurrencyRun.Result result) {
        StringBuilder text = new StringBuilder();
        for (LiveConcurrencyRun.PhaseSummary p : result.phases()) {
            text.append(String.format(Locale.ROOT, "[%d clients: pool %.1f, %.0f tx/s, R %.2f/%.2f ms] ",
                    p.clients(), p.steadyPoolSize(), p.throughput(), p.responseMeanMillis(), p.responseP99Millis()));
        }
        return text.toString();
    }

    private static void appendWindows(Path csv, String policy, int rep, LiveConcurrencyRun.Result result) {
        StringBuilder rows = new StringBuilder();
        if (!Files.exists(csv)) {
            rows.append("policy,rep,time_s,phase,clients,pool_size,target_size,open_connections,tps,busy,queue,"
                    + "acquire_mean_ms,lease_mean_ms,response_mean_ms,response_p99_ms,plateau_estimate,"
                    + "optimum_estimate,state\n");
        }
        for (LiveConcurrencyRun.WindowRow w : result.windows()) {
            rows.append(String.join(",", policy, String.valueOf(rep), f(w.timeSeconds()), String.valueOf(w.phase()),
                    String.valueOf(w.clients()), String.valueOf(w.poolSize()), String.valueOf(w.targetSize()),
                    String.valueOf(w.openConnections()), f(w.throughput()), f(w.busy()), f(w.queue()),
                    f(w.acquisitionMeanMillis()), f(w.leaseMeanMillis()), f(w.responseMeanMillis()),
                    f(w.responseP99Millis()), String.valueOf(w.plateauEstimate()),
                    String.valueOf(w.optimumEstimate()), w.state())).append('\n');
        }
        append(csv, rows);
    }

    private static void appendPhases(Path csv, String policy, int rep, LiveConcurrencyRun.Result result) {
        StringBuilder rows = new StringBuilder();
        if (!Files.exists(csv)) {
            rows.append("policy,rep,phase,clients,seconds,mean_pool,steady_pool,connection_seconds,tps,steady_tps,"
                    + "response_mean_ms,response_p50_ms,response_p99_ms,response_p999_ms,acquire_mean_ms,committed,"
                    + "failures,retries,failure_types\n");
        }
        for (LiveConcurrencyRun.PhaseSummary p : result.phases()) {
            rows.append(String.join(",", policy, String.valueOf(rep), String.valueOf(p.phase()),
                    String.valueOf(p.clients()), f(p.seconds()), f(p.meanPoolSize()), f(p.steadyPoolSize()),
                    f(p.connectionSeconds()), f(p.throughput()), f(p.steadyThroughput()), f(p.responseMeanMillis()),
                    f(p.responseP50Millis()), f(p.responseP99Millis()), f(p.responseP999Millis()),
                    f(p.acquisitionMeanMillis()), String.valueOf(p.committed()), String.valueOf(p.failures()),
                    String.valueOf(result.retries()), failureTypes(result))).append('\n');
        }
        append(csv, rows);
    }

    /** The run's failures by type, for example {@code SQLException[40001]=3;SQLTimeoutException=1}. */
    private static String failureTypes(LiveConcurrencyRun.Result result) {
        StringBuilder text = new StringBuilder();
        result.failureTypes().forEach((type, count) -> text.append(text.isEmpty() ? "" : ";").append(type).append('=')
                .append(count));
        return text.toString();
    }

    private static void writeSummary(Path csv, List<LiveConcurrencyRun.Phase> profile,
                                     Map<String, List<LiveConcurrencyRun.Result>> results) {
        try (CsvWriter out = new CsvWriter(csv, "policy", "phase", "clients", "reps", "steady_pool",
                "steady_pool_ci95", "connection_seconds", "connection_seconds_ci95", "tps", "tps_ci95",
                "response_mean_ms", "response_mean_ms_ci95", "response_p99_ms", "response_p99_ms_ci95",
                "failures")) {
            for (Map.Entry<String, List<LiveConcurrencyRun.Result>> entry : results.entrySet()) {
                for (int p = 0; p < profile.size(); p++) {
                    Statistics pool = new Statistics();
                    Statistics connections = new Statistics();
                    Statistics tps = new Statistics();
                    Statistics mean = new Statistics();
                    Statistics p99 = new Statistics();
                    long failures = 0;
                    for (LiveConcurrencyRun.Result result : entry.getValue()) {
                        LiveConcurrencyRun.PhaseSummary s = result.phases().get(p);
                        pool.add(s.steadyPoolSize());
                        connections.add(s.connectionSeconds());
                        tps.add(s.throughput());
                        mean.add(s.responseMeanMillis());
                        p99.add(s.responseP99Millis());
                        failures += s.failures();
                    }
                    out.writeRow(entry.getKey(), p, profile.get(p).clients(), entry.getValue().size(), pool.mean(),
                            ci(pool), connections.mean(), ci(connections), tps.mean(), ci(tps), mean.mean(), ci(mean),
                            p99.mean(), ci(p99), failures);
                }
            }
        }
    }

    private static double ci(Statistics statistics) {
        return statistics.count() > 1 ? statistics.ci95HalfWidthStudent() : Double.NaN;
    }

    private static void append(Path csv, CharSequence rows) {
        try {
            Files.createDirectories(csv.getParent());
            Files.writeString(csv, rows, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String f(double value) {
        return Double.isNaN(value) ? "" : String.format(Locale.ROOT, "%.4f", value);
    }
}
