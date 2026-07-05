package com.vladmihalcea.phd.pool.report;

import com.vladmihalcea.phd.pool.adapter.JdbcConfig;
import com.vladmihalcea.phd.pool.adapter.PoolFactory;
import com.vladmihalcea.phd.pool.api.PoolUnderTest;
import com.vladmihalcea.phd.pool.common.util.PostgreSqlSupport;
import com.vladmihalcea.phd.pool.controller.UslAutoScalingController;
import com.vladmihalcea.phd.pool.sim.SimulatedSystem;
import com.vladmihalcea.phd.pool.usl.UslModel;
import com.vladmihalcea.phd.pool.util.CsvWriter;
import com.vladmihalcea.phd.pool.workload.Schema;
import org.junit.jupiter.api.Test;
import org.openjdk.jol.info.GraphLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Experiment E7: client-side memory footprints via JOL retained-size measurement. Two things are
 * reported:
 * <ol>
 *   <li>The auto-scaler's own estimator state after a full run — evidence that the control machinery is
 *       negligibly small (a handful of per-size throughput entries), so it adds no meaningful memory
 *       cost on top of the pool it manages.</li>
 *   <li>Each pool implementation's retained size at growing maximum sizes ({8, 32, 96}) — quantifying
 *       the client-side cost of oversizing, which compounds the well-known database-side per-backend
 *       cost. This section needs a database (local server or Docker) and is skipped without one.</li>
 * </ol>
 *
 * @author Vlad Mihalcea
 */
public class MemoryFootprintReportTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(MemoryFootprintReportTest.class);

    static {
        // JOL needs this to walk fields on JDK 17+ without add-opens for some library internals.
        System.setProperty("jol.magicFieldOffset", "true");
    }

    private static final int[] POOL_SIZES = {8, 32, 96};

    @Test
    public void measureMemoryFootprint() {
        Path csv = CsvWriter.metricsPath("MemoryFootprint", "memory-footprint.csv");
        try (CsvWriter out = new CsvWriter(csv, "component", "size", "retained_bytes")) {
            measureEstimator(out);
            measurePools(out);
        }
        writeGnuplot();
        LOGGER.info("Wrote memory footprint report to {}", csv);
    }

    private void measureEstimator(CsvWriter out) {
        UslAutoScalingController controller = new UslAutoScalingController(2, 96);
        SimulatedSystem system = new SimulatedSystem(new UslModel(1500, 0.01, 0.0005), 96, 0.02, 1L);
        int size = 4;
        for (int w = 0; w < 200; w++) {
            size = controller.decide(system.sample(size));
        }
        long retained = GraphLayout.parseInstance(controller).totalSize();
        out.writeRow("usl-estimator", "after-200-windows", retained);
        LOGGER.info("USL estimator retained size after 200 windows: {} bytes", retained);
    }

    private void measurePools(CsvWriter out) {
        if (!PostgreSqlSupport.isDatabaseAvailable()) {
            LOGGER.warn("No local database or Docker available - skipping the pool-internals memory section (estimator size still reported)");
            return;
        }

        new Schema(1).create(PostgreSqlSupport.adminDataSource());
        JdbcConfig jdbc = new JdbcConfig(
                PostgreSqlSupport.jdbcUrl(), PostgreSqlSupport.username(), PostgreSqlSupport.password());

        for (String poolId : PoolFactory.REAL_POOLS) {
            for (int size : POOL_SIZES) {
                try (PoolUnderTest pool = PoolFactory.create(poolId, jdbc, size)) {
                    warmUp(pool);
                    long retained = GraphLayout.parseInstance(pool.dataSource()).totalSize();
                    out.writeRow(poolId, size, retained);
                    LOGGER.info("{} @ maxSize {} retained {} bytes", poolId, size, retained);
                } catch (Exception e) {
                    LOGGER.warn("Could not measure {} @ {}: {}", poolId, size, e.toString());
                }
            }
        }
    }

    private void warmUp(PoolUnderTest pool) {
        try (var connection = pool.dataSource().getConnection();
             var statement = connection.prepareStatement("SELECT 1")) {
            statement.executeQuery().close();
        } catch (Exception e) {
            LOGGER.debug("warm-up query failed: {}", e.toString());
        }
    }

    private void writeGnuplot() {
        String gp = """
                # Experiment E7: retained client-side memory per component (JOL total size).
                # Input:  memory-footprint.csv (component, size, retained_bytes)
                # Output: memory-footprint.svg / .pdf
                set datafile separator ","
                set title "Retained client-side memory (JOL)" font ",13"
                set ylabel "Retained bytes (log scale)"
                set logscale y
                set format y "10^{%L}"
                set style data histograms
                set style fill solid 0.9 border -1
                set boxwidth 0.7
                set grid ytics lt 0 lw 0.5 lc rgb "#cccccc"
                set xtics rotate by -40 font ",8"
                unset key
                set terminal svg enhanced font "arial,11" size 1000,600
                set output 'memory-footprint.svg'
                plot 'memory-footprint.csv' using 3:xtic(sprintf("%s@%s", stringcolumn(1), stringcolumn(2))) lc rgb "#1565C0" notitle
                set terminal pdfcairo enhanced font "arial,9" size 8,5
                set output 'memory-footprint.pdf'
                replot
                set output
                """;
        writeFile(CsvWriter.metricsPath("MemoryFootprint", "memory-footprint.gp"), gp);
    }

    private static void writeFile(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
        } catch (Exception e) {
            throw new IllegalStateException("Could not write " + path, e);
        }
    }
}
