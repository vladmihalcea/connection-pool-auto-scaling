package com.vladmihalcea.phd.pool.jmh;

import com.vladmihalcea.phd.pool.controller.GeneticPoolSizePolicy;
import com.vladmihalcea.phd.pool.controller.UslAutoScalingController;
import com.vladmihalcea.phd.pool.controller.UslResponseTimeController;
import com.vladmihalcea.phd.pool.controller.WindowSample;
import com.vladmihalcea.phd.pool.sim.SimulatedSystem;
import com.vladmihalcea.phd.pool.usl.LeaseTimeFitter;
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
 *   <li>{@code responseTimeDecision} — the per-window {@link UslResponseTimeController#decide} call in steady
 *       tracking state, which refits the lease-time law on every window.</li>
 *   <li>{@code leaseTimeRefit} — one lease-time fit ({@link LeaseTimeFitter}, linear least squares) over the
 *       same ladder.</li>
 *   <li>{@code geneticDecision} — the per-window {@link GeneticPoolSizePolicy#decide} call while it holds.</li>
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
    private UslResponseTimeController responseTimeController;
    private GeneticPoolSizePolicy genetic;
    private WindowSample responseTimeSample;
    private WindowSample geneticSample;
    private double[] ladderLeaseSeconds;
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

        responseTimeController = new UslResponseTimeController(2, 96);
        SimulatedSystem rtSystem = new SimulatedSystem(TRUTH, 128, 0.0, 1L);
        size = 2;
        for (int w = 0; w < 60; w++) {
            size = responseTimeController.decide(rtSystem.sample(size));
        }
        responseTimeSample = rtSystem.sample(size);

        genetic = new GeneticPoolSizePolicy(GeneticPoolSizePolicy.Config.defaults(2, 96));
        SimulatedSystem gaSystem = new SimulatedSystem(TRUTH, 128, 0.0, 1L);
        size = 2;
        for (int w = 0; w < 120; w++) {
            size = genetic.decide(gaSystem.sample(size));
        }
        geneticSample = gaSystem.sample(size);

        ladderSizes = new double[]{2, 8, 15, 32, 63, 96};
        ladderThroughput = new double[ladderSizes.length];
        ladderLeaseSeconds = new double[ladderSizes.length];
        for (int i = 0; i < ladderSizes.length; i++) {
            ladderThroughput[i] = TRUTH.throughputAt(ladderSizes[i]);
            ladderLeaseSeconds[i] = TRUTH.responseTimeAt(ladderSizes[i]);
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

    @Benchmark
    public int responseTimeDecision() {
        return responseTimeController.decide(responseTimeSample);
    }

    @Benchmark
    public void leaseTimeRefit(Blackhole blackhole) {
        blackhole.consume(LeaseTimeFitter.fit(ladderSizes, ladderLeaseSeconds));
    }

    @Benchmark
    public int geneticDecision() {
        return genetic.decide(geneticSample);
    }
}
