package com.vladmihalcea.phd.pool.usl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the USL closed form: the single-connection anchor, the retrograde optimum, and the
 * degenerate no-coherency case. No Docker required.
 *
 * @author Vlad Mihalcea
 */
public class UslModelTest {

    @Test
    public void throughputAtOneEqualsLambda() {
        UslModel model = new UslModel(1000.0, 0.02, 0.0001);
        assertEquals(1000.0, model.throughputAt(1.0), 1e-9);
    }

    @Test
    public void optimalPoolSizeMatchesClosedForm() {
        // N* = sqrt((1 - sigma)/kappa) = sqrt(0.98 / 0.0001) = 98.99...
        UslModel model = new UslModel(1000.0, 0.02, 0.0001);
        assertEquals(98, model.optimalPoolSize());
        assertEquals(Math.sqrt(0.98 / 0.0001), model.optimalPoolSizeReal(), 1e-6);
    }

    @Test
    public void peakThroughputIsAtTheOptimum() {
        UslModel model = new UslModel(1000.0, 0.02, 0.0001);
        double peak = model.peakThroughput();
        double atReal = model.throughputAt(model.optimalPoolSizeReal());
        assertEquals(atReal, peak, 1e-6);
        // The peak must dominate throughput far into the retrograde region.
        assertTrue(peak > model.throughputAt(300), "peak should exceed a heavily oversized pool");
    }

    @Test
    public void noCoherencyMeansUnboundedOptimum() {
        UslModel amdahl = new UslModel(1000.0, 0.05, 0.0);
        assertEquals(Integer.MAX_VALUE, amdahl.optimalPoolSize());
        assertTrue(Double.isInfinite(amdahl.optimalPoolSizeReal()));
    }

    @Test
    public void poolSizeAtFractionIsTheSmallestSizeReachingTheFraction() {
        UslModel[] models = {
                new UslModel(6381.39, 0.121159, 0.000432233),
                new UslModel(3422.12, 0.0960180, 0.000176880),
                new UslModel(228.293, 0.0652796, 0.000476481),
                new UslModel(1500, 0.01, 0.00015),
        };
        for (UslModel model : models) {
            for (double fraction : new double[]{0.90, 0.95, 0.98, 0.99}) {
                int plateauStart = model.poolSizeAtFraction(fraction);
                double target = fraction * model.peakThroughput();
                assertTrue(model.throughputAt(plateauStart) >= target, model + " at " + fraction);
                assertTrue(plateauStart == 1 || model.throughputAt(plateauStart - 1) < target, model + " at " + fraction);
                assertTrue(plateauStart <= model.optimalPoolSize() + 1, model + " at " + fraction);
            }
        }
    }

    @Test
    public void poolSizeAtFractionMatchesThePlanTable() {
        // HikariCP read-mostly from the ICASC fit (metrics/UslFit/usl-coefficients.csv): N* = 45.
        UslModel hikariReadMostly = new UslModel(6381.39, 0.121159, 0.000432233);
        assertEquals(18, hikariReadMostly.poolSizeAtFraction(0.90));
        assertEquals(24, hikariReadMostly.poolSizeAtFraction(0.95));
        assertEquals(31, hikariReadMostly.poolSizeAtFraction(0.98));
        assertEquals(34, hikariReadMostly.poolSizeAtFraction(0.99));
    }

    @Test
    public void poolSizeAtFractionWithoutCoherency() {
        // Amdahl: X -> lambda/sigma = 20,000; 90% of it needs N >= 0.9 * 0.95 / (0.05 * 0.1) = 171.
        UslModel amdahl = new UslModel(1000.0, 0.05, 0.0);
        assertEquals(171, amdahl.poolSizeAtFraction(0.90));
        assertEquals(Integer.MAX_VALUE, new UslModel(1000.0, 0.0, 0.0).poolSizeAtFraction(0.90));
        assertThrows(IllegalArgumentException.class, () -> amdahl.poolSizeAtFraction(1.0));
    }

    @Test
    public void poolSizeAtFractionWithinACap() {
        // DBCP2 write-heavy: N* = 71. Capped at 48, the reference is X(48), so the plateau start moves down.
        UslModel model = new UslModel(3422.12, 0.0960180, 0.000176880);
        int uncapped = model.poolSizeAtFraction(0.95);
        int capped = model.poolSizeAtFraction(0.95, 48);
        assertTrue(capped < uncapped, capped + " vs " + uncapped);
        assertTrue(model.throughputAt(capped) >= 0.95 * model.throughputAt(48));
        assertTrue(model.throughputAt(capped - 1) < 0.95 * model.throughputAt(48));
        // A cap above N* changes nothing.
        assertEquals(uncapped, model.poolSizeAtFraction(0.95, 200));
        // Without coherency, the cap replaces the Amdahl asymptote: 0.95 * X(48) is reached well below 48.
        UslModel amdahl = new UslModel(1000.0, 0.05, 0.0);
        int amdahlCapped = amdahl.poolSizeAtFraction(0.95, 48);
        assertTrue(amdahlCapped < 48 && amdahl.throughputAt(amdahlCapped) >= 0.95 * amdahl.throughputAt(48));
    }

    @Test
    public void negativeCoefficientsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new UslModel(1000, -0.1, 0.001));
        assertThrows(IllegalArgumentException.class, () -> new UslModel(1000, 0.1, -0.001));
        assertThrows(IllegalArgumentException.class, () -> new UslModel(0, 0.1, 0.001));
    }
}
