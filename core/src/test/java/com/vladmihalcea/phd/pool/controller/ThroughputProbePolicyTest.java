package com.vladmihalcea.phd.pool.controller;

import com.vladmihalcea.phd.pool.sim.PolicyRunner;
import com.vladmihalcea.phd.pool.sim.SimulatedSystem;
import com.vladmihalcea.phd.pool.usl.UslModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioural tests for the model-free throughput-probe policy against the deterministic simulator. It
 * must climb to the known optimum {@code N*} purely from measured throughput (no USL fit), stay well below
 * the client-thread count where FlexyPool's grow-only reactive strategy overshoots, deliver higher
 * steady-state throughput than that reactive baseline, and re-track (including shrink) after a regime
 * shift. This is the decision logic the FlexyPool
 * {@code ThroughputOptimizingConnectionAcquisitionStrategy} drives. No Docker required.
 *
 * @author Vlad Mihalcea
 */
public class ThroughputProbePolicyTest {

    private static final int MIN = 2;
    private static final int MAX = 96;
    private static final int CLIENT_THREADS = 96;

    @Test
    public void climbsToTheOptimum() {
        // N* = sqrt((1 - 0.01)/0.0005) = 44
        UslModel truth = new UslModel(1500, 0.01, 0.0005);
        int nStar = truth.optimalPoolSize();
        assertTrue(nStar >= 40 && nStar <= 48, "sanity on N*: " + nStar);

        SimulatedSystem system = new SimulatedSystem(truth, CLIENT_THREADS, 0.02, 7L);
        ThroughputProbePolicy policy = new ThroughputProbePolicy(MIN, MAX);

        List<PolicyRunner.Step> trajectory = PolicyRunner.run(policy, system, 2, 80, -1, null);

        double settledSize = PolicyRunner.steadyStatePoolSize(trajectory, 20);
        assertTrue(Math.abs(settledSize - nStar) <= 10,
                "probe policy should settle near N*=" + nStar + " but settled at " + settledSize);
    }

    @Test
    public void beatsReactivePolicyOnThroughput() {
        UslModel truth = new UslModel(1500, 0.01, 0.0005);
        int nStar = truth.optimalPoolSize();

        SimulatedSystem systemForProbe = new SimulatedSystem(truth, CLIENT_THREADS, 0.02, 21L);
        SimulatedSystem systemForReactive = new SimulatedSystem(truth, CLIENT_THREADS, 0.02, 21L);

        ThroughputProbePolicy probe = new ThroughputProbePolicy(MIN, MAX);
        ReactiveTimeoutPolicy reactive =
                new ReactiveTimeoutPolicy(MIN, MAX, systemForReactive.reactiveTimeoutNanos());

        List<PolicyRunner.Step> probeRun = PolicyRunner.run(probe, systemForProbe, 2, 80, -1, null);
        List<PolicyRunner.Step> reactiveRun = PolicyRunner.run(reactive, systemForReactive, 2, 80, -1, null);

        double probeTps = PolicyRunner.steadyStateThroughput(probeRun, 20);
        double reactiveTps = PolicyRunner.steadyStateThroughput(reactiveRun, 20);
        double probeSize = PolicyRunner.steadyStatePoolSize(probeRun, 20);
        double reactiveSize = PolicyRunner.steadyStatePoolSize(reactiveRun, 20);

        assertTrue(probeSize < reactiveSize - 8,
                "probe should settle below the reactive overshoot: " + probeSize + " vs " + reactiveSize);
        assertTrue(reactiveSize > nStar + 8,
                "reactive should overshoot the optimum, size=" + reactiveSize + " vs N*=" + nStar);
        assertTrue(probeTps > reactiveTps,
                "probe steady-state throughput " + probeTps + " should exceed reactive " + reactiveTps);
    }

    @Test
    public void reTracksAndShrinksAfterRegimeShift() {
        // Phase 1: N* = 44. Phase 2: N* = sqrt(0.98/0.002) ~= 22 (more coherency, smaller optimum).
        UslModel phase1 = new UslModel(1500, 0.01, 0.0005);
        UslModel phase2 = new UslModel(1500, 0.02, 0.002);
        int nStar2 = phase2.optimalPoolSize();

        SimulatedSystem system = new SimulatedSystem(phase1, CLIENT_THREADS, 0.02, 11L);
        ThroughputProbePolicy policy = new ThroughputProbePolicy(MIN, MAX);

        List<PolicyRunner.Step> trajectory = PolicyRunner.run(policy, system, 2, 160, 80, phase2);

        double phase1Settled = averagePoolSize(trajectory, 65, 80);
        double phase2Settled = averagePoolSize(trajectory, 145, 160);

        assertTrue(phase2Settled < phase1Settled - 8,
                "policy must SHRINK after the optimum drops: " + phase1Settled + " -> " + phase2Settled);
        assertTrue(Math.abs(phase2Settled - nStar2) <= 12,
                "policy should re-track N*=" + nStar2 + " but settled at " + phase2Settled);
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
