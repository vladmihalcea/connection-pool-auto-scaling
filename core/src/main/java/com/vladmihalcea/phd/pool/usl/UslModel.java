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

    /**
     * The real-valued start of the plateau: the smallest {@code N} whose throughput reaches
     * {@code fraction * X(N*)}. Because {@code X(N) >= f * X*} rearranges to
     *
     * <pre>
     *     f*X*·kappa·N^2 + (f*X*·(sigma - kappa) - lambda)·N + f*X*·(1 - sigma) <= 0,
     * </pre>
     *
     * the answer is the smaller root of that quadratic. When {@code kappa == 0} the curve has no peak but
     * approaches the Amdahl asymptote {@code lambda/sigma}, so {@code fraction} is taken of that ceiling
     * instead; with {@code sigma == kappa == 0} throughput grows linearly and there is no plateau, so the
     * method returns {@link Double#POSITIVE_INFINITY}.
     *
     * @param fraction the share of the peak throughput to reach, in {@code (0, 1)} (for example 0.95)
     */
    public double poolSizeAtFractionReal(double fraction) {
        return poolSizeAtFractionReal(fraction, Double.POSITIVE_INFINITY);
    }

    /**
     * Like {@link #poolSizeAtFractionReal(double)}, but for a pool that may not grow past
     * {@code maxPoolSize}: the reference is the best throughput reachable within the cap,
     * {@code X(min(N*, maxPoolSize))}. When the peak lies beyond the cap, a fit made from the rising side
     * of the curve cannot locate it, and taking the fraction of an extrapolated peak (or of the Amdahl
     * asymptote) would point past the cap; the capped reference keeps the answer inside the range the pool
     * can actually use.
     *
     * @param fraction    the share of the reference throughput to reach, in {@code (0, 1)}
     * @param maxPoolSize the largest pool size allowed, or {@link Double#POSITIVE_INFINITY} for no cap
     */
    public double poolSizeAtFractionReal(double fraction, double maxPoolSize) {
        if (!(fraction > 0.0 && fraction < 1.0)) {
            throw new IllegalArgumentException("fraction must be in (0, 1): " + fraction);
        }
        double peakSize = Math.min(optimalPoolSizeReal(), maxPoolSize);
        double reference;
        if (Double.isInfinite(peakSize)) {
            if (sigma == 0.0) {
                return Double.POSITIVE_INFINITY; // linear growth: no plateau
            }
            reference = lambda / sigma; // Amdahl asymptote
        } else {
            reference = throughputAt(peakSize);
        }
        double target = fraction * reference;
        double a = target * kappa;
        double b = target * (sigma - kappa) - lambda;
        double c = target * (1.0 - sigma);
        double root;
        if (a == 0.0) {
            // kappa == 0: the inequality is linear, b*N + c <= 0 with b < 0.
            root = c / -b;
        } else {
            double discriminant = Math.max(0.0, b * b - 4.0 * a * c);
            root = (-b - Math.sqrt(discriminant)) / (2.0 * a);
        }
        return Math.max(1.0, Math.min(root, peakSize));
    }

    /**
     * The integer start of the plateau {@code N_eps}: the smallest pool size whose throughput is at least
     * {@code fraction} of the peak, i.e. {@code ceil} of {@link #poolSizeAtFractionReal(double)}. Returns
     * {@link Integer#MAX_VALUE} when there is no plateau ({@code sigma == kappa == 0}). Never returns less
     * than 1.
     */
    public int poolSizeAtFraction(double fraction) {
        return ceilSize(poolSizeAtFractionReal(fraction));
    }

    /**
     * The integer start of the plateau for a pool capped at {@code maxPoolSize}, see
     * {@link #poolSizeAtFractionReal(double, double)}. Never returns less than 1 or more than the cap.
     */
    public int poolSizeAtFraction(double fraction, int maxPoolSize) {
        return Math.min(maxPoolSize, ceilSize(poolSizeAtFractionReal(fraction, maxPoolSize)));
    }

    /**
     * The throughput elasticity at {@code n}: the relative throughput gained per relative connection added,
     *
     * <pre>
     *     eta(N) = d ln X / d ln N = 1 - N * (sigma + kappa*(2N - 1)) / (1 + sigma*(N - 1) + kappa*N*(N - 1)).
     * </pre>
     *
     * It falls from about 1 at the first connections (linear scaling) to 0 at {@code N*} and turns negative in the
     * retrograde region, so it measures how far the gains have diminished. By Little's Law ({@code X = N / R}) it
     * is also {@code 1 - d ln R / d ln N}: one minus the elasticity of the residence (lease) time.
     */
    public double elasticityAt(double n) {
        double denominator = 1.0 + sigma * (n - 1.0) + kappa * n * (n - 1.0);
        return 1.0 - n * (sigma + kappa * (2.0 * n - 1.0)) / denominator;
    }

    /**
     * The real-valued pool size at which the throughput elasticity falls to {@code eta}, the point of diminishing
     * returns: past it, a relative increase of the pool buys less than {@code eta} times that increase in
     * throughput (with {@code eta = 0.1}, 10% more connections buy at most 1% more throughput). Setting
     * {@link #elasticityAt} to {@code eta} rearranges to the quadratic
     *
     * <pre>
     *     kappa*(1 + eta)*N^2 + eta*(sigma - kappa)*N - (1 - eta)*(1 - sigma) = 0,
     * </pre>
     *
     * whose positive root this returns; {@code eta = 0} gives {@code N*}, so every {@code eta > 0} lies below the
     * optimum. Unlike a fixed fraction of the peak, the share of the peak reached at this size follows from the
     * shape of the curve: contention-dominated curves stop earlier (and save more connections), coherency-dominated
     * ones closer to {@code N*}. With {@code kappa == 0} the elasticity only approaches 0 (the Amdahl ceiling), and
     * the root is {@code (1 - eta)(1 - sigma) / (eta * sigma)}; with {@code sigma == kappa == 0} it is infinite.
     *
     * @param eta the elasticity threshold, in {@code (0, 1)}
     */
    public double poolSizeAtElasticityReal(double eta) {
        if (!(eta > 0.0 && eta < 1.0)) {
            throw new IllegalArgumentException("eta must be in (0, 1): " + eta);
        }
        double a = kappa * (1.0 + eta);
        double b = eta * (sigma - kappa);
        double c = -(1.0 - eta) * (1.0 - sigma);
        if (a == 0.0) {
            return b == 0.0 ? Double.POSITIVE_INFINITY : Math.max(1.0, -c / b);
        }
        return Math.max(1.0, (-b + Math.sqrt(b * b - 4.0 * a * c)) / (2.0 * a));
    }

    /**
     * The integer point of diminishing returns, {@code ceil} of {@link #poolSizeAtElasticityReal(double)}: the
     * smallest pool size whose elasticity is at most {@code eta}, capped at {@code maxPoolSize}. Never returns less
     * than 1.
     */
    public int poolSizeAtElasticity(double eta, int maxPoolSize) {
        return Math.min(maxPoolSize, ceilSize(poolSizeAtElasticityReal(eta)));
    }

    /**
     * The mean residence time at concurrency {@code n}, from Little's Law applied to the USL (Schwartz 2015,
     * Eq. 6; Gunther's {@code R(N) = N / X(N)} with no think time):
     *
     * <pre>
     *     R(N) = (1 + sigma*(N - 1) + kappa*N*(N - 1)) / lambda.
     * </pre>
     *
     * For a saturated connection pool, {@code N} busy connections and the throughput {@code X(N)} give the
     * mean connection lease time, so this is the lease time in seconds when {@code lambda} is in
     * transactions per second: quadratic in the pool size, with the contention term growing linearly and the
     * coherency term quadratically.
     */
    public double responseTimeAt(double n) {
        return (1.0 + sigma * (n - 1.0) + kappa * n * (n - 1.0)) / lambda;
    }

    /**
     * The largest concurrency whose mean residence time stays within {@code responseTime} (Schwartz 2015,
     * Eq. 10), the positive root of {@code R(N) = responseTime}:
     *
     * <pre>
     *     N(R) = (kappa - sigma + sqrt(sigma^2 + kappa^2 + 2*kappa*(2*lambda*R + sigma - 2))) / (2*kappa).
     * </pre>
     *
     * With {@code kappa == 0} the residence time is linear in {@code N}, and the root is
     * {@code 1 + (lambda*R - 1) / sigma}. Returns {@link Double#POSITIVE_INFINITY} when the residence time
     * never grows ({@code sigma == kappa == 0}) and 0 when even one request exceeds the target
     * ({@code responseTime < 1 / lambda}).
     *
     * @param responseTime the residence-time target, in the unit of {@code 1 / lambda} (seconds when lambda is
     *                     per second)
     */
    public double concurrencyForResponseTime(double responseTime) {
        if (responseTime * lambda < 1.0) {
            return 0.0;
        }
        if (kappa == 0.0) {
            return sigma == 0.0 ? Double.POSITIVE_INFINITY : 1.0 + (lambda * responseTime - 1.0) / sigma;
        }
        double discriminant = sigma * sigma + kappa * kappa + 2.0 * kappa * (2.0 * lambda * responseTime + sigma - 2.0);
        return (kappa - sigma + Math.sqrt(Math.max(0.0, discriminant))) / (2.0 * kappa);
    }

    /**
     * The throughput at which the mean residence time reaches {@code responseTime} (Schwartz 2015, Eq. 9):
     * {@code X(R) = N(R) / R} by Little's Law, with {@code N(R)} from
     * {@link #concurrencyForResponseTime(double)}. Unlike the residence time as a function of the throughput,
     * which folds back on itself past the peak (the "nose"), this is a function.
     */
    public double throughputForResponseTime(double responseTime) {
        double n = concurrencyForResponseTime(responseTime);
        return Double.isInfinite(n) ? Double.POSITIVE_INFINITY : n / responseTime;
    }

    private static int ceilSize(double real) {
        if (Double.isInfinite(real) || real >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        // The tolerance absorbs floating-point noise when the root lands on an integer.
        return Math.max(1, (int) Math.ceil(real - 1e-9));
    }
}
