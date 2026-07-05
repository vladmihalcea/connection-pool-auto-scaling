package com.vladmihalcea.phd.pool.jmh;

import com.vladmihalcea.phd.pool.controller.UslAutoScalingController;
import com.vladmihalcea.phd.pool.controller.WindowSample;
import com.vladmihalcea.phd.pool.sim.SimulatedSystem;
import com.vladmihalcea.phd.pool.usl.UslFitter;
import com.vladmihalcea.phd.pool.usl.UslModel;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Experiment E6: the CPU cost of the auto-scaler's estimation itself (claim C7 — the control overhead is
 * negligible next to a database transaction). Two costs are measured, both independent of the database
 * and therefore reproducible without Docker:
 * <ul>
 *   <li>{@code controllerDecision} — the per-window {@link UslAutoScalingController#decide} call in
 *       steady tracking state (EMA update + dead-band logic; the once-per-window path).</li>
 *   <li>{@code uslRefit} — one full Levenberg&ndash;Marquardt USL refit over the probe ladder (the
 *       heaviest thing the controller ever does, and only once per window).</li>
 * </ul>
 * Against a millisecond-scale OLTP transaction, both are shown to be a rounding error, so the controller
 * can run at a per-second cadence essentially for free. The DB-side proxy overhead (bare vs.
 * FlexyPool-wrapped acquisition) is measured separately by the Docker-backed harness.
 *
 * @author Vlad Mihalcea
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class OverheadBenchmark {

    private static final UslModel TRUTH = new UslModel(1500, 0.01, 0.0005);

    private UslAutoScalingController controller;
    private WindowSample steadySample;
    private double[] ladderSizes;
    private double[] ladderThroughput;

    @Setup(Level.Trial)
    public void setUp() {
        controller = new UslAutoScalingController(2, 96);
        // Warm the controller into steady tracking so decide() exercises the common per-window path.
        SimulatedSystem system = new SimulatedSystem(TRUTH, 96, 0.0, 1L);
        int size = 4;
        for (int w = 0; w < 40; w++) {
            size = controller.decide(system.sample(size));
        }
        steadySample = system.sample(size);

        ladderSizes = new double[]{2, 8, 15, 32, 63, 96};
        ladderThroughput = new double[ladderSizes.length];
        for (int i = 0; i < ladderSizes.length; i++) {
            ladderThroughput[i] = TRUTH.throughputAt(ladderSizes[i]);
        }
    }

    @Benchmark
    public int controllerDecision() {
        return controller.decide(steadySample);
    }

    @Benchmark
    public void uslRefit(Blackhole blackhole) {
        blackhole.consume(UslFitter.fit(ladderSizes, ladderThroughput));
    }
}
