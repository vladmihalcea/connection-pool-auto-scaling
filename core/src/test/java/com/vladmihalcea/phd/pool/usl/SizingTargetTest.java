package com.vladmihalcea.phd.pool.usl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The throughput elasticity of the USL and the two sizing targets: the elasticity is 0 at {@code N*}, the closed
 * form inverts it, the point of diminishing returns lies between the plateau start and {@code N*}, and the share of
 * the peak it reaches depends on the shape of the curve.
 *
 * @author Vlad Mihalcea
 */
public class SizingTargetTest {

    private static final UslModel TRUTH = new UslModel(1500, 0.03, 0.0005);

    @Test
    public void elasticityMatchesTheNumericalDerivative() {
        for (double n = 2; n <= 90; n += 4) {
            double h = 1e-5;
            double numeric = (Math.log(TRUTH.throughputAt(n * (1 + h))) - Math.log(TRUTH.throughputAt(n * (1 - h))))
                    / (Math.log(1 + h) - Math.log(1 - h));
            assertEquals(numeric, TRUTH.elasticityAt(n), 1e-6);
            // eta = 1 - d ln R / d ln N, Little's Law
            double numericR = (Math.log(TRUTH.responseTimeAt(n * (1 + h))) - Math.log(TRUTH.responseTimeAt(n * (1 - h))))
                    / (Math.log(1 + h) - Math.log(1 - h));
            assertEquals(1 - numericR, TRUTH.elasticityAt(n), 1e-6);
        }
        assertEquals(0.0, TRUTH.elasticityAt(TRUTH.optimalPoolSizeReal()), 1e-12);
    }

    @Test
    public void closedFormInvertsTheElasticity() {
        for (double eta : new double[]{0.02, 0.05, 0.1, 0.2, 0.5}) {
            double n = TRUTH.poolSizeAtElasticityReal(eta);
            assertEquals(eta, TRUTH.elasticityAt(n), 1e-9);
            assertTrue(n < TRUTH.optimalPoolSizeReal());
        }
        // kappa = 0: the Amdahl curve.
        UslModel amdahl = new UslModel(1000, 0.05, 0);
        assertEquals(0.1, amdahl.elasticityAt(amdahl.poolSizeAtElasticityReal(0.1)), 1e-9);
    }

    @Test
    public void theTargetLiesBetweenThePlateauStartAndTheOptimum() {
        int target = SizingTarget.DEFAULT.poolSize(TRUTH, 96);
        int plateauStart = TRUTH.poolSizeAtFraction(0.95, 96);
        assertTrue(target >= plateauStart && target <= TRUTH.optimalPoolSize(),
                "N_eta " + target + ", N_0.05 " + plateauStart + ", N* " + TRUTH.optimalPoolSize());
    }

    @Test
    public void theShareOfThePeakFollowsTheShapeOfTheCurve() {
        // Same N* = 44: coherency-dominated (low sigma) against contention-dominated (high sigma).
        UslModel coherency = new UslModel(1000, 0.0005, (1 - 0.0005) / (44.0 * 44.0));
        UslModel contention = new UslModel(1000, 0.15, (1 - 0.15) / (44.0 * 44.0));
        double coherencyShare = coherency.throughputAt(SizingTarget.DEFAULT.poolSize(coherency, 96))
                / coherency.peakThroughput();
        double contentionShare = contention.throughputAt(SizingTarget.DEFAULT.poolSize(contention, 96))
                / contention.peakThroughput();
        assertTrue(coherencyShare > contentionShare, coherencyShare + " vs " + contentionShare);
        assertTrue(SizingTarget.DEFAULT.poolSize(contention, 96) < SizingTarget.DEFAULT.poolSize(coherency, 96));
    }

    @Test
    public void parsesBothForms() {
        assertEquals(new SizingTarget.Elasticity(0.1), SizingTarget.parse("eta:0.1"));
        assertEquals(new SizingTarget.Tolerance(0.01), SizingTarget.parse("eps:0.01"));
        assertEquals(new SizingTarget.Tolerance(0.05), SizingTarget.parse("0.05"));
        assertEquals("eta:0.1", SizingTarget.DEFAULT.id());
    }
}
