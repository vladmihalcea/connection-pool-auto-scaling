package com.vladmihalcea.phd.pool.usl;

import org.apache.commons.math3.distribution.TDistribution;

/**
 * The outcome of fitting a {@link UslModel} to measured (pool size, throughput) points: the fitted
 * model, the coefficient of determination {@code R^2}, the standard errors of the three coefficients,
 * and the residual degrees of freedom of the fit. The 95% confidence intervals reported in the paper's
 * parameter table are {@code +/- t(0.975, dof) * stdErr}, where the Student-t critical value (rather than
 * the large-sample normal 1.96) keeps the intervals honest for the small samples these curves are fitted
 * from (e.g. 10 pool sizes and 3 parameters give 7 dof and {@code t ~= 2.36}).
 *
 * @author Vlad Mihalcea
 */
public record UslFit(
        UslModel model,
        double rSquared,
        double lambdaStdErr,
        double sigmaStdErr,
        double kappaStdErr,
        int degreesOfFreedom) {

    public int optimalPoolSize() {
        return model.optimalPoolSize();
    }

    public double sigmaCi95() {
        return tMultiplier() * sigmaStdErr;
    }

    public double kappaCi95() {
        return tMultiplier() * kappaStdErr;
    }

    public double lambdaCi95() {
        return tMultiplier() * lambdaStdErr;
    }

    /**
     * Two-sided 95% Student-t critical value for the fit's residual degrees of freedom. This is the
     * small-sample-correct multiplier for a 95% confidence interval; it converges to the normal 1.96 as
     * the number of measured points grows.
     */
    private double tMultiplier() {
        return new TDistribution(degreesOfFreedom).inverseCumulativeProbability(0.975);
    }
}
