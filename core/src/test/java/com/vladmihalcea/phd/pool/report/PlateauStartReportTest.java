package com.vladmihalcea.phd.pool.report;

import com.vladmihalcea.phd.pool.usl.LeaseTimeFitter;
import com.vladmihalcea.phd.pool.usl.UslFit;
import com.vladmihalcea.phd.pool.usl.UslFitter;
import com.vladmihalcea.phd.pool.usl.UslModel;
import com.vladmihalcea.phd.pool.util.CsvWriter;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Experiments K1 and K2 of the plateau-start article, the analysis of the dense sweeps: does the fitted USL place
 * the start of the plateau where the measured curve has it (H1), is the plateau start better determined than the
 * optimum (H4), how do the Levenberg-Marquardt and lease-time estimators compare, does the measured ratio
 * {@code N_eps / N*} follow the shape number, and does Little's Law hold for the measured response times?
 * <p>
 * Input: the JMH JSON of every sweep ({@code -Dusl.analysis.sweeps=<a.json,b.json>}, by default every
 * {@code metrics/jmh-results/ThroughputVsPoolSizeBenchmark*.json}), which keeps the score of every measurement
 * iteration of every fork, and the sweep's {@code -latency.csv} next to it, when it exists. A curve is one
 * (database, pool, workload) of a sweep; its throughput at a pool size is the mean over the forks.
 * <p>
 * The throughput of a fork is its {@code window_tps}, the transactions completed inside the measured window over the
 * window's length, which the sweeps write to the {@code -latency.csv} since 17 Nov. JMH's score sums the per-thread
 * rates, each over the thread's own measured loop, so the threads that start late or end their loop with a
 * transaction in flight inflate it: up to 24 times with 128 clients queuing on a small pool. A curve falls back to
 * the JMH score when one of its sizes has no {@code window_tps} (the older sweeps), and
 * {@code -Dusl.analysis.throughput=jmh} forces the JMH score everywhere. {@code plateau-curves.csv} keeps the JMH
 * score next to the throughput used, and the log gives the largest ratio of a curve.
 * <p>
 * Per curve and tolerance {@code eps} (1%, 2%, 5%, 10%):
 * <ul>
 *   <li>the <em>measured</em> plateau start, the smallest swept size whose throughput is at least {@code (1 - eps)}
 *       of the highest measured throughput, and the measured optimum, the size of that highest throughput;</li>
 *   <li>the <em>fitted</em> plateau start of both estimators ({@link UslModel#poolSizeAtFraction(double, int)}, with
 *       the sweep's largest size as the cap, so a peak beyond the sweep is taken at its edge), the fitted
 *       optimum, the shape number {@code gamma = (sigma - kappa) / sqrt(kappa (1 - sigma))}, the closed-form ratio
 *       {@code N_eps / N^} it predicts, and the point of diminishing returns {@code N_eta} ({@code eta = 0.1});</li>
 *   <li>the original H1: whether the fitted plateau start is within 3 connections of the measured one (the
 *       sweep's step of 2 up to 64 sizes bounds the resolution of the measured one), kept for comparison;</li>
 *   <li>H1a (revised 19 Nov, the primary test): whether the measured throughput at the fitted plateau start, read
 *       at the smallest swept size at or above it, reaches {@code 1 - eps - delta} of the measured peak, where
 *       {@code delta} is the larger of one percentage point and the half-width of the peak's 95% bootstrap
 *       interval, relative to the peak; also at the upper end of the fitted plateau start's interval;</li>
 *   <li>H1b (revised 19 Nov): whether the fitted plateau start, on the sweep's grid, lies inside the 95% bootstrap
 *       interval of the measured one (with 2 forks the intervals collapse, so only a 10-fork sweep tests it);</li>
 *   <li>the measured plateau start over the fitted optimum, when the fitted optimum lies inside the sweep, against
 *       the closed form's bound for {@code sigma >= kappa} ({@code 0.724} at 5%);</li>
 *   <li>the share of the measured peak that the fitted plateau start delivers, read from the measured curve at the
 *       smallest swept size at or above it;</li>
 *   <li>K2 and H4: 95% percentile intervals of the fitted and measured plateau starts and of the fitted optimum
 *       from a bootstrap over the forks ({@code usl.analysis.bootstrap}, 1,000 resamples, seeded): every resample
 *       draws, at every size, as many fork means as were measured, with replacement, and refits the curve.</li>
 * </ul>
 * Little's Law: in the sweep's closed loop {@code M} clients compete for the pool ({@code min(N + 16, 128)} by
 * default, the {@code clients} column of the {@code -latency.csv}), so the mean response time from the request for a
 * connection to its return must be {@code M / X}; the report compares the measured mean of every size with it. The
 * sweeps since 17 Nov also record the mean acquisition wait and lease, so the report gives the busy connections
 * {@code X W_lease} and their share of {@code N}, and warns about the sizes below 90%: a pool that is not saturated
 * measures the client, not the pool (the co-located laptop held only 18 of 96 connections busy behind a fair client
 * throttle). A
 * sweep with the same {@code M} at every size ({@code -Dusl.sweep.clients}) also checks the article's closed-loop
 * bound: the mean response time at the fitted plateau start over the one at the measured optimum, against
 * {@code 1 / (1 - eps)}. With the same {@code M} everywhere, Little's Law gives the mean response time as
 * {@code M / X}, so the bound is judged on the throughput ratio. The measured means are censored (a client that
 * waits past the end of the window adds nothing), so their ratio is reported next to it, as is the p99 ratio, which
 * the bound does not cover.
 * <p>
 * Outputs ({@code metrics/PlateauStart/}): {@code plateau-start.csv} (one row per curve and tolerance),
 * {@code plateau-fits.csv} (one row per curve: both fits, the optima, the shape number, {@code N_eta}, and the H4
 * interval widths), {@code plateau-curves.csv} (the measured curve with both fits, for the figures),
 * {@code littles-law.csv}, and {@code response-time-bound.csv}.
 *
 * @author Vlad Mihalcea
 */
public class PlateauStartReportTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlateauStartReportTest.class);

    static final double[] TOLERANCES = {0.01, 0.02, 0.05, 0.10};
    static final double ETA = 0.1;
    static final int H1_CONNECTIONS = 3;
    /** The smallest {@code delta} of H1a: one percentage point of the peak. */
    static final double H1A_MIN_DELTA = 0.01;
    private static final int BOOTSTRAP = Integer.getInteger("usl.analysis.bootstrap", 1_000);
    private static final boolean JMH_THROUGHPUT = "jmh".equals(System.getProperty("usl.analysis.throughput"));
    private static final String FIGURE = "PlateauStart";

    /**
     * One measured curve: the throughput of every fork at every swept pool size ({@code window_tps}, or the JMH fork
     * means when {@code throughput} is {@code jmh}), and the JMH fork means.
     */
    record Curve(String source, String database, String pool, String workload, TreeMap<Integer, double[]> forkMeans,
                 TreeMap<Integer, double[]> jmhForkMeans, String throughput) {

        String label() {
            return source + " " + database + " " + pool + " " + workload;
        }

        double[] sizes() {
            return forkMeans.keySet().stream().mapToDouble(Integer::doubleValue).toArray();
        }

        double[] means() {
            return forkMeans.values().stream().mapToDouble(PlateauStartReportTest::mean).toArray();
        }

        int maxSize() {
            return forkMeans.lastKey();
        }

        int forks() {
            return forkMeans.values().stream().mapToInt(f -> f.length).min().orElse(0);
        }
    }

    /** What one throughput curve (measured or resampled) gives. */
    record Estimate(double[] measured, double measuredOptimum, UslFit lm, UslFit lease, double[] fitted,
                    double[] fittedLease) {
    }

    @Test
    public void analyzePlateauStart() {
        List<Path> sweeps = sweeps();
        Assumptions.assumeFalse(sweeps.isEmpty(), "No ThroughputVsPoolSizeBenchmark*.json in metrics/jmh-results");
        List<Curve> curves = new ArrayList<>();
        for (Path sweep : sweeps) {
            curves.addAll(load(sweep));
        }
        Assumptions.assumeFalse(curves.isEmpty(), "No curves in " + sweeps);

        try (CsvWriter starts = new CsvWriter(CsvWriter.metricsPath(FIGURE, "plateau-start.csv"),
                "source", "database", "pool", "workload", "sizes", "forks", "eps",
                "measured", "measured_ci95_low", "measured_ci95_high",
                "fitted_lm", "fitted_lm_ci95_low", "fitted_lm_ci95_high", "fitted_lease",
                "fitted_minus_measured", "h1_within_3", "share_of_peak_at_fitted",
                "closed_form_ratio", "measured_ratio", "peak_ci95_half_width", "delta", "h1a",
                "share_of_peak_at_fitted_high", "h1a_at_fitted_high", "fitted_grid_size", "h1b",
                "measured_over_fitted_optimum", "ratio_bound", "within_ratio_bound");
             CsvWriter fits = new CsvWriter(CsvWriter.metricsPath(FIGURE, "plateau-fits.csv"),
                     "source", "database", "pool", "workload", "sizes", "forks", "max_size",
                     "lambda", "sigma", "kappa", "r2", "lease_lambda", "lease_sigma", "lease_kappa", "lease_r2",
                     "nhat_lm", "nhat_lease", "nstar_measured", "nstar_lm_ci95_low", "nstar_lm_ci95_high",
                     "gamma", "n_eta_lm", "n_eta_lease", "n005_ci95_width", "nstar_ci95_width",
                     "n005_relative_width", "nstar_relative_width", "h4_narrower");
             CsvWriter points = new CsvWriter(CsvWriter.metricsPath(FIGURE, "plateau-curves.csv"),
                     "source", "database", "pool", "workload", "pool_size", "forks", "tps_mean", "tps_sd",
                     "tps_lm", "tps_lease", "throughput", "jmh_tps_mean", "jmh_over_tps");
             CsvWriter little = new CsvWriter(CsvWriter.metricsPath(FIGURE, "littles-law.csv"),
                     "source", "database", "pool", "workload", "pool_size", "clients", "tps", "measured_mean_us",
                     "littles_law_us", "ratio", "p99_us", "mean_wait_us", "mean_lease_us", "busy", "saturation",
                     "queue");
             CsvWriter bound = new CsvWriter(CsvWriter.metricsPath(FIGURE, "response-time-bound.csv"),
                     "source", "database", "pool", "workload", "clients", "eps", "n_eps_fitted", "n_eps_size",
                     "nstar_size", "throughput_ratio", "bound", "within_bound", "mean_response_ratio",
                     "measured_within_bound", "p99_ratio")) {
            Map<String, Latency> latency = new LinkedHashMap<>();
            for (Path sweep : sweeps) {
                latency.putAll(loadLatency(sweep));
            }
            for (Curve curve : curves) {
                analyze(curve, starts, fits, points, latency, bound);
                checkLittlesLaw(curve, latency, little);
            }
        }
        LOGGER.info("Analyzed {} curves from {}", curves.size(), sweeps);
    }

    /** The response times of one size of one curve, over its forks. */
    record Latency(double meanUs, double p99Us, int clients, double waitUs, double leaseUs) {
    }

    /** A size counts as pool-limited when at least this share of its connections is busy on average. */
    static final double SATURATED = 0.9;

    private static String key(Curve curve, int size) {
        return curve.source() + "|" + curve.database() + "|" + curve.pool() + "|" + curve.workload() + "|" + size;
    }

    private static void analyze(Curve curve, CsvWriter starts, CsvWriter fits, CsvWriter points,
                                Map<String, Latency> latency, CsvWriter bound) {
        double[] sizes = curve.sizes();
        double[] means = curve.means();
        Estimate estimate = estimate(sizes, means, curve.maxSize());
        if (estimate == null) {
            LOGGER.warn("{}: the fit failed", curve.label());
            return;
        }
        checkResponseTimeBound(curve, sizes, means, estimate, latency, bound);
        UslModel lm = estimate.lm().model();
        UslModel lease = estimate.lease() == null ? null : estimate.lease().model();

        // The bootstrap over the forks: the distribution of every estimate.
        int t = TOLERANCES.length;
        List<double[]> measuredDraws = new ArrayList<>();
        List<double[]> fittedDraws = new ArrayList<>();
        List<Double> optimumDraws = new ArrayList<>();
        List<Double> peakDraws = new ArrayList<>();
        SplittableRandom random = new SplittableRandom(curve.label().hashCode());
        List<double[]> forkMeans = new ArrayList<>(curve.forkMeans().values());
        for (int b = 0; b < BOOTSTRAP && curve.forks() > 1; b++) {
            double[] resampled = new double[sizes.length];
            for (int i = 0; i < sizes.length; i++) {
                double[] forks = forkMeans.get(i);
                double sum = 0;
                for (int k = 0; k < forks.length; k++) {
                    sum += forks[random.nextInt(forks.length)];
                }
                resampled[i] = sum / forks.length;
            }
            peakDraws.add(Arrays.stream(resampled).max().orElse(Double.NaN));
            Estimate draw = estimate(sizes, resampled, curve.maxSize());
            if (draw != null) {
                measuredDraws.add(draw.measured());
                fittedDraws.add(draw.fitted());
                optimumDraws.add(draw.lm().model().optimalPoolSizeReal());
            }
        }

        double nhat = lm.optimalPoolSizeReal();
        double gamma = lm.kappa() > 0 ? (lm.sigma() - lm.kappa()) / Math.sqrt(lm.kappa() * (1 - lm.sigma()))
                : Double.POSITIVE_INFINITY;
        double[] optimumInterval = interval(optimumDraws.stream().mapToDouble(Double::doubleValue).toArray());
        double[] n005Interval = new double[]{Double.NaN, Double.NaN};
        // delta of H1a: the measurement noise of the peak, never below one percentage point.
        double peak = Arrays.stream(means).max().orElse(Double.NaN);
        double[] peakInterval = interval(peakDraws.stream().mapToDouble(Double::doubleValue).toArray());
        double peakHalfWidth = (peakInterval[1] - peakInterval[0]) / 2 / peak;
        double delta = Double.isFinite(peakHalfWidth) ? Math.max(H1A_MIN_DELTA, peakHalfWidth) : H1A_MIN_DELTA;
        for (int e = 0; e < t; e++) {
            double eps = TOLERANCES[e];
            final int index = e;
            double[] measuredInterval = interval(measuredDraws.stream().mapToDouble(d -> d[index]).toArray());
            double[] fittedInterval = interval(fittedDraws.stream().mapToDouble(d -> d[index]).toArray());
            if (eps == 0.05) {
                n005Interval = fittedInterval;
            }
            double measured = estimate.measured()[e];
            double fitted = estimate.fitted()[e];
            double share = shareAt(sizes, means, fitted);
            boolean h1a = share >= 1 - eps - delta;
            double shareHigh = Double.isFinite(fittedInterval[1]) ? shareAt(sizes, means, fittedInterval[1])
                    : Double.NaN;
            double gridSize = gridSizeAt(sizes, fitted);
            boolean intervals = Double.isFinite(measuredInterval[0]) && Double.isFinite(measuredInterval[1]);
            boolean h1b = intervals && gridSize >= measuredInterval[0] && gridSize <= measuredInterval[1];
            // The saving against the closed form, only when the fitted optimum lies inside the sweep.
            double ratioFitted = nhat <= curve.maxSize() ? measured / nhat : Double.NaN;
            double ratioBound = closedFormRatio(0, eps);
            starts.writeRow(curve.source(), curve.database(), curve.pool(), curve.workload(), sizes.length,
                    curve.forks(), eps, measured, measuredInterval[0], measuredInterval[1],
                    fitted, fittedInterval[0], fittedInterval[1],
                    estimate.fittedLease() == null ? "" : estimate.fittedLease()[e],
                    fitted - measured, Math.abs(fitted - measured) <= H1_CONNECTIONS, share,
                    Double.isFinite(gamma) ? closedFormRatio(gamma, eps) : "",
                    measured / estimate.measuredOptimum(), peakHalfWidth, delta, h1a,
                    Double.isFinite(shareHigh) ? shareHigh : "",
                    Double.isFinite(shareHigh) ? shareHigh >= 1 - eps - delta : "",
                    gridSize, intervals ? h1b : "",
                    Double.isFinite(ratioFitted) ? ratioFitted : "", ratioBound,
                    Double.isFinite(ratioFitted) ? ratioFitted <= ratioBound : "");
            LOGGER.info("{} eps={}: measured plateau start {} [{}, {}], fitted {} [{}, {}], lease-time fit {}; "
                            + "the fitted size delivers {}% of the measured peak (H1a needs {}%: {}), H1b {}",
                    curve.label(), eps, (int) measured, fmt(measuredInterval[0]), fmt(measuredInterval[1]),
                    (int) fitted, fmt(fittedInterval[0]), fmt(fittedInterval[1]),
                    estimate.fittedLease() == null ? "-" : (int) estimate.fittedLease()[e],
                    String.format("%.1f", 100 * share), String.format("%.1f", 100 * (1 - eps - delta)),
                    h1a ? "pass" : "FAIL", intervals ? (h1b ? "pass" : "FAIL") : "untested");
        }

        double n005Width = n005Interval[1] - n005Interval[0];
        double nstarWidth = optimumInterval[1] - optimumInterval[0];
        double n005 = estimate.fitted()[Arrays.binarySearch(TOLERANCES, 0.05)];
        fits.writeRow(curve.source(), curve.database(), curve.pool(), curve.workload(), sizes.length, curve.forks(),
                curve.maxSize(), lm.lambda(), lm.sigma(), lm.kappa(), estimate.lm().rSquared(),
                lease == null ? "" : lease.lambda(), lease == null ? "" : lease.sigma(),
                lease == null ? "" : lease.kappa(), lease == null ? "" : estimate.lease().rSquared(),
                nhat, lease == null ? "" : lease.optimalPoolSizeReal(), estimate.measuredOptimum(),
                optimumInterval[0], optimumInterval[1], gamma,
                lm.poolSizeAtElasticity(ETA, curve.maxSize()),
                lease == null ? "" : lease.poolSizeAtElasticity(ETA, curve.maxSize()),
                n005Width, nstarWidth, n005Width / n005, nstarWidth / nhat, n005Width < nstarWidth);
        LOGGER.info("{}: LM sigma={} kappa={} R2={} N^={} [{}, {}]; lease-time sigma={} kappa={} R2={}; measured "
                        + "N*={}; gamma={}; N_eta={}", curve.label(), fmt(lm.sigma()), fmt(lm.kappa()),
                fmt(estimate.lm().rSquared()), fmt(nhat), fmt(optimumInterval[0]), fmt(optimumInterval[1]),
                lease == null ? "-" : fmt(lease.sigma()), lease == null ? "-" : fmt(lease.kappa()),
                lease == null ? "-" : fmt(estimate.lease().rSquared()), (int) estimate.measuredOptimum(),
                fmt(gamma), lm.poolSizeAtElasticity(ETA, curve.maxSize()));

        for (Map.Entry<Integer, double[]> entry : curve.forkMeans().entrySet()) {
            double[] forks = entry.getValue();
            double[] jmh = curve.jmhForkMeans().get(entry.getKey());
            double jmhMean = jmh == null ? Double.NaN : mean(jmh);
            points.writeRow(curve.source(), curve.database(), curve.pool(), curve.workload(), entry.getKey(),
                    forks.length, mean(forks), sd(forks), lm.throughputAt(entry.getKey()),
                    lease == null ? "" : lease.throughputAt(entry.getKey()), curve.throughput(), jmhMean,
                    jmhMean / mean(forks));
        }
    }

    /**
     * The measured and fitted plateau starts of one curve, or {@code null} when the Levenberg-Marquardt fit fails.
     */
    static Estimate estimate(double[] sizes, double[] throughput, int maxSize) {
        UslFit lm;
        try {
            lm = UslFitter.fit(sizes, throughput);
        } catch (RuntimeException e) {
            return null;
        }
        UslFit lease = null;
        try {
            double[] leaseSeconds = new double[sizes.length];
            for (int i = 0; i < sizes.length; i++) {
                leaseSeconds[i] = sizes[i] / throughput[i];
            }
            lease = LeaseTimeFitter.fit(sizes, leaseSeconds);
        } catch (RuntimeException e) {
            // the lease-time fit is reported when it exists
        }
        int peak = 0;
        for (int i = 1; i < throughput.length; i++) {
            if (throughput[i] > throughput[peak]) {
                peak = i;
            }
        }
        double[] measured = new double[TOLERANCES.length];
        double[] fitted = new double[TOLERANCES.length];
        double[] fittedLease = lease == null ? null : new double[TOLERANCES.length];
        for (int e = 0; e < TOLERANCES.length; e++) {
            double goal = (1 - TOLERANCES[e]) * throughput[peak];
            for (int i = 0; i < throughput.length; i++) {
                if (throughput[i] >= goal) {
                    measured[e] = sizes[i];
                    break;
                }
            }
            fitted[e] = lm.model().poolSizeAtFraction(1 - TOLERANCES[e], maxSize);
            if (lease != null) {
                fittedLease[e] = lease.model().poolSizeAtFraction(1 - TOLERANCES[e], maxSize);
            }
        }
        return new Estimate(measured, sizes[peak], lm, lease, fitted, fittedLease);
    }

    /** {@code N_eps / N^} in closed form from the shape number (the article's Eq. 6), before rounding. */
    static double closedFormRatio(double gamma, double eps) {
        double b = 2 + eps * (2 + gamma) / (1 - eps);
        return (b - Math.sqrt(b * b - 4)) / 2;
    }

    /** The smallest swept size at or above {@code size}, or the largest swept size. */
    static double gridSizeAt(double[] sizes, double size) {
        for (double swept : sizes) {
            if (swept >= size) {
                return swept;
            }
        }
        return sizes[sizes.length - 1];
    }

    /** The measured throughput at the smallest swept size at or above {@code size}, over the measured peak. */
    static double shareAt(double[] sizes, double[] throughput, double size) {
        double peak = Arrays.stream(throughput).max().orElse(Double.NaN);
        for (int i = 0; i < sizes.length; i++) {
            if (sizes[i] >= size) {
                return throughput[i] / peak;
            }
        }
        return throughput[throughput.length - 1] / peak;
    }

    /**
     * The response times of a sweep's {@code -latency.csv}, by {@link #key}: the count-weighted mean of the forks'
     * means, the highest p99 of a fork, and the clients of the closed loop (the {@code clients} column, or the
     * default {@code min(N + 16, 128)} of the sweeps that predate it).
     */
    static Map<String, Latency> loadLatency(Path sweep) {
        String source = sweep.getFileName().toString().replaceFirst("\\.json$", "");
        Path csv = sweep.resolveSibling(source + "-latency.csv");
        Map<String, Latency> latency = new LinkedHashMap<>();
        if (!Files.exists(csv)) {
            return latency;
        }
        Map<String, double[]> sums = new LinkedHashMap<>();
        try {
            List<String> lines = Files.readAllLines(csv);
            List<String> header = Arrays.asList(lines.get(0).split(","));
            int clientsColumn = header.indexOf("clients");
            for (String line : lines.subList(1, lines.size())) {
                String[] cells = line.split(",");
                int size = Integer.parseInt(cells[header.indexOf("poolSize")]);
                String key = source + "|" + cells[header.indexOf("database")] + "|" + cells[header.indexOf("pool")]
                        + "|" + cells[header.indexOf("workload")] + "|" + size;
                double count = Double.parseDouble(cells[header.indexOf("count")]);
                double[] sum = sums.computeIfAbsent(key, k -> new double[6]);
                sum[0] += Double.parseDouble(cells[header.indexOf("mean_us")]) * count;
                sum[1] += count;
                sum[2] = Math.max(sum[2], Double.parseDouble(cells[header.indexOf("p99_us")]));
                sum[3] = clientsColumn >= 0 && clientsColumn < cells.length
                        ? Double.parseDouble(cells[clientsColumn]) : Math.min(size + 16, 128);
                int waitColumn = header.indexOf("mean_wait_us");
                int leaseColumn = header.indexOf("mean_lease_us");
                if (waitColumn >= 0 && leaseColumn >= 0 && leaseColumn < cells.length) {
                    sum[4] += Double.parseDouble(cells[waitColumn]) * count;
                    sum[5] += Double.parseDouble(cells[leaseColumn]) * count;
                } else {
                    sum[4] = Double.NaN;
                    sum[5] = Double.NaN;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        sums.forEach((key, sum) -> {
            if (sum[1] > 0) {
                latency.put(key, new Latency(sum[0] / sum[1], sum[2], (int) sum[3], sum[4] / sum[1],
                        sum[5] / sum[1]));
            }
        });
        return latency;
    }

    /** Little's Law against the measured mean response time: {@code R = M / X} in the closed loop of {@code M}. */
    private static void checkLittlesLaw(Curve curve, Map<String, Latency> latency, CsvWriter out) {
        double worst = Double.NaN;
        List<Integer> unsaturated = new ArrayList<>();
        for (Map.Entry<Integer, double[]> entry : curve.forkMeans().entrySet()) {
            Latency measured = latency.get(key(curve, entry.getKey()));
            if (measured == null) {
                continue;
            }
            double tps = mean(entry.getValue());
            double predicted = measured.clients() / tps * 1e6;
            double ratio = measured.meanUs() / predicted;
            worst = Double.isNaN(worst) ? Math.abs(ratio - 1) : Math.max(worst, Math.abs(ratio - 1));
            // Little's Law per part of the response time: busy connections and waiting requests.
            double busy = tps * measured.leaseUs() / 1e6;
            double queue = tps * measured.waitUs() / 1e6;
            if (busy < SATURATED * entry.getKey()) {
                unsaturated.add(entry.getKey());
            }
            out.writeRow(curve.source(), curve.database(), curve.pool(), curve.workload(), entry.getKey(),
                    measured.clients(), tps, measured.meanUs(), predicted, ratio, measured.p99Us(), measured.waitUs(),
                    measured.leaseUs(), busy, busy / entry.getKey(), queue);
        }
        if (!Double.isNaN(worst)) {
            LOGGER.info("{}: the measured mean response time departs from Little's Law by at most {}%",
                    curve.label(), String.format("%.1f", 100 * worst));
        }
        if (!unsaturated.isEmpty()) {
            LOGGER.warn("{}: fewer than {}% of the connections were busy at the sizes {}: those points measure the "
                    + "client, not the pool", curve.label(), (int) (100 * SATURATED), unsaturated);
        }
    }

    /**
     * The article's closed-loop claim, on a sweep with the same {@code M} clients at every size
     * ({@code -Dusl.sweep.clients}): the mean response time at the fitted plateau start, over the one at the measured
     * optimum, against the throughput ratio and the bound {@code 1 / (1 - eps)}; and the p99 ratio, which the bound
     * does not cover. The fitted plateau start is read at the smallest swept size at or above it.
     */
    private static void checkResponseTimeBound(Curve curve, double[] sizes, double[] means, Estimate estimate,
                                               Map<String, Latency> latency, CsvWriter out) {
        Latency atOptimum = latency.get(key(curve, (int) estimate.measuredOptimum()));
        if (atOptimum == null) {
            return;
        }
        for (double size : sizes) {
            Latency l = latency.get(key(curve, (int) size));
            if (l == null || l.clients() != atOptimum.clients()) {
                return; // not a fixed-clients sweep
            }
        }
        for (int e = 0; e < TOLERANCES.length; e++) {
            double eps = TOLERANCES[e];
            int index = 0;
            while (index < sizes.length - 1 && sizes[index] < estimate.fitted()[e]) {
                index++;
            }
            Latency atStart = latency.get(key(curve, (int) sizes[index]));
            double peak = Arrays.stream(means).max().orElse(Double.NaN);
            double meanRatio = atStart.meanUs() / atOptimum.meanUs();
            // Little's Law in the closed loop of M: R = M / X, so R(N_eps) / R(N*) = X(N*) / X(N_eps).
            double littlesRatio = peak / means[index];
            out.writeRow(curve.source(), curve.database(), curve.pool(), curve.workload(), atOptimum.clients(), eps,
                    estimate.fitted()[e], sizes[index], estimate.measuredOptimum(), littlesRatio, 1 / (1 - eps),
                    littlesRatio <= 1 / (1 - eps), meanRatio, meanRatio <= 1 / (1 - eps),
                    atStart.p99Us() / atOptimum.p99Us());
            LOGGER.info("{} with {} clients, eps={}: R(N_eps={})/R(N*={}) = {} by Little's Law (bound {}), measured "
                            + "means {}, p99 ratio {}", curve.label(), atOptimum.clients(), eps, (int) sizes[index],
                    (int) estimate.measuredOptimum(), String.format("%.3f", littlesRatio),
                    String.format("%.3f", 1 / (1 - eps)), String.format("%.3f", meanRatio),
                    String.format("%.3f", atStart.p99Us() / atOptimum.p99Us()));
        }
    }

    /** The sweeps to analyze: {@code usl.analysis.sweeps}, else every sweep JSON in {@code metrics/jmh-results}. */
    static List<Path> sweeps() {
        String configured = System.getProperty("usl.analysis.sweeps");
        if (configured != null && !configured.isBlank()) {
            return Arrays.stream(configured.split(",")).map(String::trim).map(Path::of).toList();
        }
        Path results = CsvWriter.metricsPath("jmh-results", "x").getParent();
        if (!Files.isDirectory(results)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(results)) {
            return files.filter(f -> f.getFileName().toString().matches("ThroughputVsPoolSizeBenchmark.*\\.json"))
                    .sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The curves of one JMH JSON: the fork means of every (database, pool, workload, pool size). */
    @SuppressWarnings("unchecked")
    static List<Curve> load(Path json) {
        String source = json.getFileName().toString().replaceFirst("\\.json$", "");
        List<Object> entries;
        try {
            entries = (List<Object>) JsonReader.parse(Files.readString(json));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, Curve> curves = new LinkedHashMap<>();
        for (Object item : entries) {
            Map<String, Object> entry = (Map<String, Object>) item;
            if (!String.valueOf(entry.get("benchmark")).endsWith(".transaction")) {
                continue;
            }
            Map<String, Object> params = (Map<String, Object>) entry.get("params");
            String database = String.valueOf(params.getOrDefault("database", "postgresql"));
            String pool = String.valueOf(params.get("pool"));
            String workload = String.valueOf(params.get("workload"));
            int size = Integer.parseInt(String.valueOf(params.get("poolSize")));
            List<Object> raw = (List<Object>) ((Map<String, Object>) entry.get("primaryMetric")).get("rawData");
            double[] forkMeans = raw.stream()
                    .mapToDouble(fork -> ((List<Object>) fork).stream().mapToDouble(v -> (Double) v).average()
                            .orElse(Double.NaN))
                    .filter(Double::isFinite)
                    .toArray();
            if (forkMeans.length == 0) {
                continue;
            }
            String key = database + "|" + pool + "|" + workload;
            curves.computeIfAbsent(key, k -> new Curve(source, database, pool, workload, new TreeMap<>(),
                            new TreeMap<>(), "jmh"))
                    .jmhForkMeans().put(size, forkMeans);
        }
        Map<String, double[]> window = JMH_THROUGHPUT ? Map.of() : loadWindowThroughput(json);
        List<Curve> result = new ArrayList<>();
        for (Curve curve : curves.values()) {
            if (curve.jmhForkMeans().size() < 4) {
                continue;
            }
            TreeMap<Integer, double[]> windowForks = new TreeMap<>();
            for (Integer size : curve.jmhForkMeans().keySet()) {
                double[] forks = window.get(key(curve, size));
                if (forks == null || forks.length == 0) {
                    windowForks = null;
                    break;
                }
                windowForks.put(size, forks);
            }
            if (windowForks == null) {
                if (!JMH_THROUGHPUT && !window.isEmpty()) {
                    LOGGER.warn("{}: a size has no window_tps, so the curve uses the JMH score", curve.label());
                }
                result.add(new Curve(source, curve.database(), curve.pool(), curve.workload(), curve.jmhForkMeans(),
                        curve.jmhForkMeans(), "jmh"));
                continue;
            }
            Curve windowed = new Curve(source, curve.database(), curve.pool(), curve.workload(), windowForks,
                    curve.jmhForkMeans(), "window");
            int worstSize = -1;
            double worst = 0;
            for (Map.Entry<Integer, double[]> entry : windowForks.entrySet()) {
                double ratio = mean(curve.jmhForkMeans().get(entry.getKey())) / mean(entry.getValue());
                if (ratio > worst) {
                    worst = ratio;
                    worstSize = entry.getKey();
                }
            }
            LOGGER.info("{}: window_tps throughput; the JMH score is up to {} times it (at {} connections)",
                    windowed.label(), String.format("%.2f", worst), worstSize);
            result.add(windowed);
        }
        return result;
    }

    /**
     * The {@code window_tps} of every fork in the sweep's {@code -latency.csv}, by {@link #key}, in the order of the
     * forks; empty when the file or the column is missing.
     */
    static Map<String, double[]> loadWindowThroughput(Path sweep) {
        String source = sweep.getFileName().toString().replaceFirst("\\.json$", "");
        Path csv = sweep.resolveSibling(source + "-latency.csv");
        if (!Files.exists(csv)) {
            return Map.of();
        }
        Map<String, List<Double>> forks = new LinkedHashMap<>();
        try {
            List<String> lines = Files.readAllLines(csv);
            List<String> header = Arrays.asList(lines.get(0).split(","));
            int column = header.indexOf("window_tps");
            if (column < 0) {
                return Map.of();
            }
            for (String line : lines.subList(1, lines.size())) {
                String[] cells = line.split(",");
                if (column >= cells.length) {
                    continue;
                }
                double tps = Double.parseDouble(cells[column]);
                if (!Double.isFinite(tps)) {
                    continue;
                }
                String key = source + "|" + cells[header.indexOf("database")] + "|" + cells[header.indexOf("pool")]
                        + "|" + cells[header.indexOf("workload")] + "|"
                        + Integer.parseInt(cells[header.indexOf("poolSize")]);
                forks.computeIfAbsent(key, k -> new ArrayList<>()).add(tps);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, double[]> result = new LinkedHashMap<>();
        forks.forEach((key, values) -> result.put(key, values.stream().mapToDouble(Double::doubleValue).toArray()));
        return result;
    }

    /** The 95% percentile interval of the draws, {@code {NaN, NaN}} without draws. */
    static double[] interval(double[] draws) {
        if (draws.length == 0) {
            return new double[]{Double.NaN, Double.NaN};
        }
        double[] sorted = draws.clone();
        Arrays.sort(sorted);
        return new double[]{quantile(sorted, 0.025), quantile(sorted, 0.975)};
    }

    private static double quantile(double[] sorted, double q) {
        double position = q * (sorted.length - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        double weight = position - lower;
        return sorted[lower] * (1 - weight) + sorted[upper] * weight;
    }

    static double mean(double[] values) {
        return Arrays.stream(values).average().orElse(Double.NaN);
    }

    private static double sd(double[] values) {
        if (values.length < 2) {
            return 0;
        }
        double mean = mean(values);
        return Math.sqrt(Arrays.stream(values).map(v -> (v - mean) * (v - mean)).sum() / (values.length - 1));
    }

    private static String fmt(double value) {
        return Double.isFinite(value) ? String.format("%.4g", value) : String.valueOf(value);
    }
}
