package com.vladmihalcea.phd.pool.usl;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lease-time (response-time) form of the USL: Schwartz's Eq. 6, 9, and 10 in {@link UslModel}, and the
 * linear least-squares fit of {@link LeaseTimeFitter}.
 *
 * @author Vlad Mihalcea
 */
public class LeaseTimeFitterTest {

    private static final UslModel TRUTH = new UslModel(1500, 0.03, 0.0005);

    @Test
    public void responseTimeIsLittlesLawOverTheUsl() {
        for (int n = 1; n <= 96; n++) {
            assertEquals(n / TRUTH.throughputAt(n), TRUTH.responseTimeAt(n), 1e-12);
        }
        assertEquals(1.0 / TRUTH.lambda(), TRUTH.responseTimeAt(1), 1e-15);
    }

    @Test
    public void concurrencyForResponseTimeInvertsTheResponseTime() {
        for (int n = 1; n <= 96; n++) {
            double r = TRUTH.responseTimeAt(n);
            assertEquals(n, TRUTH.concurrencyForResponseTime(r), 1e-6);
            assertEquals(TRUTH.throughputAt(n), TRUTH.throughputForResponseTime(r), 1e-6);
        }
        assertEquals(0.0, TRUTH.concurrencyForResponseTime(0.5 / TRUTH.lambda()));
        // kappa = 0: linear residence time.
        UslModel amdahl = new UslModel(1000, 0.05, 0);
        assertEquals(20, amdahl.concurrencyForResponseTime(amdahl.responseTimeAt(20)), 1e-9);
    }

    @Test
    public void recoversTheCoefficientsFromExactLeaseTimes() {
        double[] c = {1, 2, 4, 8, 16, 24, 32};
        double[] w = new double[c.length];
        for (int i = 0; i < c.length; i++) {
            w[i] = TRUTH.responseTimeAt(c[i]);
        }
        UslFit fit = LeaseTimeFitter.fit(c, w);
        assertEquals(TRUTH.lambda(), fit.model().lambda(), 1e-6);
        assertEquals(TRUTH.sigma(), fit.model().sigma(), 1e-9);
        assertEquals(TRUTH.kappa(), fit.model().kappa(), 1e-11);
        assertEquals(1.0, fit.rSquared(), 1e-9);
    }

    @Test
    public void locatesThePlateauStartFromNoisyPointsBelowThePeak() {
        // Points up to 32 only (below N* = 44): the curvature of W(c) still identifies kappa.
        Random random = new Random(3);
        double[] c = new double[24];
        double[] w = new double[c.length];
        for (int i = 0; i < c.length; i++) {
            c[i] = 2 + i * 30.0 / (c.length - 1);
            w[i] = TRUTH.responseTimeAt(c[i]) * (1 + 0.02 * random.nextGaussian());
        }
        UslFit fit = LeaseTimeFitter.fit(c, w);
        int expected = TRUTH.poolSizeAtFraction(0.99, 96);
        int fitted = fit.model().poolSizeAtFraction(0.99, 96);
        assertTrue(Math.abs(fitted - expected) <= 4, "fitted N_0.01 " + fitted + ", true " + expected);
        assertTrue(fit.rSquared() > 0.95, "R2 " + fit.rSquared());
        assertTrue(fit.kappaStdErr() > 0 && fit.sigmaStdErr() > 0);
    }

    @Test
    public void dropsANegativeCoherencyTerm() {
        // A concave W(c) has no USL reading with kappa >= 0: the fitter falls back to kappa = 0.
        double[] c = {1, 2, 4, 8, 16};
        double[] w = new double[c.length];
        for (int i = 0; i < c.length; i++) {
            w[i] = 1e-3 * (1 + 0.1 * (c[i] - 1) - 0.002 * c[i] * (c[i] - 1));
        }
        UslFit fit = LeaseTimeFitter.fit(c, w);
        assertEquals(0.0, fit.model().kappa());
        assertTrue(fit.model().sigma() > 0);
    }
}
