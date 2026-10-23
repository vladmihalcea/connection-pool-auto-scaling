package com.vladmihalcea.phd.pool.controller;

import com.vladmihalcea.phd.pool.sim.PolicyRunner;
import com.vladmihalcea.phd.pool.sim.SimulatedSystem;
import com.vladmihalcea.phd.pool.usl.UslModel;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioral tests for the plateau-targeting USL controller against the deterministic simulator: it must
 * settle at the start of the plateau {@code N_eps} rather than at {@code N*}, keep near-peak throughput
 * with fewer connections than the {@code N*} controller, shrink and grow to follow a moved plateau start, and not
 * fit demand-limited windows. No Docker required.
 *
 * @author Vlad Mihalcea
 */
public class UslPlateauControllerTest {

    private static final int MIN = 2;
    private static final int MAX = 96;
    private static final int CLIENT_THREADS = 128;
    private static final double TOLERANCE = 0.05;

    @Test
    public void convergesToThePlateauStart() {
        // N* = 81, N_0.05 = 56
        UslModel truth = new UslModel(1500, 0.01, 0.00015);
        int expectedPlateauStart = truth.poolSizeAtFraction(1 - TOLERANCE);

        SimulatedSystem system = new SimulatedSystem(truth, CLIENT_THREADS, 0.02, 7L);
        UslPlateauController controller = new UslPlateauController(MIN, MAX);
        List<PolicyRunner.Step> trajectory = PolicyRunner.run(controller, system, 8, 60, -1, null);

        double settledSize = medianPoolSize(trajectory, 45, 60);
        assertTrue(Math.abs(settledSize - expectedPlateauStart) <= 6,
                "controller should settle near N_eps=" + expectedPlateauStart + " but settled at " + settledSize);
        assertTrue(controller.lastEstimatedOptimum() > controller.lastEstimatedPlateauStart(),
                "the plateau start must lie below the estimated optimum");
    }

    @Test
    public void keepsNearPeakThroughputWithFewerConnections() {
        // N* = 44, N_0.05 = 32
        UslModel truth = new UslModel(1500, 0.01, 0.0005);

        List<PolicyRunner.Step> plateauRun = PolicyRunner.run(new UslPlateauController(MIN, MAX),
                new SimulatedSystem(truth, CLIENT_THREADS, 0.02, 21L), 8, 80, -1, null);
        List<PolicyRunner.Step> peakRun = PolicyRunner.run(new UslAutoScalingController(MIN, MAX),
                new SimulatedSystem(truth, CLIENT_THREADS, 0.02, 21L), 8, 80, -1, null);

        double plateauTps = PolicyRunner.steadyStateThroughput(plateauRun, 20);
        double peakTps = PolicyRunner.steadyStateThroughput(peakRun, 20);
        double plateauSize = PolicyRunner.steadyStatePoolSize(plateauRun, 20);
        double peakSize = PolicyRunner.steadyStatePoolSize(peakRun, 20);

        assertTrue(plateauTps >= (1 - TOLERANCE - 0.02) * peakTps,
                "plateau throughput " + plateauTps + " should stay within the tolerance of " + peakTps);
        assertTrue(plateauSize <= 0.85 * peakSize,
                "plateau pool " + plateauSize + " should use clearly fewer connections than " + peakSize);
    }

    @Test
    public void followsThePlateauStartAcrossRegimeShifts() {
        // Phase 1: N_0.05 = 56. Phase 2: N_0.05 = 26 (shrink). Phase 3 back to phase 1 (grow).
        UslModel phase1 = new UslModel(1500, 0.01, 0.00015);
        UslModel phase2 = new UslModel(1500, 0.02, 0.0007);
        int plateau1 = phase1.poolSizeAtFraction(1 - TOLERANCE);
        int plateau2 = phase2.poolSizeAtFraction(1 - TOLERANCE);

        SimulatedSystem system = new SimulatedSystem(phase1, CLIENT_THREADS, 0.02, 11L);
        UslPlateauController controller = new UslPlateauController(MIN, MAX);
        List<PolicyRunner.Step> first = PolicyRunner.run(controller, system, 8, 120, 60, phase2);

        double phase1Size = medianPoolSize(first, 45, 60);
        double phase2Size = medianPoolSize(first, 105, 120);
        assertTrue(Math.abs(phase1Size - plateau1) <= 6, "phase 1 plateau start " + plateau1 + ", got " + phase1Size);
        assertTrue(Math.abs(phase2Size - plateau2) <= 6, "phase 2 plateau start " + plateau2 + ", got " + phase2Size);

        // Continue from the phase-2 operating point and switch back: the controller must grow again.
        system.setTruth(phase1);
        int size = first.get(first.size() - 1).poolSize();
        double[] sizes = new double[60];
        for (int w = 0; w < 60; w++) {
            size = controller.decide(system.sample(size));
            sizes[w] = size;
        }
        double regrown = median(Arrays.copyOfRange(sizes, 45, 60));
        assertTrue(Math.abs(regrown - plateau1) <= 6, "should grow back to " + plateau1 + ", got " + regrown);
    }

    @Test
    public void doesNotFitDemandLimitedWindows() {
        // Demand at 60% of the peak: the pool only limits throughput at small sizes.
        UslModel truth = new UslModel(1500, 0.01, 0.00015);
        double demand = 0.6 * truth.peakThroughput();
        int plateauStartOfTruth = truth.poolSizeAtFraction(1 - TOLERANCE);

        SimulatedSystem system = new SimulatedSystem(truth, CLIENT_THREADS, 0.02, 3L);
        system.setDemand(demand);
        UslPlateauController controller = new UslPlateauController(MIN, MAX);
        List<PolicyRunner.Step> trajectory = PolicyRunner.run(controller, system, 8, 60, -1, null);

        double settledSize = medianPoolSize(trajectory, 45, 60);
        double settledTps = PolicyRunner.steadyStateThroughput(trajectory, 15);
        assertTrue(settledSize < plateauStartOfTruth,
                "a demand-limited pool needs fewer connections than the plateau start " + plateauStartOfTruth
                        + ", got " + settledSize);
        assertTrue(settledTps >= 0.95 * demand, "the demand must still be served: " + settledTps);
        assertEquals(UslPlateauController.Load.DEMAND_LIMITED, controller.classify(system.sample((int) settledSize)),
                "the settled pool should not be the bottleneck");
    }

    @Test
    public void holdsWhileThePoolIsStillResizing() {
        UslPlateauController controller = new UslPlateauController(MIN, MAX);
        // 48 allowed, 18 open and busy, 46 threads waiting: HikariCP has not filled the pool yet.
        WindowSample growing = new WindowSample(48, 37_000, 0, 46, 18);
        assertEquals(UslPlateauController.Load.RESIZING, controller.classify(growing));
        assertEquals(48, controller.decide(growing));
        // 32 allowed, 44 still busy: the connections above the cap have not been retired yet.
        WindowSample shrinking = new WindowSample(32, 45_000, 0, 14, 44);
        assertEquals(UslPlateauController.Load.RESIZING, controller.classify(shrinking));
        assertEquals(32, controller.decide(shrinking));
    }

    @Test
    public void smallSaturatedPoolsAllowAHandOffGap() {
        UslPlateauController controller = new UslPlateauController(MIN, MAX);
        // DBCP2 on PostgreSQL at 2 connections: 1.7 busy (85%) with 62 threads waiting is saturated.
        assertEquals(UslPlateauController.Load.SATURATED,
                controller.classify(new WindowSample(2, 6_700, 0, 62.3, 1.7)));
        // One connection open out of 2 with a queue is still a pool that has not filled.
        assertEquals(UslPlateauController.Load.RESIZING,
                controller.classify(new WindowSample(2, 4_000, 0, 63, 1.0)));
    }

    @Test
    public void steadyUnderUtilizationUnderAQueueIsSaturated() {
        UslPlateauController controller = new UslPlateauController(MIN, MAX);
        // DBCP2 on PostgreSQL at 6 connections: 4.9 busy (82%) with 59 waiting, window after window.
        WindowSample first = new WindowSample(6, 23_000, 0, 59, 4.9);
        assertEquals(UslPlateauController.Load.RESIZING, controller.classify(first));
        controller.decide(first);
        assertEquals(UslPlateauController.Load.SATURATED,
                controller.classify(new WindowSample(6, 23_400, 0, 59, 4.95)));
        // A HikariCP pool filling at ~1.2 connections per window keeps resizing.
        UslPlateauController filling = new UslPlateauController(MIN, MAX);
        filling.decide(new WindowSample(48, 44_000, 0, 30, 33.9));
        assertEquals(UslPlateauController.Load.RESIZING,
                filling.classify(new WindowSample(48, 44_700, 0, 28, 35.2)));
    }

    @Test
    public void littleFloorBoundsTheShrink() {
        UslPlateauController controller = new UslPlateauController(MIN, MAX);
        // 10,000 tx/s with 5 busy connections: W = 0.5 ms, so 10,000 tx/s needs 5 * 1.2 = 6 connections.
        controller.decide(new WindowSample(8, 10_000, 0, 0, 5));
        assertTrue(controller.littleFloor(10_000) == 6, "floor " + controller.littleFloor(10_000));
        assertTrue(controller.littleFloor(20_000) == 12, "floor " + controller.littleFloor(20_000));
    }

    private static double medianPoolSize(List<PolicyRunner.Step> trajectory, int fromWindow, int toWindow) {
        return median(trajectory.subList(fromWindow, toWindow).stream()
                .mapToDouble(PolicyRunner.Step::poolSize).toArray());
    }

    private static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int mid = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
    }
}
