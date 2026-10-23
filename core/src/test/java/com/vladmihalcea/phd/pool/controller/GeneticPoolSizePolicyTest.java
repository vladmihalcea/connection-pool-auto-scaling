package com.vladmihalcea.phd.pool.controller;

import com.vladmihalcea.phd.pool.sim.PolicyRunner;
import com.vladmihalcea.phd.pool.sim.SimulatedSystem;
import com.vladmihalcea.phd.pool.usl.UslModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The genetic search over the pool size: it ends at a size within the tolerance of the best measured
 * throughput, prefers the smaller of equally good sizes, is reproducible for a seed, and restarts when the
 * throughput at the held size moves.
 *
 * @author Vlad Mihalcea
 */
public class GeneticPoolSizePolicyTest {

    private static final int MIN = 2;
    private static final int MAX = 96;
    private static final UslModel TRUTH = new UslModel(1500, 0.03, 0.0005); // N* = 44

    @Test
    public void endsNearThePlateauWithNearPeakThroughput() {
        GeneticPoolSizePolicy policy = new GeneticPoolSizePolicy(GeneticPoolSizePolicy.Config.defaults(MIN, MAX, 0.01, 1L));
        List<PolicyRunner.Step> trajectory = PolicyRunner.run(policy, new SimulatedSystem(TRUTH, 128, 0.01, 3L),
                MIN, 120, -1, null);
        int held = policy.heldSize();
        assertTrue(held > 0, "the search should end within 120 windows");
        assertTrue(TRUTH.throughputAt(held) >= 0.95 * TRUTH.peakThroughput(), "held " + held);
        assertTrue(held < 70, "the tie-break should prefer the smaller near-peak sizes, held " + held);
        assertTrue(policy.evaluations() <= 8 * 5, "evaluations " + policy.evaluations());
        assertEquals(held, trajectory.getLast().poolSize());
    }

    @Test
    public void elasticityTargetHoldsBelowTheBestSize() {
        GeneticPoolSizePolicy policy = new GeneticPoolSizePolicy(GeneticPoolSizePolicy.Config.defaults(MIN, MAX));
        PolicyRunner.run(policy, new SimulatedSystem(TRUTH, 128, 0.01, 3L), MIN, 120, -1, null);
        int held = policy.heldSize();
        assertTrue(held > 0 && held < TRUTH.optimalPoolSize(), "held " + held);
        assertTrue(TRUTH.throughputAt(held) >= 0.93 * TRUTH.peakThroughput(), "held " + held);
    }

    @Test
    public void isReproducibleForASeed() {
        int[] held = new int[2];
        for (int i = 0; i < 2; i++) {
            GeneticPoolSizePolicy policy = new GeneticPoolSizePolicy(GeneticPoolSizePolicy.Config.defaults(MIN, MAX, 0.01, 9L));
            PolicyRunner.run(policy, new SimulatedSystem(TRUTH, 128, 0.02, 4L), MIN, 100, -1, null);
            held[i] = policy.heldSize();
        }
        assertEquals(held[0], held[1]);
    }

    @Test
    public void restartsWhenTheLoadChanges() {
        SimulatedSystem system = new SimulatedSystem(TRUTH, 128, 0.01, 5L);
        GeneticPoolSizePolicy policy = new GeneticPoolSizePolicy(GeneticPoolSizePolicy.Config.defaults(MIN, MAX, 0.01, 2L));
        int size = MIN;
        for (int w = 0; w < 120; w++) {
            size = policy.decide(system.sample(size));
        }
        int before = policy.heldSize();
        system.setClientThreads(8);
        for (int w = 0; w < 160; w++) {
            size = policy.decide(system.sample(size));
        }
        assertTrue(policy.searches() >= 2, "the drop to 8 clients should restart the search");
        int after = policy.heldSize();
        assertTrue(after >= 8 && after < before,
                "with 8 clients the search should hold a smaller pool of at least 8: " + before + " -> " + after);
    }
}
