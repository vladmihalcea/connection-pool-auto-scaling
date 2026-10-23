package com.vladmihalcea.phd.pool.controller;

/**
 * The control-theoretic baseline of the self-adaptive-systems literature: a PID controller that holds the
 * connection wait at a set fraction of the lease time. By Little's Law, the mean wait queue {@code Q = X * A}
 * and the mean busy connections {@code L = X * W}, so {@code Q / L = A / W}, the ratio of the acquisition wait
 * to the lease time, which needs no timer and is dimensionless, so one set-point {@code r} serves every workload.
 *
 * <p>The pool size is a multiplicative actuator, so the error is a log ratio and the output a log step:
 *
 * <pre>
 *     e = ln((1 + Q / L) / (1 + r)),
 *     N_next = N * exp(clamp(Kp*e + Ki*sum(e) + Kd*(e - e_prev), -maxLogStep, maxLogStep)).
 * </pre>
 *
 * In a closed loop with {@code M} clients and a saturated pool, {@code 1 + Q / L = M / N}, so
 * {@code e = ln(M / (N (1 + r)))} is the relative distance to the controller's own target {@code M / (1 + r)}, and a
 * proportional gain of 1 would reach it in one window; under light load ({@code Q = 0}) the error is
 * {@code -ln(1 + r)}, and the pool shrinks until a queue appears. The integral is clamped and frozen while the
 * output saturates (conditional integration), so a long climb from a small pool does not wind it up and overshoot
 * once the queue is gone.
 *
 * <p>The controller knows nothing about the USL: it settles near {@code M / (1 + r)} and follows the client
 * concurrency past the peak of the curve, which is the failure mode the USL controllers avoid. Windows without
 * occupancy data use the pool size in place of {@code L}.
 *
 * @author Vlad Mihalcea
 */
public final class PidPoolSizePolicy implements ScalingPolicy {

    /** Tunable knobs; the defaults are what the experiments use. */
    public record Config(int minSize, int maxSize, double setPoint, double kp, double ki, double kd,
                         double integralLimit, double maxLogStep) {

        public static Config defaults(int minSize, int maxSize) {
            return new Config(minSize, maxSize, 0.10, 0.6, 0.1, 0.05, 1.0, 0.5);
        }
    }

    private final Config config;
    private double integral;
    private double previousError = Double.NaN;

    public PidPoolSizePolicy(Config config) {
        this.config = config;
    }

    @Override
    public int decide(WindowSample sample) {
        int current = sample.poolSize();
        double load = sample.hasOccupancy() ? Math.max(sample.busyConnections(), 1e-3) : current;
        double error = Math.log((1.0 + sample.queueLength() / load) / (1.0 + config.setPoint()));
        double derivative = Double.isNaN(previousError) ? 0.0 : error - previousError;
        previousError = error;
        double candidateIntegral = Math.max(-config.integralLimit(),
                Math.min(config.integralLimit(), integral + error));
        double u = config.kp() * error + config.ki() * candidateIntegral + config.kd() * derivative;
        if (Math.abs(u) <= config.maxLogStep() || Math.signum(error) != Math.signum(u)) {
            integral = candidateIntegral; // conditional integration: freeze the integral while saturated
        }
        u = Math.max(-config.maxLogStep(), Math.min(config.maxLogStep(), u));
        int next = (int) Math.round(current * Math.exp(u));
        if (next == current && Math.abs(Math.expm1(u)) * current >= 0.5) {
            next += (int) Math.signum(u);
        }
        return Math.max(config.minSize(), Math.min(config.maxSize(), next));
    }

    @Override
    public void reset() {
        integral = 0;
        previousError = Double.NaN;
    }

    @Override
    public String id() {
        return "pid";
    }
}
