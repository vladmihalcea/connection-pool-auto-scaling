package com.vladmihalcea.phd.pool.usl;

/**
 * The outcome of fitting a {@link UslModel} to measured (pool size, throughput) points: the fitted
 * model, the coefficient of determination {@code R^2}, and the standard errors of the three coefficients
 * (used to report 95% confidence intervals as {@code +/- 1.96 * stdErr} in the paper's parameter table).
 *
 * @author Vlad Mihalcea
 */
public record UslFit(
        UslModel model,
        double rSquared,
        double lambdaStdErr,
        double sigmaStdErr,
        double kappaStdErr) {

    public int optimalPoolSize() {
        return model.optimalPoolSize();
    }

    public double sigmaCi95() {
        return 1.96 * sigmaStdErr;
    }

    public double kappaCi95() {
        return 1.96 * kappaStdErr;
    }

    public double lambdaCi95() {
        return 1.96 * lambdaStdErr;
    }
}
