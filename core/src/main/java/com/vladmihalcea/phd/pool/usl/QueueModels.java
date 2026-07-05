package com.vladmihalcea.phd.pool.usl;

/**
 * Classical sizing formulae the paper compares against the USL optimum (experiment E3): Little's Law,
 * the Erlang-C (M/M/c) waiting model, and the widely-quoted HikariCP heuristic. Each predicts a pool
 * size from offered load and service time; the paper's argument is that they diverge from the observed
 * throughput optimum precisely in the regime where the coherency term {@code kappa} dominates, because
 * none of them models cross-connection coordination.
 *
 * @author Vlad Mihalcea
 */
public final class QueueModels {

    private QueueModels() {
    }

    /**
     * Little's Law sizing: the number of connections needed to sustain an arrival rate with a given mean
     * service time equals the average number of in-flight requests {@code L = lambda * W}. Here
     * {@code arrivalRatePerSec} is the offered load and {@code serviceTimeSeconds} is the mean
     * per-transaction service time (including network round-trip). Rounded up, floored at 1.
     *
     * @param arrivalRatePerSec offered transaction arrival rate (requests/second)
     * @param serviceTimeSeconds mean service time per transaction (seconds)
     * @return the smallest integer pool size that keeps utilisation at or below 1
     */
    public static int littlesLaw(double arrivalRatePerSec, double serviceTimeSeconds) {
        double concurrency = arrivalRatePerSec * serviceTimeSeconds;
        return Math.max(1, (int) Math.ceil(concurrency));
    }

    /**
     * Smallest number of servers {@code c} in an M/M/c queue for which the Erlang-C probability that an
     * arriving request must wait falls below {@code targetWaitProbability}. Requires the offered load
     * {@code a = lambda * S} (in Erlangs) to be finite; {@code c} must exceed {@code a} for stability.
     *
     * @param arrivalRatePerSec  offered arrival rate (requests/second)
     * @param serviceTimeSeconds mean service time (seconds)
     * @param targetWaitProbability acceptable probability of queueing (e.g. 0.05)
     * @return the smallest stable server count meeting the target
     */
    public static int erlangCServers(double arrivalRatePerSec, double serviceTimeSeconds,
                                     double targetWaitProbability) {
        double offeredLoad = arrivalRatePerSec * serviceTimeSeconds; // Erlangs
        int c = Math.max(1, (int) Math.floor(offeredLoad) + 1);
        // Increase c until the queueing probability drops below the target (and the system is stable).
        while (c < 100_000) {
            if (c > offeredLoad && erlangCWaitProbability(offeredLoad, c) <= targetWaitProbability) {
                return c;
            }
            c++;
        }
        return c;
    }

    /**
     * The Erlang-C formula: probability that an arriving customer has to wait in an M/M/c queue with
     * offered load {@code a} Erlangs and {@code c} servers. Requires {@code c > a} for a stable queue.
     */
    public static double erlangCWaitProbability(double offeredLoad, int c) {
        if (c <= offeredLoad) {
            return 1.0;
        }
        // Numerically stable via the recursive Erlang-B, then the B->C transformation.
        double erlangB = erlangB(offeredLoad, c);
        double rho = offeredLoad / c;
        return erlangB / (1.0 - rho * (1.0 - erlangB));
    }

    /**
     * Erlang-B blocking probability via the standard numerically-stable recursion
     * {@code B(0,a)=1; B(k,a)= a*B(k-1,a) / (k + a*B(k-1,a))}.
     */
    public static double erlangB(double offeredLoad, int servers) {
        double b = 1.0;
        for (int k = 1; k <= servers; k++) {
            b = (offeredLoad * b) / (k + offeredLoad * b);
        }
        return b;
    }

    /**
     * The HikariCP folklore heuristic {@code connections = ((core_count * 2) + effective_spindle_count)}.
     * Included so the paper can test whether this static rule of thumb lands near the measured optimum.
     *
     * @param coreCount            number of CPU cores on the database host
     * @param effectiveSpindleCount number of independent storage channels (1 for a single SSD/volume)
     */
    public static int hikariHeuristic(int coreCount, int effectiveSpindleCount) {
        return Math.max(1, coreCount * 2 + effectiveSpindleCount);
    }
}
