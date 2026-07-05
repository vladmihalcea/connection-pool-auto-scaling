package com.vladmihalcea.phd.pool.workload;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Fast, Docker-free checks on the workload factory: the named profiles resolve and unknown names fail
 * fast. The transactional behaviour itself is exercised by the DB-backed benchmarks.
 *
 * @author Vlad Mihalcea
 */
public class OltpWorkloadTest {

    @Test
    public void namedProfilesResolve() {
        assertEquals("readMostly", OltpWorkload.byName("readMostly", 1_000).name());
        assertEquals("writeHeavy", OltpWorkload.byName("writeHeavy", 1_000).name());
        assertEquals("longTx", OltpWorkload.byName("longTx", 1_000).name());
    }

    @Test
    public void unknownProfileFailsFast() {
        assertThrows(IllegalArgumentException.class, () -> OltpWorkload.byName("nope", 1_000));
    }
}
