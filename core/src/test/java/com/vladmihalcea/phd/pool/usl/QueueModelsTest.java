package com.vladmihalcea.phd.pool.usl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the classical sizing baselines compared against the USL optimum in experiment E3.
 * No Docker required.
 *
 * @author Vlad Mihalcea
 */
public class QueueModelsTest {

    @Test
    public void littlesLawIsArrivalRateTimesServiceTime() {
        // 1000 tx/s at 5 ms service time => L = 5 concurrent in flight.
        assertEquals(5, QueueModels.littlesLaw(1000, 0.005));
        // Rounds up a fractional concurrency and never returns 0.
        assertEquals(4, QueueModels.littlesLaw(700, 0.005));
        assertEquals(1, QueueModels.littlesLaw(1, 0.0001));
    }

    @Test
    public void erlangBDecreasesWithMoreServers() {
        double previous = 1.0;
        for (int c = 1; c <= 20; c++) {
            double b = QueueModels.erlangB(5.0, c);
            assertTrue(b <= previous, "Erlang-B must be non-increasing in servers");
            assertTrue(b >= 0 && b <= 1, "Erlang-B is a probability");
            previous = b;
        }
    }

    @Test
    public void erlangCServersExceedsOfferedLoadAndMeetsTarget() {
        double arrival = 1000;      // tx/s
        double service = 0.02;      // 20 ms => offered load = 20 Erlangs
        int servers = QueueModels.erlangCServers(arrival, service, 0.05);
        assertTrue(servers > 20, "must exceed the offered load for stability, got " + servers);
        assertTrue(QueueModels.erlangCWaitProbability(20.0, servers) <= 0.05,
                "chosen server count must meet the wait-probability target");
        assertTrue(QueueModels.erlangCWaitProbability(20.0, servers - 1) > 0.05,
                "one fewer server must miss the target (minimality)");
    }

    @Test
    public void hikariHeuristicFormula() {
        // ((cores * 2) + spindles): 8 cores, 1 SSD => 17.
        assertEquals(17, QueueModels.hikariHeuristic(8, 1));
    }
}
