package com.vladmihalcea.phd.pool.controller;

import com.vladmihalcea.phd.pool.sim.PolicyRunner;
import com.vladmihalcea.phd.pool.sim.SimulatedSystem;
import com.vladmihalcea.phd.pool.usl.UslModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioural tests for the USL auto-scaling controller against the deterministic simulator: it must
 * converge to the known optimum {@code N*}, re-track a moved optimum after a regime shift (including
 * shrinking, which the reactive baseline cannot), and deliver higher steady-state throughput than the
 * reactive latency-chasing policy. No Docker required.
 *
 * @author Vlad Mihalcea
 */
public class UslAutoScalingControllerTest {

    private static final int MIN = 2;
    private static final int MAX = 96;
    private static final int CLIENT_THREADS = 96;

    @Test
    public void convergesToTheOptimum() {
        // N* = sqrt((1 - 0.01)/0.00015) = 81
        UslModel truth = new UslModel(1500, 0.01, 0.00015);
        int expectedNStar = truth.optimalPoolSize();
        assertTrue(expectedNStar >= 78 && expectedNStar <= 84, "sanity on N*: " + expectedNStar);

        SimulatedSystem system = new SimulatedSystem(truth, CLIENT_THREADS, 0.02, 7L);
        UslAutoScalingController controller = new UslAutoScalingController(MIN, MAX);

        List<PolicyRunner.Step> trajectory =
                PolicyRunner.run(controller, system, 8, 60, -1, null);

        double settledSize = PolicyRunner.steadyStatePoolSize(trajectory, 15);
        assertTrue(Math.abs(settledSize - expectedNStar) <= 8,
                "controller should settle near N*=" + expectedNStar + " but settled at " + settledSize);
    }

    @Test
    public void reTracksAndShrinksAfterRegimeShift() {
        // Phase 1: N* = 81 (long service time). Phase 2: N* = sqrt(0.98/0.0007) ~= 37 (shorter, more coherency).
        UslModel phase1 = new UslModel(1500, 0.01, 0.00015);
        UslModel phase2 = new UslModel(1500, 0.02, 0.0007);
        int nStar2 = phase2.optimalPoolSize();

        SimulatedSystem system = new SimulatedSystem(phase1, CLIENT_THREADS, 0.02, 11L);
        UslAutoScalingController controller = new UslAutoScalingController(MIN, MAX);

        List<PolicyRunner.Step> trajectory =
                PolicyRunner.run(controller, system, 8, 120, 60, phase2);

        double phase1Plateau = averagePoolSize(trajectory, 45, 60);
        double phase2Settled = averagePoolSize(trajectory, 105, 120);

        assertTrue(phase1Plateau > 60, "phase 1 should operate at a large pool, got " + phase1Plateau);
        assertTrue(phase2Settled < phase1Plateau - 15,
                "controller must SHRINK after the optimum drops: " + phase1Plateau + " -> " + phase2Settled);
        assertTrue(Math.abs(phase2Settled - nStar2) <= 12,
                "controller should re-track N*=" + nStar2 + " but settled at " + phase2Settled);
    }

    @Test
    public void beatsReactivePolicyOnThroughput() {
        // N* = 44, comfortably below the client-thread count, so a latency-chasing policy overshoots it.
        UslModel truth = new UslModel(1500, 0.01, 0.0005);
        int nStar = truth.optimalPoolSize();
        assertTrue(nStar >= 40 && nStar <= 48, "sanity on N*: " + nStar);

        SimulatedSystem systemForUsl = new SimulatedSystem(truth, CLIENT_THREADS, 0.02, 21L);
        SimulatedSystem systemForReactive = new SimulatedSystem(truth, CLIENT_THREADS, 0.02, 21L);

        UslAutoScalingController usl = new UslAutoScalingController(MIN, MAX);
        ReactiveTimeoutPolicy reactive =
                new ReactiveTimeoutPolicy(MIN, MAX, systemForReactive.reactiveTimeoutNanos());

        List<PolicyRunner.Step> uslRun = PolicyRunner.run(usl, systemForUsl, 8, 80, -1, null);
        List<PolicyRunner.Step> reactiveRun = PolicyRunner.run(reactive, systemForReactive, 8, 80, -1, null);

        double uslTps = PolicyRunner.steadyStateThroughput(uslRun, 20);
        double reactiveTps = PolicyRunner.steadyStateThroughput(reactiveRun, 20);
        double reactiveSize = PolicyRunner.steadyStatePoolSize(reactiveRun, 20);

        assertTrue(reactiveSize > nStar + 8,
                "reactive should overshoot the optimum, size=" + reactiveSize + " vs N*=" + nStar);
        assertTrue(uslTps > reactiveTps,
                "USL steady-state throughput " + uslTps + " should exceed reactive " + reactiveTps);
    }

    private static double averagePoolSize(List<PolicyRunner.Step> trajectory, int fromWindow, int toWindow) {
        double sum = 0;
        int n = 0;
        for (PolicyRunner.Step step : trajectory) {
            if (step.window() >= fromWindow && step.window() < toWindow) {
                sum += step.poolSize();
                n++;
            }
        }
        return n == 0 ? Double.NaN : sum / n;
    }
}
