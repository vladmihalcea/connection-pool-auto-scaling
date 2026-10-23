package com.vladmihalcea.phd.pool.report;

import com.vladmihalcea.phd.pool.usl.UslModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the pieces of {@link PlateauStartReportTest} that the dense-sweep analysis rests on: the JSON reader on
 * JMH's format, the closed form of {@code N_eps / N^} in the shape number against {@link UslModel}'s quadratic root,
 * and the measured and fitted plateau starts of an exact USL curve.
 *
 * @author Vlad Mihalcea
 */
class PlateauStartReportCheckTest {

    @Test
    @SuppressWarnings("unchecked")
    void readsJmhJson() {
        Object parsed = JsonReader.parse("""
                [ { "benchmark" : "a.b.ThroughputVsPoolSizeBenchmark.transaction", "forks" : 2,
                    "params" : { "pool" : "hikari", "poolSize" : "8", "workload" : "readMostly" },
                    "primaryMetric" : { "score" : 1.5E4, "scoreConfidence" : [ NaN, -2.5e-3 ],
                        "rawData" : [ [ 1.0, 2.0 ], [ 3.0, 5.0 ] ], "unit" : "ops/s", "x" : null, "y" : true } } ]
                """);
        Map<String, Object> entry = (Map<String, Object>) ((List<Object>) parsed).get(0);
        assertEquals("8", ((Map<String, Object>) entry.get("params")).get("poolSize"));
        Map<String, Object> metric = (Map<String, Object>) entry.get("primaryMetric");
        assertEquals(15000.0, metric.get("score"));
        assertTrue(Double.isNaN((Double) ((List<Object>) metric.get("scoreConfidence")).get(0)));
        assertEquals(List.of(List.of(1.0, 2.0), List.of(3.0, 5.0)), metric.get("rawData"));
        assertEquals(Boolean.TRUE, metric.get("y"));
        assertTrue(metric.containsKey("x") && metric.get("x") == null);
    }

    @Test
    void closedFormRatioMatchesTheQuadraticRoot() {
        // The ICASC HikariCP read-mostly curve and two extreme shapes.
        for (UslModel model : List.of(new UslModel(7_000, 0.12, 4.3e-4), new UslModel(5_000, 0.0011, 3.2e-4),
                new UslModel(3_000, 0.2, 2.5e-3))) {
            double gamma = (model.sigma() - model.kappa()) / Math.sqrt(model.kappa() * (1 - model.sigma()));
            for (double eps : PlateauStartReportTest.TOLERANCES) {
                double fromRoot = model.poolSizeAtFractionReal(1 - eps) / model.optimalPoolSizeReal();
                assertEquals(fromRoot, PlateauStartReportTest.closedFormRatio(gamma, eps), 1e-9, model + " eps=" + eps);
            }
        }
        // The 0.724 bound of the pure-coherency shape at 5%.
        assertEquals(0.724, PlateauStartReportTest.closedFormRatio(0, 0.05), 5e-4);
    }

    @Test
    void exactCurveGivesTheSamePlateauStartMeasuredAndFitted() {
        UslModel truth = new UslModel(7_000, 0.12, 4.3e-4);
        double[] sizes = new double[64];
        double[] throughput = new double[64];
        for (int i = 0; i < 64; i++) {
            sizes[i] = i + 1;
            throughput[i] = truth.throughputAt(i + 1);
        }
        PlateauStartReportTest.Estimate estimate = PlateauStartReportTest.estimate(sizes, throughput, 64);
        assertNotNull(estimate);
        assertArrayEquals(estimate.measured(), estimate.fitted(), 1e-9);
        assertEquals(truth.optimalPoolSize(), (int) estimate.measuredOptimum());
        assertTrue(estimate.lm().rSquared() > 0.9999);
        assertNotNull(estimate.fittedLease());
        assertArrayEquals(estimate.fitted(), estimate.fittedLease(), 1e-9);
    }

    /** The revised H1: the plateau start on the sweep's grid, and the closed form's bound for sigma >= kappa. */
    @Test
    void readsTheFittedSizeOnTheGridAndTheRatioBound() {
        double[] sizes = {1, 2, 4, 8, 16, 24, 32, 48, 64, 96};
        assertEquals(24, PlateauStartReportTest.gridSizeAt(sizes, 17.2));
        assertEquals(24, PlateauStartReportTest.gridSizeAt(sizes, 24));
        assertEquals(96, PlateauStartReportTest.gridSizeAt(sizes, 120));
        // The plan's general bound: N_eps / N* <= 0.724 at 5%, 0.868 at 1%, 0.627 at 10% when sigma >= kappa.
        assertEquals(0.724, PlateauStartReportTest.closedFormRatio(0, 0.05), 5e-4);
        assertEquals(0.868, PlateauStartReportTest.closedFormRatio(0, 0.01), 5e-4);
        assertEquals(0.627, PlateauStartReportTest.closedFormRatio(0, 0.10), 5e-4);
    }

    @Test
    void loadsTheCurvesOfAJmhJson(@TempDir Path dir) throws IOException {
        StringBuilder json = new StringBuilder("[");
        for (int size = 1; size <= 4; size++) {
            if (size > 1) {
                json.append(',');
            }
            json.append("{\"benchmark\":\"x.ThroughputVsPoolSizeBenchmark.transaction\",\"params\":{\"database\":"
                    + "\"mysql\",\"pool\":\"hikari\",\"poolSize\":\"").append(size).append("\",\"workload\":\"w\"},"
                    + "\"primaryMetric\":{\"rawData\":[[").append(100 * size).append(',').append(100 * size + 10)
                    .append("],[").append(100 * size + 20).append(']').append("]}}");
        }
        json.append(']');
        Path file = dir.resolve("ThroughputVsPoolSizeBenchmark-dense-mysql-test.json");
        Files.writeString(file, json);
        List<PlateauStartReportTest.Curve> curves = PlateauStartReportTest.load(file);
        assertEquals(1, curves.size());
        PlateauStartReportTest.Curve curve = curves.get(0);
        assertEquals("mysql", curve.database());
        assertEquals(2, curve.forks());
        assertArrayEquals(new double[]{205, 220}, curve.forkMeans().get(2), 1e-9);
        assertArrayEquals(new double[]{1, 2, 3, 4}, curve.sizes(), 1e-9);
        assertEquals("jmh", curve.throughput());

        // With the sweep's -latency.csv, the throughput of a fork is its window_tps; the JMH score stays alongside.
        StringBuilder latency = new StringBuilder("database,pool,workload,poolSize,count,mean_us,p50_us,p90_us,p99_us,"
                + "p999_us,max_us,retries,clients,mean_wait_us,mean_lease_us,window_s,window_tps\n");
        for (int size = 1; size <= 4; size++) {
            for (int fork = 0; fork < 2; fork++) {
                latency.append("mysql,hikari,w,").append(size).append(",10,1,1,1,1,1,1,0,20,0,1,10.0,")
                        .append(90 * size + fork).append('\n');
            }
        }
        Files.writeString(dir.resolve("ThroughputVsPoolSizeBenchmark-dense-mysql-test-latency.csv"), latency);
        PlateauStartReportTest.Curve windowed = PlateauStartReportTest.load(file).get(0);
        assertEquals("window", windowed.throughput());
        assertArrayEquals(new double[]{180, 181}, windowed.forkMeans().get(2), 1e-9);
        assertArrayEquals(new double[]{205, 220}, windowed.jmhForkMeans().get(2), 1e-9);
    }
}
