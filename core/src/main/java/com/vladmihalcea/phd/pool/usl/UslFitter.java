package com.vladmihalcea.phd.pool.usl;

import org.apache.commons.math3.fitting.leastsquares.LeastSquaresBuilder;
import org.apache.commons.math3.fitting.leastsquares.LeastSquaresOptimizer;
import org.apache.commons.math3.fitting.leastsquares.LevenbergMarquardtOptimizer;
import org.apache.commons.math3.fitting.leastsquares.MultivariateJacobianFunction;
import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.ArrayRealVector;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.linear.RealVector;
import org.apache.commons.math3.util.Pair;

/**
 * Fits {@link UslModel} coefficients ({@code lambda}, {@code sigma}, {@code kappa}) to measured
 * (pool size, throughput) points by non-linear least squares (Levenberg&ndash;Marquardt), and reports
 * {@code R^2} and per-coefficient standard errors. This is the mechanism behind experiment E2: extract
 * the contention and coherency of each connection pool from its throughput curve.
 * <p>
 * The model is fitted directly in its non-linear form (rather than Gunther's textbook quadratic-regression
 * transform on the deficiency {@code N/X - 1}) because the direct fit does not distort the error structure
 * of the measured throughput and yields honest confidence intervals. A transformed-regression
 * cross-check is provided separately by {@link #fitByRegression(double[], double[])}.
 *
 * @author Vlad Mihalcea
 */
public final class UslFitter {

    private static final int MAX_ITERATIONS = 10_000;

    private UslFitter() {
    }

    /**
     * Non-linear least-squares fit.
     *
     * @param poolSizes    the measured pool sizes (must contain at least 3 distinct values)
     * @param throughputs  the measured throughput at each pool size (same length, all positive)
     * @return the fitted model with goodness-of-fit statistics
     */
    public static UslFit fit(double[] poolSizes, double[] throughputs) {
        validate(poolSizes, throughputs);
        final int m = poolSizes.length;

        double[] start = initialGuess(poolSizes, throughputs);

        MultivariateJacobianFunction model = params -> {
            double lambda = params.getEntry(0);
            double sigma = params.getEntry(1);
            double kappa = params.getEntry(2);
            RealVector value = new ArrayRealVector(m);
            RealMatrix jacobian = new Array2DRowRealMatrix(m, 3);
            for (int i = 0; i < m; i++) {
                double n = poolSizes[i];
                double d = 1.0 + sigma * (n - 1.0) + kappa * n * (n - 1.0);
                double x = lambda * n / d;
                value.setEntry(i, x);
                jacobian.setEntry(i, 0, n / d);
                jacobian.setEntry(i, 1, -lambda * n * (n - 1.0) / (d * d));
                jacobian.setEntry(i, 2, -lambda * n * n * (n - 1.0) / (d * d));
            }
            return new Pair<>(value, jacobian);
        };

        LeastSquaresOptimizer.Optimum optimum = new LevenbergMarquardtOptimizer().optimize(
                new LeastSquaresBuilder()
                        .start(start)
                        .model(model)
                        .target(throughputs)
                        .lazyEvaluation(false)
                        .maxEvaluations(MAX_ITERATIONS)
                        .maxIterations(MAX_ITERATIONS)
                        .build());

        RealVector point = optimum.getPoint();
        double lambda = Math.max(1e-9, point.getEntry(0));
        double sigma = Math.max(0.0, point.getEntry(1));
        double kappa = Math.max(0.0, point.getEntry(2));
        UslModel fitted = new UslModel(lambda, sigma, kappa);

        double rSquared = rSquared(poolSizes, throughputs, fitted);
        double[] stdErr = standardErrors(optimum, m);
        return new UslFit(fitted, rSquared, stdErr[0], stdErr[1], stdErr[2]);
    }

    /**
     * Gunther's textbook cross-check: regress the deficiency {@code y = N/X_norm - 1} on {@code (N-1)}
     * with a quadratic through a shifted origin, where {@code X_norm = X(N)/X(1)}. Recovers
     * {@code sigma} and {@code kappa} from the quadratic coefficients. Provided so the paper can report
     * agreement between the two estimation methods; the non-linear {@link #fit} is the primary result.
     */
    public static UslModel fitByRegression(double[] poolSizes, double[] throughputs) {
        validate(poolSizes, throughputs);
        // X(1): use the throughput at the smallest observed pool size, normalised to N=1.
        int minIdx = 0;
        for (int i = 1; i < poolSizes.length; i++) {
            if (poolSizes[i] < poolSizes[minIdx]) {
                minIdx = i;
            }
        }
        double lambda = throughputs[minIdx] / poolSizes[minIdx];

        // Fit y = a*(N-1) + b*(N-1)^2 with y = N/(X/lambda) - 1, via ordinary least squares (no intercept).
        // Using basis u = (N-1), v = (N-1)^2 and solving the 2x2 normal equations.
        double suu = 0, suv = 0, svv = 0, suy = 0, svy = 0;
        for (int i = 0; i < poolSizes.length; i++) {
            double n = poolSizes[i];
            double xNorm = throughputs[i] / lambda;
            double y = n / xNorm - 1.0;
            double u = n - 1.0;
            double v = u * u;
            suu += u * u;
            suv += u * v;
            svv += v * v;
            suy += u * y;
            svy += v * y;
        }
        double det = suu * svv - suv * suv;
        double sigma;
        double kappa;
        if (Math.abs(det) < 1e-12) {
            sigma = 0.0;
            kappa = 0.0;
        } else {
            sigma = (svv * suy - suv * svy) / det;
            kappa = (suu * svy - suv * suy) / det;
        }
        return new UslModel(Math.max(1e-9, lambda), Math.max(0.0, sigma), Math.max(0.0, kappa));
    }

    private static double[] initialGuess(double[] poolSizes, double[] throughputs) {
        int minIdx = 0;
        for (int i = 1; i < poolSizes.length; i++) {
            if (poolSizes[i] < poolSizes[minIdx]) {
                minIdx = i;
            }
        }
        double lambda0 = throughputs[minIdx] / poolSizes[minIdx];
        return new double[]{lambda0, 0.1, 0.001};
    }

    private static double rSquared(double[] poolSizes, double[] throughputs, UslModel model) {
        double mean = 0.0;
        for (double t : throughputs) {
            mean += t;
        }
        mean /= throughputs.length;

        double rss = 0.0;
        double tss = 0.0;
        for (int i = 0; i < poolSizes.length; i++) {
            double predicted = model.throughputAt(poolSizes[i]);
            double residual = throughputs[i] - predicted;
            rss += residual * residual;
            double dev = throughputs[i] - mean;
            tss += dev * dev;
        }
        return tss == 0.0 ? 1.0 : 1.0 - rss / tss;
    }

    private static double[] standardErrors(LeastSquaresOptimizer.Optimum optimum, int m) {
        try {
            double rss = 0.0;
            RealVector residuals = optimum.getResiduals();
            for (int i = 0; i < residuals.getDimension(); i++) {
                double r = residuals.getEntry(i);
                rss += r * r;
            }
            int dof = Math.max(1, m - 3);
            double residualVariance = rss / dof;
            RealMatrix covariance = optimum.getCovariances(1e-14);
            double[] stdErr = new double[3];
            for (int i = 0; i < 3; i++) {
                stdErr[i] = Math.sqrt(Math.max(0.0, covariance.getEntry(i, i) * residualVariance));
            }
            return stdErr;
        } catch (Exception e) {
            // Singular Jacobian (e.g. a near-perfect fit on few points) — report zero uncertainty.
            return new double[]{0.0, 0.0, 0.0};
        }
    }

    private static void validate(double[] poolSizes, double[] throughputs) {
        if (poolSizes.length != throughputs.length) {
            throw new IllegalArgumentException("poolSizes and throughputs must have the same length");
        }
        long distinct = java.util.Arrays.stream(poolSizes).distinct().count();
        if (distinct < 3) {
            throw new IllegalArgumentException(
                    "USL fitting needs at least 3 distinct pool sizes, got " + distinct);
        }
        for (int i = 0; i < throughputs.length; i++) {
            if (throughputs[i] <= 0 || poolSizes[i] <= 0) {
                throw new IllegalArgumentException(
                        "pool sizes and throughputs must be positive at index " + i);
            }
        }
    }
}
