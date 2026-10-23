package com.vladmihalcea.phd.pool.experiment;

import com.vladmihalcea.phd.pool.adapter.PoolFactory;
import com.vladmihalcea.phd.pool.api.PoolUnderTest;
import com.vladmihalcea.phd.pool.common.util.Database;
import com.vladmihalcea.phd.pool.common.util.Workloads;
import com.vladmihalcea.phd.pool.usl.QueueModels;
import com.vladmihalcea.phd.pool.usl.UslModel;
import com.vladmihalcea.phd.pool.util.CsvWriter;
import org.HdrHistogram.ConcurrentHistogram;
import org.HdrHistogram.Histogram;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

/**
 * Experiment K7 of the plateau-start article (RQ1, RQ2, H5): the pool under <em>open</em> arrivals, where, unlike in
 * the closed loops of the other experiments, Little's Law bounds nothing: at a fixed arrival rate, a pool sized at the
 * plateau start lowers the capacity by up to {@code eps}, and the queueing delay grows steeply as the arrival rate
 * approaches it.
 * <p>
 * For each database and workload, a {@link Calibration} sweep first measures the curve and fits the USL, which gives
 * {@code N*}, the point of diminishing returns {@code N_eta}, the 95% plateau start {@code N_0.05}, and the peak
 * {@code X*}. Then, for each pool cap among those sizes and each load {@code f} ({@code usl.open.loads}: 30% to 95%),
 * Poisson arrivals at the rate {@code f X*} (the same rates for every cap) run against a static HikariCP pool for a
 * {@code usl.open.warmupSeconds} (10 s) warm-up and {@code usl.open.seconds} (60 s) measured. A dispatcher draws the
 * arrival times from a seeded exponential distribution and starts one virtual thread per request; the response time
 * runs from the <em>scheduled</em> arrival to the return of the connection, so a late dispatcher cannot hide queueing
 * (no coordinated omission).
 * <p>
 * At most {@code usl.open.maxInFlight} (20,000) requests are in flight at once, waiting or running. An arrival beyond
 * it is shed and counted (the {@code shed} columns): once the arrival rate exceeds the pool's capacity, the queue grows
 * without bound, and the bound keeps the backlog, and so the threads and the memory, finite. A run that sheds is
 * unstable, and its response times cover only the admitted requests. On a single machine, the driver's own CPU lowers
 * the capacity below the calibrated (closed-loop) peak, so the highest loads shed sooner than on a separate client.
 * <p>
 * Per one-second window: the completions, the mean busy connections {@code L} (the lease time of the window's
 * transactions over the window), the mean waiting requests {@code Q} (likewise for the acquisition waits), the mean
 * lease time {@code W}, and the response-time percentiles. Per run: the achieved arrival rate and throughput, the
 * utilization {@code L / N}, the mean wait against the Erlang-C (M/M/c) prediction with the measured lease time as
 * the service time, and the response-time mean, p50, p99, and p99.9.
 * <p>
 * H5: a window without waiting requests ({@code Q < 0.05}) is demand-limited, and the lease-time reading of the USL
 * predicts its lease time from its busy count alone, {@code W(L) = (1 + sigma (L - 1) + kappa L (L - 1)) / lambda}
 * with the calibrated coefficients. The windows CSV has the predicted lease time next to the measured one, and the
 * summary has the median and the 90th percentile of the relative error and its mean sign (the bias).
 * <p>
 * Outputs ({@code metrics/OpenArrival/}): {@code <db>-<workload>-calibration.csv}, {@code -windows.csv}, and
 * {@code -summary.csv}.
 *
 * @author Vlad Mihalcea
 */
public class OpenArrivalExperimentTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(OpenArrivalExperimentTest.class);

    static final String FIGURE = "OpenArrival";
    private static final String POOL = System.getProperty("usl.open.pool", "hikari");
    private static final List<String> DATABASES = list("usl.open.databases", "postgresql,mysql");
    private static final List<String> WORKLOADS = list("usl.open.workloads", "readMostly,oltpReadWrite");
    private static final double[] LOADS = Arrays.stream(
            System.getProperty("usl.open.loads", "0.3,0.5,0.7,0.8,0.9,0.95").split(","))
            .mapToDouble(s -> Double.parseDouble(s.trim())).toArray();
    private static final List<String> CAPS = list("usl.open.caps", "nstar,neta,n005");
    private static final long SECONDS = Long.getLong("usl.open.seconds", 60);
    private static final long WARMUP_SECONDS = Long.getLong("usl.open.warmupSeconds", 10);
    private static final int REPS = Integer.getInteger("usl.reps", 3);
    private static final int MAX_SIZE = Integer.getInteger("usl.open.maxSize", 96);
    private static final long CALIBRATION_WARMUP_MILLIS = Long.getLong("usl.calibration.warmupMillis", 2_000);
    private static final long CALIBRATION_MILLIS = Long.getLong("usl.calibration.measureMillis", 5_000);
    private static final double DEMAND_LIMITED_QUEUE = 0.05;
    private static final int MAX_IN_FLIGHT = Integer.getInteger("usl.open.maxInFlight", 20_000);

    static List<String> list(String property, String defaults) {
        return Arrays.stream(System.getProperty(property, defaults).split(",")).map(String::trim)
                .filter(s -> !s.isEmpty()).toList();
    }

    @Test
    public void measureOpenArrivals() throws Exception {
        boolean ranAny = false;
        for (String databaseId : DATABASES) {
            Database database = Database.valueOf(databaseId.toUpperCase(Locale.ROOT));
            if (!database.isAvailable()) {
                LOGGER.warn("Skipping {}: not available", databaseId);
                continue;
            }
            for (String workloadName : WORKLOADS) {
                ranAny = true;
                run(database, workloadName);
            }
        }
        Assumptions.assumeTrue(ranAny, "No database available");
    }

    private void run(Database database, String workloadName) throws Exception {
        String base = database.id() + "-" + workloadName;
        Workloads.Prepared workload = Workloads.prepare(database, workloadName);
        Workloads.settle(database);
        Workloads.warmUp(database, workload);
        Calibration.Result calibration = Calibration.run(database, POOL, workload, Calibration.SIZES, MAX_SIZE,
                CALIBRATION_WARMUP_MILLIS, CALIBRATION_MILLIS, 17L);
        writeCalibration(CsvWriter.metricsPath(FIGURE, base + "-calibration.csv"), calibration);
        UslModel model = calibration.model();

        try (CsvWriter windows = new CsvWriter(CsvWriter.metricsPath(FIGURE, base + "-windows.csv"),
                "rep", "cap", "pool_size", "load", "rate", "window", "completed", "shed", "busy", "queue", "lease_ms",
                "response_mean_ms", "response_p99_ms", "demand_limited", "usl_lease_ms", "lease_error");
             CsvWriter summary = new CsvWriter(CsvWriter.metricsPath(FIGURE, base + "-summary.csv"),
                     "rep", "cap", "pool_size", "load", "rate", "arrival_rate", "throughput", "busy", "utilization",
                     "queue", "lease_ms", "wait_mean_ms", "erlang_c_wait_ms", "response_mean_ms", "response_p50_ms",
                     "response_p99_ms", "response_p999_ms", "failures", "shed", "demand_limited_windows",
                     "h5_median_error", "h5_p90_error", "h5_bias")) {
            for (int rep = 0; rep < REPS; rep++) {
                for (String cap : CAPS) {
                    int size = switch (cap) {
                        case "nstar" -> calibration.nStar();
                        case "neta" -> calibration.nEta();
                        case "n005" -> calibration.nEps05();
                        default -> Integer.parseInt(cap);
                    };
                    for (double load : LOADS) {
                        double rate = load * calibration.peakThroughput();
                        Workloads.settle(database);
                        RunResult r = runOpen(database, workload, size, rate, 1_000L * rep + (long) (load * 100) + size);
                        write(windows, summary, rep, cap, size, load, rate, r, model);
                    }
                }
            }
        }
    }

    /** The counters of one one-second window. */
    private static final class Window {
        final LongAdder completed = new LongAdder();
        final LongAdder leaseNanos = new LongAdder();
        final LongAdder waitNanos = new LongAdder();
        final Histogram response = new ConcurrentHistogram(TimeUnit.SECONDS.toNanos(60), 2);
        /** The arrivals scheduled in this window and shed; only the dispatcher writes it. */
        long shed;
    }

    private record RunResult(Window[] windows, long arrivals, double seconds, Histogram response, long failures,
                             long shed, double waitMeanNanos) {
    }

    /** Poisson arrivals at {@code rate} against a static pool of {@code size}. */
    static RunResult runOpen(Database database, Workloads.Prepared workload, int size, double rate, long seed)
            throws Exception {
        int windowsCount = (int) SECONDS;
        Window[] windows = new Window[windowsCount];
        for (int i = 0; i < windowsCount; i++) {
            windows[i] = new Window();
        }
        Histogram total = new ConcurrentHistogram(TimeUnit.SECONDS.toNanos(60), 3);
        LongAdder failures = new LongAdder();
        LongAdder totalWait = new LongAdder();
        LongAdder measuredCompletions = new LongAdder();
        long arrivals = 0;
        long shed = 0;
        try (PoolUnderTest pool = PoolFactory.create(POOL, database.jdbcConfig(), size)) {
            Calibration.prefill(pool, size);
            AtomicInteger inFlight = new AtomicInteger();
            SplittableRandom arrivalsRandom = new SplittableRandom(seed);
            double meanGapNanos = 1e9 / rate;
            long start = System.nanoTime();
            long measureStart = start + TimeUnit.SECONDS.toNanos(WARMUP_SECONDS);
            long end = measureStart + TimeUnit.SECONDS.toNanos(SECONDS);
            long next = start;
            while (true) {
                next += (long) (-Math.log(1 - arrivalsRandom.nextDouble()) * meanGapNanos);
                if (next >= end) {
                    break;
                }
                long now;
                while ((now = System.nanoTime()) < next) {
                    long gap = next - now;
                    if (gap > 200_000) {
                        LockSupport.parkNanos(gap - 100_000);
                    } else {
                        Thread.onSpinWait();
                    }
                }
                final long scheduled = next;
                final long requestSeed = arrivalsRandom.nextLong();
                if (scheduled >= measureStart) {
                    arrivals++;
                }
                if (inFlight.get() >= MAX_IN_FLIGHT) {
                    if (scheduled >= measureStart) {
                        shed++;
                        windows[(int) Math.min(windowsCount - 1, (scheduled - measureStart) / 1_000_000_000L)].shed++;
                    }
                    continue;
                }
                inFlight.incrementAndGet();
                Thread.ofVirtual().start(() -> {
                    try {
                        long acquireStart = System.nanoTime();
                        long acquired;
                        long leaseEnd;
                        try (Connection connection = pool.dataSource().getConnection()) {
                            acquired = System.nanoTime();
                            workload.transaction().run(connection, new SplittableRandom(requestSeed));
                            leaseEnd = System.nanoTime();
                        }
                        long done = System.nanoTime();
                        if (scheduled >= measureStart && done < end) {
                            int w = (int) ((done - measureStart) / 1_000_000_000L);
                            Window window = windows[Math.min(windowsCount - 1, w)];
                            window.completed.increment();
                            window.leaseNanos.add(leaseEnd - acquired);
                            window.waitNanos.add(acquired - acquireStart);
                            window.response.recordValue(Math.min(done - scheduled, TimeUnit.SECONDS.toNanos(60)));
                            total.recordValue(Math.min(done - scheduled, TimeUnit.SECONDS.toNanos(60)));
                            totalWait.add(acquired - scheduled);
                            measuredCompletions.increment();
                        }
                    } catch (SQLException e) {
                        if (scheduled >= measureStart) {
                            failures.increment();
                        }
                    } finally {
                        inFlight.decrementAndGet();
                    }
                });
            }
            // Let the requests in flight finish before the pool closes.
            long drainDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (inFlight.get() > 0 && System.nanoTime() < drainDeadline) {
                Thread.sleep(10);
            }
            if (inFlight.get() > 0) {
                LOGGER.warn("{} requests still in flight 60 s after the last arrival", inFlight.get());
            }
        }
        long completions = measuredCompletions.sum();
        return new RunResult(windows, arrivals, SECONDS, total, failures.sum(), shed,
                completions == 0 ? Double.NaN : totalWait.sum() / (double) completions);
    }

    private static void write(CsvWriter windowsOut, CsvWriter summaryOut, int rep, String cap, int size, double load,
                              double rate, RunResult r, UslModel model) {
        long completed = 0;
        long leaseNanos = 0;
        long waitNanos = 0;
        List<Double> errors = new ArrayList<>();
        for (int w = 0; w < r.windows().length; w++) {
            Window window = r.windows()[w];
            long count = window.completed.sum();
            completed += count;
            leaseNanos += window.leaseNanos.sum();
            waitNanos += window.waitNanos.sum();
            double busy = window.leaseNanos.sum() / 1e9;
            double queue = window.waitNanos.sum() / 1e9;
            double lease = count == 0 ? Double.NaN : window.leaseNanos.sum() / 1e6 / count;
            boolean demandLimited = count > 0 && queue < DEMAND_LIMITED_QUEUE && busy < size;
            double uslLease = model.responseTimeAt(Math.max(busy, 1e-3)) * 1e3;
            double error = demandLimited ? (lease - uslLease) / uslLease : Double.NaN;
            if (demandLimited) {
                errors.add(error);
            }
            windowsOut.writeRow(rep, cap, size, load, rate, w, count, window.shed, busy, queue, lease,
                    window.response.getTotalCount() == 0 ? Double.NaN : window.response.getMean() / 1e6,
                    window.response.getTotalCount() == 0 ? Double.NaN : window.response.getValueAtPercentile(99) / 1e6,
                    demandLimited, uslLease, demandLimited ? error : "");
        }
        double seconds = r.seconds();
        double busy = leaseNanos / 1e9 / seconds;
        double lease = completed == 0 ? Double.NaN : leaseNanos / 1e9 / completed;
        double arrivalRate = r.arrivals() / seconds;
        // Erlang C with the measured lease time as the mean service time; unstable when the offered load reaches
        // the servers.
        double offered = arrivalRate * lease;
        double erlangWait = offered < size
                ? QueueModels.erlangCWaitProbability(offered, size) * lease / (size - offered) * 1e3
                : Double.POSITIVE_INFINITY;
        Histogram response = r.response();
        double[] sorted = errors.stream().mapToDouble(e -> Math.abs(e)).sorted().toArray();
        double bias = errors.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
        summaryOut.writeRow(rep, cap, size, load, rate, arrivalRate, completed / seconds, busy, busy / size,
                waitNanos / 1e9 / seconds, lease * 1e3, r.waitMeanNanos() / 1e6, erlangWait,
                response.getTotalCount() == 0 ? Double.NaN : response.getMean() / 1e6,
                response.getValueAtPercentile(50) / 1e6, response.getValueAtPercentile(99) / 1e6,
                response.getValueAtPercentile(99.9) / 1e6, r.failures(), r.shed(), errors.size(),
                sorted.length == 0 ? Double.NaN : sorted[sorted.length / 2],
                sorted.length == 0 ? Double.NaN : sorted[(int) Math.min(sorted.length - 1, Math.ceil(0.9 * sorted.length) - 1)],
                bias);
        LOGGER.info("rep {} cap {} ({} connections), load {} ({} req/s): throughput {} tx/s, busy {} ({}%), wait {} ms "
                        + "(Erlang C {} ms), response mean {} p99 {} ms, {} failures, {} shed; H5 over {} demand-limited windows: "
                        + "median |error| {}, bias {}", rep, cap, size, load, String.format("%.0f", rate),
                String.format("%.0f", completed / seconds), String.format("%.1f", busy),
                String.format("%.0f", 100 * busy / size), String.format("%.3f", r.waitMeanNanos() / 1e6),
                String.format("%.3f", erlangWait), String.format("%.2f", response.getMean() / 1e6),
                String.format("%.2f", response.getValueAtPercentile(99) / 1e6), r.failures(), r.shed(), errors.size(),
                sorted.length == 0 ? "-" : String.format("%.3f", sorted[sorted.length / 2]),
                String.format("%.3f", bias));
    }

    static void writeCalibration(java.nio.file.Path csv, Calibration.Result calibration) {
        try (CsvWriter out = new CsvWriter(csv, "pool_size", "throughput", "usl", "lambda", "sigma", "kappa", "r2",
                "nstar", "n_eta", "n005")) {
            UslModel model = calibration.model();
            for (int i = 0; i < calibration.sizes().length; i++) {
                out.writeRow(calibration.sizes()[i], calibration.throughput()[i],
                        model.throughputAt(calibration.sizes()[i]), model.lambda(), model.sigma(), model.kappa(),
                        calibration.fit().rSquared(), calibration.nStar(), calibration.nEta(), calibration.nEps05());
            }
        }
    }
}
