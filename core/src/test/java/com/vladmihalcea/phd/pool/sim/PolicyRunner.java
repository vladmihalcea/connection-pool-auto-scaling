package com.vladmihalcea.phd.pool.sim;

import com.vladmihalcea.phd.pool.controller.ScalingPolicy;
import com.vladmihalcea.phd.pool.controller.WindowSample;

import java.util.ArrayList;
import java.util.List;

/**
 * Drives a {@link ScalingPolicy} against a {@link SimulatedSystem} for a fixed number of windows,
 * recording the pool-size and throughput trajectory. Shared by the controller unit test and the
 * convergence/regime-shift experiments so both exercise the identical control loop.
 *
 * @author Vlad Mihalcea
 */
public final class PolicyRunner {

    public record Step(int window, int poolSize, double throughput, double optimalThroughput) {
    }

    private PolicyRunner() {
    }

    /**
     * @param policy       the sizing policy under test
     * @param system       the simulated system (its ground truth may be mutated mid-run for a regime shift)
     * @param initialSize  the starting pool size
     * @param windows      number of observation windows to run
     * @param shiftAt      window index at which to apply {@code shiftedTruth} (or negative for none)
     * @param shiftedTruth the ground-truth model to switch to at {@code shiftAt}
     * @return the per-window trajectory
     */
    public static List<Step> run(ScalingPolicy policy, SimulatedSystem system, int initialSize,
                                 int windows, int shiftAt,
                                 com.vladmihalcea.phd.pool.usl.UslModel shiftedTruth) {
        policy.reset();
        List<Step> trajectory = new ArrayList<>(windows);
        int poolSize = initialSize;
        for (int w = 0; w < windows; w++) {
            if (w == shiftAt && shiftedTruth != null) {
                system.setTruth(shiftedTruth);
            }
            WindowSample sample = system.sample(poolSize);
            double optimal = system.truth().peakThroughput();
            trajectory.add(new Step(w, poolSize, sample.throughput(), optimal));
            poolSize = policy.decide(sample);
        }
        return trajectory;
    }

    /** Mean throughput over the final {@code tailWindows} windows (steady-state performance). */
    public static double steadyStateThroughput(List<Step> trajectory, int tailWindows) {
        int from = Math.max(0, trajectory.size() - tailWindows);
        double sum = 0;
        int n = 0;
        for (int i = from; i < trajectory.size(); i++) {
            sum += trajectory.get(i).throughput();
            n++;
        }
        return n == 0 ? Double.NaN : sum / n;
    }

    /** Mean pool size over the final {@code tailWindows} windows (steady-state operating point). */
    public static double steadyStatePoolSize(List<Step> trajectory, int tailWindows) {
        int from = Math.max(0, trajectory.size() - tailWindows);
        double sum = 0;
        int n = 0;
        for (int i = from; i < trajectory.size(); i++) {
            sum += trajectory.get(i).poolSize();
            n++;
        }
        return n == 0 ? Double.NaN : sum / n;
    }
}
