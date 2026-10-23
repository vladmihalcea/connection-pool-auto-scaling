package com.vladmihalcea.phd.pool.flexypool;

import com.vladmihalcea.phd.pool.common.util.Database;
import com.vladmihalcea.phd.pool.util.CsvWriter;
import com.vladmihalcea.phd.pool.workload.OltpWorkload;
import com.vladmihalcea.phd.pool.workload.Schema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@link UslPlateauConnectionAcquisitionStrategy} live on every pool and database: HikariCP, DBCP2 and
 * Agroal (through {@link AgroalPoolAdapter}), each on PostgreSQL and MySQL, under the writeHeavy OLTP
 * workload. Each case checks that an online USL fit is accepted, that the plateau start lies below the
 * fitted optimum, and that the pool settles at the plateau start, which needs both growing (the bootstrap
 * ladder) and shrinking (from the top rung back to {@code N_eps}). The results are written to
 * {@code metrics/FlexyPoolPlateau/live-summary.csv}.
 * <p>
 * Each case runs for 90 s: HikariCP opens about one connection per second against the local PostgreSQL
 * on Windows, so the bootstrap alone can take ~40 s. Database-gated: a database that is neither local nor
 * available through Docker is skipped.
 *
 * @author Vlad Mihalcea
 */
public class FlexyPoolPlateauExperimentTest {

    private static final int MIN_SIZE = 2;
    private static final int MAX_SIZE = 48;
    private static final int CLIENT_THREADS = 64;
    private static final double TOLERANCE = 0.05;
    private static final long RUN_MILLIS = Long.getLong("usl.live.runMillis", 90_000);
    private static final long OBSERVE_FROM_MILLIS = RUN_MILLIS - 25_000;
    private static final int SCALE = 10;

    private static final List<LivePlateauRun.Result> RESULTS = new ArrayList<>();

    /** All databases and pools, optionally narrowed with {@code -Dusl.live.databases} / {@code -Dusl.live.pools}. */
    static Stream<Arguments> cases() {
        String databases = System.getProperty("usl.live.databases", "");
        String pools = System.getProperty("usl.live.pools", "");
        List<Arguments> cases = new ArrayList<>();
        for (Database database : Database.values()) {
            for (String pool : LivePlateauRun.POOLS) {
                if ((databases.isEmpty() || databases.contains(database.id()))
                        && (pools.isEmpty() || pools.contains(pool))) {
                    cases.add(Arguments.of(database, pool));
                }
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("cases")
    public void plateauStrategySettlesAtThePlateauStart(Database database, String pool) throws Exception {
        database.assumeAvailable();
        // A fresh table per case: the write-heavy mix updates 10,000 rows ~15,000 times a second, and the
        // dead row versions it leaves slow PostgreSQL down within minutes, which a later case would inherit.
        createSchema(database);

        UslPlateauConnectionAcquisitionStrategy.Config config =
                UslPlateauConnectionAcquisitionStrategy.Config.defaults(MIN_SIZE, MAX_SIZE, TOLERANCE);
        OltpWorkload workload = OltpWorkload.byName("writeHeavy", new Schema(SCALE).accountCount());
        LivePlateauRun.Result result = new LivePlateauRun(database, pool, workload.name(),
                (connection, random) -> workload.runTransaction(connection, random::nextInt),
                config, CLIENT_THREADS, RUN_MILLIS, OBSERVE_FROM_MILLIS).run();
        synchronized (RESULTS) {
            RESULTS.add(result);
        }

        assertTrue(result.committed() > 0, "the closed loop should commit transactions");
        assertNotNull(result.fit(), "an online USL fit should have been accepted");
        assertTrue(result.plateauStart() >= MIN_SIZE && result.plateauStart() <= result.optimum(),
                "the plateau start must lie in range and below N*=" + result.optimum()
                        + ", got " + result.plateauStart());
        int slack = config.controllerConfig().stepLimit() + config.controllerConfig().deadBand();
        assertTrue(Math.abs(result.settledSize() - result.plateauStart()) <= slack,
                "the pool should settle at the estimated plateau start " + result.plateauStart()
                        + ", got " + result.settledSize());
    }

    private static void createSchema(Database database) {
        DataSource admin = database.adminDataSource();
        try {
            new Schema(SCALE).create(admin);
        } finally {
            if (admin instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception ignore) {
                    // best-effort close of the one-off admin pool
                }
            }
        }
    }

    @AfterAll
    public static void writeSummary() {
        if (RESULTS.isEmpty()) {
            return;
        }
        try (CsvWriter out = new CsvWriter(CsvWriter.metricsPath("FlexyPoolPlateau", "live-summary.csv"),
                "database", "pool", "workload", "ceiling", "settled_size", "plateau_start", "nstar",
                "tps", "committed", "failures", "lambda", "sigma", "kappa", "r2")) {
            for (LivePlateauRun.Result r : RESULTS) {
                out.writeRow(r.database(), r.pool(), r.workload(), r.ceiling(), r.settledSize(),
                        r.plateauStart(), r.optimum(), r.observedThroughput(), r.committed(), r.failures(),
                        r.fit() == null ? Double.NaN : r.fit().model().lambda(),
                        r.fit() == null ? Double.NaN : r.fit().model().sigma(),
                        r.fit() == null ? Double.NaN : r.fit().model().kappa(),
                        r.fit() == null ? Double.NaN : r.fit().rSquared());
            }
        }
    }
}
