package com.vladmihalcea.phd.pool.usl;

/**
 * Fits the USL from <em>lease times</em> instead of throughputs: the response-time form of the law.
 * Little's Law turns the USL into a residence time that is quadratic in the concurrency (Schwartz,
 * <i>Practical Scalability Analysis with the Universal Scalability Law</i>, 2015, Eq. 6):
 *
 * <pre>
 *     W(c) = W_0 * [1 + sigma*(c - 1) + kappa*c*(c - 1)] = a + b*(c - 1) + d*c*(c - 1),
 * </pre>
 *
 * with {@code W_0 = a = 1 / lambda}, {@code sigma = b / a} and {@code kappa = d / a}. For a connection pool,
 * {@code c} is the mean number of busy connections in a window and {@code W} the mean lease time,
 * {@code W = c / X} by Little's Law. The coefficients follow from weighted ordinary least squares on the basis
 * {@code (1, c - 1, c(c - 1))}, in closed form: no start values, no iterations, and no {@code X(1)}, which an
 * online controller never measures. Gunther's own regression on the deviation from linearity needs
 * {@code X(1)}; this one recovers it as {@code 1 / a}.
 *
 * <p>The coefficients must be non-negative. When the unconstrained solution has a negative {@code b} or
 * {@code d}, the fitter solves every subset of the basis that keeps {@code a} and returns the feasible one with
 * the smallest weighted squared error (an active-set search over four candidates).
 *
 * <p>The two fits weight errors differently: an error {@code dX} in the throughput becomes an error of about
 * {@code c * dX / X^2} in the lease time, so the lease-time fit trusts the small, fast windows more than
 * {@link UslFitter} does. The returned {@link UslFit#rSquared()} is computed in throughput space
 * ({@code X = c / W}), so the two fitters' values compare directly. Standard errors come from the weighted
 * least-squares covariance by the delta method.
 *
 * @author Vlad Mihalcea
 */
public final class LeaseTimeFitter {

    private LeaseTimeFitter() {
    }

    /**
     * Unweighted fit.
     *
     * @param concurrency  the mean number of busy connections of each point
     * @param leaseSeconds the mean lease time of each point, in seconds
     */
    public static UslFit fit(double[] concurrency, double[] leaseSeconds) {
        double[] weights = new double[concurrency.length];
        java.util.Arrays.fill(weights, 1.0);
        return fit(concurrency, leaseSeconds, weights);
    }

    /**
     * Weighted fit.
     *
     * @param concurrency  the mean number of busy connections of each point (at least 1)
     * @param leaseSeconds the mean lease time of each point, in seconds (positive)
     * @param weights      the non-negative weight of each point, for example its number of windows
     * @return the fitted model; {@code lambda} is in transactions per second
     * @throws IllegalArgumentException with fewer than 3 points or fewer than 2 distinct concurrencies
     */
    public static UslFit fit(double[] concurrency, double[] leaseSeconds, double[] weights) {
        int m = concurrency.length;
        if (leaseSeconds.length != m || weights.length != m) {
            throw new IllegalArgumentException("concurrency, leaseSeconds and weights must have the same length");
        }
        if (m < 3) {
            throw new IllegalArgumentException("at least 3 points are needed, got " + m);
        }
        long distinct = java.util.Arrays.stream(concurrency).map(c -> Math.round(c * 1000) / 1000.0).distinct().count();
        if (distinct < 2) {
            throw new IllegalArgumentException("at least 2 distinct concurrencies are needed");
        }
        for (int i = 0; i < m; i++) {
            if (!(concurrency[i] > 0) || !(leaseSeconds[i] > 0) || weights[i] < 0) {
                throw new IllegalArgumentException("point " + i + " is invalid: c=" + concurrency[i]
                        + ", W=" + leaseSeconds[i] + ", weight=" + weights[i]);
            }
        }

        // Candidate bases, all keeping the intercept a: {a, b, d}, {a, d}, {a, b}, {a}.
        boolean[][] subsets = {{true, true, true}, {true, false, true}, {true, true, false}, {true, false, false}};
        Solution best = null;
        for (boolean[] subset : subsets) {
            Solution candidate = solve(concurrency, leaseSeconds, weights, subset);
            if (candidate != null && candidate.feasible() && (best == null || candidate.sse < best.sse)) {
                best = candidate;
            }
        }
        if (best == null) {
            throw new IllegalArgumentException("no feasible lease-time fit (singular design)");
        }

        double a = best.coefficients[0];
        double b = best.coefficients[1];
        double d = best.coefficients[2];
        UslModel model = new UslModel(1.0 / a, b / a, d / a);

        // Delta method: lambda = 1/a, sigma = b/a, kappa = d/a.
        double[][] cov = best.covariance;
        double lambdaVar = cov[0][0] / Math.pow(a, 4);
        double sigmaVar = deltaRatioVariance(cov, 1, a, b);
        double kappaVar = deltaRatioVariance(cov, 2, a, d);
        return new UslFit(model, throughputRSquared(concurrency, leaseSeconds, model),
                Math.sqrt(Math.max(0, lambdaVar)), Math.sqrt(Math.max(0, sigmaVar)),
                Math.sqrt(Math.max(0, kappaVar)), Math.max(1, best.degreesOfFreedom));
    }

    /** Variance of {@code q / a} for the coefficient {@code q} at index {@code k}. */
    private static double deltaRatioVariance(double[][] cov, int k, double a, double q) {
        double ga = -q / (a * a);
        double gq = 1.0 / a;
        return ga * ga * cov[0][0] + 2 * ga * gq * cov[0][k] + gq * gq * cov[k][k];
    }

    private record Solution(double[] coefficients, double[][] covariance, double sse, int degreesOfFreedom) {
        boolean feasible() {
            return coefficients[0] > 0 && coefficients[1] >= 0 && coefficients[2] >= 0;
        }
    }

    /** Weighted least squares on the chosen basis columns; dropped coefficients are zero. */
    private static Solution solve(double[] c, double[] w, double[] weight, boolean[] subset) {
        int[] columns = java.util.stream.IntStream.range(0, 3).filter(j -> subset[j]).toArray();
        int p = columns.length;
        int m = c.length;
        double[][] xtx = new double[p][p];
        double[] xty = new double[p];
        for (int i = 0; i < m; i++) {
            double[] row = basis(c[i]);
            for (int r = 0; r < p; r++) {
                xty[r] += weight[i] * row[columns[r]] * w[i];
                for (int s = 0; s < p; s++) {
                    xtx[r][s] += weight[i] * row[columns[r]] * row[columns[s]];
                }
            }
        }
        double[][] inverse = invert(xtx);
        if (inverse == null) {
            return null;
        }
        double[] beta = new double[p];
        for (int r = 0; r < p; r++) {
            for (int s = 0; s < p; s++) {
                beta[r] += inverse[r][s] * xty[s];
            }
        }
        double[] coefficients = new double[3];
        for (int r = 0; r < p; r++) {
            coefficients[columns[r]] = beta[r];
        }
        double sse = 0;
        for (int i = 0; i < m; i++) {
            double[] row = basis(c[i]);
            double predicted = coefficients[0] * row[0] + coefficients[1] * row[1] + coefficients[2] * row[2];
            double residual = w[i] - predicted;
            sse += weight[i] * residual * residual;
        }
        int dof = m - p;
        // Relative weights: Cov(beta) = s^2 (X'WX)^-1 with s^2 = sum(w r^2) / (m - p).
        double residualVariance = dof > 0 ? sse / dof : 0.0;
        double[][] covariance = new double[3][3];
        for (int r = 0; r < p; r++) {
            for (int s = 0; s < p; s++) {
                covariance[columns[r]][columns[s]] = residualVariance * inverse[r][s];
            }
        }
        return new Solution(coefficients, covariance, sse, dof);
    }

    private static double[] basis(double c) {
        return new double[]{1.0, c - 1.0, c * (c - 1.0)};
    }

    /** Gauss-Jordan inverse with partial pivoting of a small symmetric matrix, or {@code null} if singular. */
    private static double[][] invert(double[][] matrix) {
        int n = matrix.length;
        double[][] a = new double[n][2 * n];
        double scale = 0;
        for (int i = 0; i < n; i++) {
            System.arraycopy(matrix[i], 0, a[i], 0, n);
            a[i][n + i] = 1.0;
            for (int j = 0; j < n; j++) {
                scale = Math.max(scale, Math.abs(matrix[i][j]));
            }
        }
        for (int col = 0; col < n; col++) {
            int pivot = col;
            for (int r = col + 1; r < n; r++) {
                if (Math.abs(a[r][col]) > Math.abs(a[pivot][col])) {
                    pivot = r;
                }
            }
            if (Math.abs(a[pivot][col]) <= 1e-12 * Math.max(scale, 1e-300)) {
                return null;
            }
            double[] tmp = a[col];
            a[col] = a[pivot];
            a[pivot] = tmp;
            double div = a[col][col];
            for (int j = 0; j < 2 * n; j++) {
                a[col][j] /= div;
            }
            for (int r = 0; r < n; r++) {
                if (r != col && a[r][col] != 0.0) {
                    double factor = a[r][col];
                    for (int j = 0; j < 2 * n; j++) {
                        a[r][j] -= factor * a[col][j];
                    }
                }
            }
        }
        double[][] inverse = new double[n][n];
        for (int i = 0; i < n; i++) {
            System.arraycopy(a[i], n, inverse[i], 0, n);
        }
        return inverse;
    }

    /** {@code R^2} of the throughputs {@code X = c / W} against the fitted {@code X(c)}. */
    private static double throughputRSquared(double[] c, double[] w, UslModel model) {
        int m = c.length;
        double mean = 0;
        for (int i = 0; i < m; i++) {
            mean += c[i] / w[i];
        }
        mean /= m;
        double ssTot = 0;
        double ssRes = 0;
        for (int i = 0; i < m; i++) {
            double x = c[i] / w[i];
            ssTot += (x - mean) * (x - mean);
            double r = x - model.throughputAt(c[i]);
            ssRes += r * r;
        }
        return ssTot == 0 ? (ssRes == 0 ? 1.0 : 0.0) : 1.0 - ssRes / ssTot;
    }
}
