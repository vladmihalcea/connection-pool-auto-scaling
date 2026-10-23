package com.vladmihalcea.phd.pool.controller;

import com.vladmihalcea.phd.pool.sim.PolicyRunner;
import com.vladmihalcea.phd.pool.sim.SimulatedSystem;
import com.vladmihalcea.phd.pool.usl.SizingTarget;
import com.vladmihalcea.phd.pool.usl.UslModel;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioral tests of the elastic response-time controller on the simulator: under a steady load it sweeps the
 * pool sizes, finds {@code Nmax}, and settles at the point of diminishing returns below it; it follows the demand
 * down and back up without sweeping a load it already knows; it reuses a validated model at a new load level; it
 * re-characterizes after a regime shift; and it honors a tolerance target and a lease-time target.
 *
 * @author Vlad Mihalcea
 */
public class UslResponseTimeControllerTest {

    private static final int MIN = 2;
    private static final int MAX = 96;
    private static final UslModel TRUTH = new UslModel(1500, 0.03, 0.0005); // N* = 44

    @Test
    public void findsNmaxAndSettlesAtThePointOfDiminishingReturns() {
        int expected = SizingTarget.DEFAULT.poolSize(TRUTH, MAX);
        SimulatedSystem system = new SimulatedSystem(TRUTH, 128, 0.02, 7L);
        UslResponseTimeController controller = new UslResponseTimeController(MIN, MAX);
        List<PolicyRunner.Step> trajectory = PolicyRunner.run(controller, system, MIN, 80, -1, null);
        double settled = median(trajectory, 60, 80);
        assertTrue(Math.abs(settled - expected) <= 3, "settled at " + settled + ", N_eta = " + expected);
        assertEquals(1, controller.sweeps());
        assertTrue(Math.abs(controller.lastEstimatedOptimum() - TRUTH.optimalPoolSize()) <= 5,
                "estimated N* " + controller.lastEstimatedOptimum());
        assertTrue(controller.measuredPeak() > 0, "the sweep should pass the peak at 128 clients");
        assertTrue(settled < controller.lastEstimatedOptimum(), "the target lies below Nmax");
        assertTrue(PolicyRunner.steadyStateThroughput(trajectory, 20) >= 0.96 * TRUTH.peakThroughput());
    }

    @Test
    public void honorsAToleranceTarget() {
        SizingTarget target = new SizingTarget.Tolerance(0.05);
        int expected = target.poolSize(TRUTH, MAX);
        UslResponseTimeController controller = new UslResponseTimeController(
                UslResponseTimeController.Config.defaults(MIN, MAX, target));
        List<PolicyRunner.Step> trajectory = PolicyRunner.run(controller, new SimulatedSystem(TRUTH, 128, 0.02, 21L),
                MIN, 80, -1, null);
        double settled = median(trajectory, 60, 80);
        assertTrue(Math.abs(settled - expected) <= 3, "settled at " + settled + ", N_0.05 = " + expected);
    }

    @Test
    public void followsTheDemandAndRemembersTheLoad() {
        SimulatedSystem system = new SimulatedSystem(TRUTH, 128, 0.02, 11L);
        UslResponseTimeController controller = new UslResponseTimeController(MIN, MAX);
        int size = MIN;
        int[] clients = {128, 12, 128};
        double[] settled = new double[clients.length];
        for (int phase = 0; phase < clients.length; phase++) {
            system.setClientThreads(clients[phase]);
            double sum = 0;
            for (int w = 0; w < 60; w++) {
                size = controller.decide(system.sample(size));
                if (w >= 40) {
                    sum += size;
                }
            }
            settled[phase] = sum / 20;
        }
        // ceil(12 * 1.2) + 1 = 16, within the dead band of 2.
        assertTrue(settled[1] <= 18, "with 12 clients the pool should shrink, got " + settled[1]);
        assertTrue(Math.abs(settled[2] - SizingTarget.DEFAULT.poolSize(TRUTH, MAX)) <= 3, "regrown to " + settled[2]);
        assertEquals(1, controller.sweeps(), "the second 128-client episode should reuse the first sweep");
    }

    @Test
    public void reusesAValidatedModelAtANewLoadLevel() {
        SimulatedSystem system = new SimulatedSystem(TRUTH, 128, 0.02, 17L);
        UslResponseTimeController controller = new UslResponseTimeController(MIN, MAX);
        int size = MIN;
        for (int w = 0; w < 60; w++) {
            size = controller.decide(system.sample(size));
        }
        system.setClientThreads(64);
        for (int w = 0; w < 40; w++) {
            size = controller.decide(system.sample(size));
        }
        assertEquals(1, controller.sweeps(), "64 clients on the same curve should not need a second sweep");
        assertEquals(1, controller.reuses());
        assertTrue(Math.abs(size - SizingTarget.DEFAULT.poolSize(TRUTH, MAX)) <= 3, "held " + size);
    }

    @Test
    public void reCharacterizesAfterARegimeShift() {
        UslModel phase2 = new UslModel(1500, 0.06, 0.0012); // N* = 27
        SimulatedSystem system = new SimulatedSystem(TRUTH, 128, 0.02, 13L);
        UslResponseTimeController controller = new UslResponseTimeController(MIN, MAX);
        List<PolicyRunner.Step> trajectory = PolicyRunner.run(controller, system, MIN, 160, 80, phase2);
        double settled = median(trajectory, 140, 160);
        int expected = SizingTarget.DEFAULT.poolSize(phase2, MAX);
        assertTrue(Math.abs(settled - expected) <= 3, "after the shift settled at " + settled + ", N_eta = " + expected);
        assertEquals(2, controller.sweeps());
    }

    @Test
    public void respectsALeaseTimeTarget() {
        double target = TRUTH.responseTimeAt(20);
        UslResponseTimeController controller = new UslResponseTimeController(
                UslResponseTimeController.Config.defaults(MIN, MAX).withLeaseTimeTarget(target));
        List<PolicyRunner.Step> trajectory = PolicyRunner.run(controller, new SimulatedSystem(TRUTH, 128, 0.02, 5L),
                MIN, 80, -1, null);
        double settled = median(trajectory, 60, 80);
        assertTrue(settled <= 22 && settled >= 17, "with a lease-time target the pool should hold about 20, got " + settled);
    }

    @Test
    public void doesNotSweepALightLoad() {
        SimulatedSystem system = new SimulatedSystem(TRUTH, 6, 0.02, 3L);
        UslResponseTimeController controller = new UslResponseTimeController(MIN, MAX);
        List<PolicyRunner.Step> trajectory = PolicyRunner.run(controller, system, MIN, 40, -1, null);
        assertEquals(0, controller.sweeps());
        assertTrue(median(trajectory, 20, 40) <= 9, "6 clients need at most ceil(6 * 1.2) + 1 = 9 connections");
    }

    static double median(List<PolicyRunner.Step> trajectory, int from, int to) {
        int[] sizes = trajectory.subList(from, to).stream().mapToInt(PolicyRunner.Step::poolSize).toArray();
        Arrays.sort(sizes);
        return sizes[sizes.length / 2];
    }
}
