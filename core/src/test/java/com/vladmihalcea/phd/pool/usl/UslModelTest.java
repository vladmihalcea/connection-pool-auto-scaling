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
    public void negativeCoefficientsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new UslModel(1000, -0.1, 0.001));
        assertThrows(IllegalArgumentException.class, () -> new UslModel(1000, 0.1, -0.001));
        assertThrows(IllegalArgumentException.class, () -> new UslModel(0, 0.1, 0.001));
    }
}
