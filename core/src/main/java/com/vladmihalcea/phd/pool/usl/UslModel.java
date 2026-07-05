package com.vladmihalcea.phd.pool.usl;

/**
 * Neil Gunther's Universal Scalability Law in its concurrency form, applied here to connection pool
 * sizing. Throughput as a function of the number of connections {@code N} is
 *
 * <pre>
 *                        lambda * N
 *     X(N) = ------------------------------------
 *             1 + sigma*(N - 1) + kappa*N*(N - 1)
 * </pre>
 *
 * where {@code lambda = X(1)} is the single-connection throughput, {@code sigma} is the contention
 * (serialization) coefficient and {@code kappa} is the coherency (cross-talk) coefficient. When
 * {@code kappa > 0} the curve is concave with an interior maximum — the retrograde region past which
 * adding connections reduces throughput — located at
 *
 * <pre>
 *     N* = floor( sqrt( (1 - sigma) / kappa ) ).
 * </pre>
 *
 * @author Vlad Mihalcea
 */
public record UslModel(double lambda, double sigma, double kappa) {

    public UslModel {
        if (lambda <= 0) {
            throw new IllegalArgumentException("lambda must be positive: " + lambda);
        }
        if (sigma < 0) {
            throw new IllegalArgumentException("sigma must be non-negative: " + sigma);
        }
        if (kappa < 0) {
            throw new IllegalArgumentException("kappa must be non-negative: " + kappa);
        }
    }

    /**
     * Predicted throughput at pool size {@code n}.
     */
    public double throughputAt(double n) {
        double denominator = 1.0 + sigma * (n - 1.0) + kappa * n * (n - 1.0);
        return lambda * n / denominator;
    }

    /**
     * The real-valued optimal pool size {@code sqrt((1 - sigma)/kappa)}. Returns
     * {@link Double#POSITIVE_INFINITY} when {@code kappa == 0} (no coherency penalty, throughput keeps
     * rising with concurrency — a purely Amdahl regime).
     */
    public double optimalPoolSizeReal() {
        if (kappa == 0.0) {
            return Double.POSITIVE_INFINITY;
        }
        return Math.sqrt((1.0 - sigma) / kappa);
    }

    /**
     * The integer optimal pool size {@code N*}. Returns {@link Integer#MAX_VALUE} when there is no
     * retrograde region (kappa == 0). Never returns less than 1.
     */
    public int optimalPoolSize() {
        double real = optimalPoolSizeReal();
        if (Double.isInfinite(real) || real >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return Math.max(1, (int) Math.floor(real));
    }

    /**
     * Peak achievable throughput, i.e. {@code X(N*)} evaluated at the real-valued optimum.
     */
    public double peakThroughput() {
        double real = optimalPoolSizeReal();
        return Double.isInfinite(real) ? Double.POSITIVE_INFINITY : throughputAt(real);
    }
}
