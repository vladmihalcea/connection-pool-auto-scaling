package com.vladmihalcea.phd.pool.usl;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validation of the Levenberg&ndash;Marquardt USL fitter by round-tripping known coefficients: generate
 * throughput from a ground-truth {@link UslModel}, fit, and assert the coefficients, {@code N*} and
 * {@code R^2} are recovered — first on clean data, then under seeded multiplicative noise. This is the
 * scientific gate behind experiment E2. No Docker required.
 *
 * @author Vlad Mihalcea
 */
public class UslFitterTest {

    private static final double[] POOL_SIZES = {1, 2, 4, 8, 12, 16, 24, 32, 48, 64, 96, 128};

    @Test
    public void recoversCoefficientsFromCleanData() {
        UslModel truth = new UslModel(1000.0, 0.02, 0.0001);
        double[] throughput = generate(truth, POOL_SIZES, 0.0, null);

        UslFit fit = UslFitter.fit(POOL_SIZES, throughput);

        assertEquals(truth.lambda(), fit.model().lambda(), truth.lambda() * 0.01);
        assertEquals(truth.sigma(), fit.model().sigma(), 0.002);
        assertEquals(truth.kappa(), fit.model().kappa(), 0.00002);
        assertEquals(truth.optimalPoolSize(), fit.optimalPoolSize(), 2);
        assertTrue(fit.rSquared() > 0.9999, "clean data should fit almost perfectly, got R2=" + fit.rSquared());
    }

    @Test
    public void recoversOptimumUnderNoise() {
        UslModel truth = new UslModel(1200.0, 0.03, 0.00008); // N* = sqrt(0.97/0.00008) ~= 110
        Random random = new Random(1234L);
        double[] throughput = generate(truth, POOL_SIZES, 0.03, random); // 3% multiplicative noise

        UslFit fit = UslFitter.fit(POOL_SIZES, throughput);

        assertTrue(fit.rSquared() > 0.99, "noisy fit should still be strong, got R2=" + fit.rSquared());
        assertEquals(truth.optimalPoolSize(), fit.optimalPoolSize(), 12);
        assertTrue(fit.sigmaStdErr() >= 0 && fit.kappaStdErr() >= 0, "standard errors are defined");
        assertTrue(fit.kappaCi95() > 0, "a noisy fit should report a non-zero kappa CI");
    }

    @Test
    public void detectsRetrogradeRegion() {
        // A curve that visibly peaks and declines must yield kappa > 0 and an interior optimum.
        UslModel truth = new UslModel(500.0, 0.01, 0.0005); // N* = sqrt(0.99/0.0005) ~= 44
        double[] throughput = generate(truth, POOL_SIZES, 0.0, null);

        UslFit fit = UslFitter.fit(POOL_SIZES, throughput);

        assertTrue(fit.model().kappa() > 0, "coherency coefficient must be positive");
        assertTrue(fit.optimalPoolSize() < 128, "optimum must be interior to the sampled range");
    }

    @Test
    public void regressionCrossCheckAgreesOnCleanData() {
        UslModel truth = new UslModel(1000.0, 0.02, 0.0001);
        double[] throughput = generate(truth, POOL_SIZES, 0.0, null);

        UslModel regressed = UslFitter.fitByRegression(POOL_SIZES, throughput);

        assertEquals(truth.sigma(), regressed.sigma(), 0.005);
        assertEquals(truth.kappa(), regressed.kappa(), 0.00003);
    }

    @Test
    public void requiresThreeDistinctPoolSizes() {
        assertThrows(IllegalArgumentException.class,
                () -> UslFitter.fit(new double[]{4, 4, 8}, new double[]{100, 100, 150}));
    }

    private static double[] generate(UslModel model, double[] poolSizes, double noiseFraction, Random random) {
        double[] out = new double[poolSizes.length];
        for (int i = 0; i < poolSizes.length; i++) {
            double x = model.throughputAt(poolSizes[i]);
            if (noiseFraction > 0 && random != null) {
                x *= 1.0 + noiseFraction * random.nextGaussian();
            }
            out[i] = x;
        }
        return out;
    }
}
