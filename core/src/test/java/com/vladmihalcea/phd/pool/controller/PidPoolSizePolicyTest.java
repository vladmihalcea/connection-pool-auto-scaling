package com.vladmihalcea.phd.pool.controller;

import com.vladmihalcea.phd.pool.sim.PolicyRunner;
import com.vladmihalcea.phd.pool.sim.SimulatedSystem;
import com.vladmihalcea.phd.pool.usl.UslModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The PID baseline holds the wait-to-lease ratio at its set-point, so in a closed loop it follows the client
 * count, past the USL peak when the clients outnumber {@code N*}, without winding up on the way.
 *
 * @author Vlad Mihalcea
 */
public class PidPoolSizePolicyTest {

    @Test
    public void followsTheClientCountPastThePeak() {
        UslModel truth = new UslModel(1500, 0.03, 0.0005); // N* = 44
        List<PolicyRunner.Step> trajectory = PolicyRunner.run(
                new PidPoolSizePolicy(PidPoolSizePolicy.Config.defaults(2, 96)),
                new SimulatedSystem(truth, 80, 0.02, 1L), 2, 100, -1, null);
        double settled = PolicyRunner.steadyStatePoolSize(trajectory, 20);
        // Q / L = (80 - N) / N = 0.1 gives N = 72.7.
        assertTrue(Math.abs(settled - 80 / 1.1) <= 6, "settled at " + settled);
        assertTrue(settled > truth.optimalPoolSize());
    }

    @Test
    public void doesNotWindUpWhileClimbingFromASmallPool() {
        // From 2 connections to 8 clients: the climb must not overshoot far past 8 / 1.1, and must come back.
        UslModel truth = new UslModel(1500, 0.03, 0.0005);
        List<PolicyRunner.Step> trajectory = PolicyRunner.run(
                new PidPoolSizePolicy(PidPoolSizePolicy.Config.defaults(2, 96)),
                new SimulatedSystem(truth, 8, 0.02, 2L), 2, 60, -1, null);
        int peak = trajectory.stream().mapToInt(PolicyRunner.Step::poolSize).max().orElseThrow();
        double settled = PolicyRunner.steadyStatePoolSize(trajectory, 20);
        assertTrue(peak <= 16, "overshoot to " + peak);
        assertTrue(settled >= 6 && settled <= 10, "settled at " + settled);
    }
}
