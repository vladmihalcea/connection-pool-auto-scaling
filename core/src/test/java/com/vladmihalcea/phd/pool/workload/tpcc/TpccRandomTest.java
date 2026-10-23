package com.vladmihalcea.phd.pool.workload.tpcc;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the TPC-C input generators against the specification: the last-name syllables (the example in
 * clause 4.3.2.3), the {@code NURand} ranges and skew, the {@code ORIGINAL} share of the data strings, and
 * the 45/43/4/4/4 transaction mix. No database required.
 *
 * @author Vlad Mihalcea
 */
public class TpccRandomTest {

    @Test
    public void lastNameFollowsTheSpecificationExample() {
        assertEquals("PRICALLYOUGHT", TpccRandom.lastName(371));
        assertEquals("BARBARBAR", TpccRandom.lastName(0));
        assertEquals("EINGEINGEING", TpccRandom.lastName(999));
    }

    @Test
    public void nuRandStaysInRangeAndIsSkewed() {
        SplittableRandom random = new SplittableRandom(1);
        int[] hits = new int[TpccSchema.CUSTOMERS_PER_DISTRICT + 1];
        int draws = 300_000;
        for (int i = 0; i < draws; i++) {
            int id = TpccRandom.customerId(random);
            assertTrue(id >= 1 && id <= TpccSchema.CUSTOMERS_PER_DISTRICT, "customer id " + id);
            hits[id]++;
            int item = TpccRandom.itemId(random);
            assertTrue(item >= 1 && item <= TpccSchema.ITEMS, "item id " + item);
        }
        int max = 0;
        for (int h : hits) {
            max = Math.max(max, h);
        }
        // Uniform draws would give ~100 hits per id; NURand concentrates them on some ids.
        assertTrue(max > 3 * draws / TpccSchema.CUSTOMERS_PER_DISTRICT, "NURand should be skewed, max " + max);
    }

    @Test
    public void tenPercentOfDataStringsContainOriginal() {
        SplittableRandom random = new SplittableRandom(2);
        int original = 0;
        int draws = 100_000;
        for (int i = 0; i < draws; i++) {
            String data = TpccRandom.data(random, 26, 50);
            assertTrue(data.length() >= 26 && data.length() <= 50);
            if (data.contains("ORIGINAL")) {
                original++;
            }
        }
        assertEquals(0.10, (double) original / draws, 0.01);
    }

    @Test
    public void transactionMixMatchesTheStandard() {
        SplittableRandom random = new SplittableRandom(3);
        Map<TpccWorkload.Type, Integer> counts = new EnumMap<>(TpccWorkload.Type.class);
        int draws = 200_000;
        for (int i = 0; i < draws; i++) {
            counts.merge(TpccWorkload.nextType(random), 1, Integer::sum);
        }
        for (TpccWorkload.Type type : TpccWorkload.Type.values()) {
            assertEquals(type.percent() / 100.0, counts.get(type) / (double) draws, 0.01, type.name());
        }
    }
}
