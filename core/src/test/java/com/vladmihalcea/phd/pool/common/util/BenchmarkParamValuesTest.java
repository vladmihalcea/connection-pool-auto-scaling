package com.vladmihalcea.phd.pool.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The {@code -Djmh.param.*} override syntax used for the dense pool-size sweep.
 *
 * @author Vlad Mihalcea
 */
public class BenchmarkParamValuesTest {

    @Test
    public void expandsValuesRangesAndSteps() {
        assertArrayEquals(new String[]{"1", "2", "3", "10", "20", "30", "96"},
                AbstractBenchmarkTest.expandParamValues("1..3, 10..30:10, 96"));
        assertArrayEquals(new String[]{"hikari", "dbcp2"},
                AbstractBenchmarkTest.expandParamValues("hikari,dbcp2"));
    }

    @Test
    public void denseSweepHas68Sizes() {
        // Step 1 up to 40, then step 2 up to 96: the K1 grid of the plateau-start study.
        assertEquals(68, AbstractBenchmarkTest.expandParamValues("1..40,42..96:2").length);
    }
}
