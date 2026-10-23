package com.vladmihalcea.phd.pool.workload.sysbench;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The database-independent parts of the sysbench-derived workload: the generated values and the errors
 * that restart a transaction.
 *
 * @author Vlad Mihalcea
 */
public class SysbenchWorkloadTest {

    @Test
    public void valuesFollowTheSysbenchTemplates() {
        SplittableRandom random = new SplittableRandom(7);
        String c = SysbenchWorkload.cValue(random);
        String pad = SysbenchWorkload.padValue(random);
        assertEquals(119, c.length());
        assertEquals(59, pad.length());
        assertTrue(c.matches("\\d{11}(-\\d{11}){9}"), c);
        assertTrue(pad.matches("\\d{11}(-\\d{11}){4}"), pad);
    }

    @Test
    public void deadlocksAndDuplicateKeysAreRetried() {
        assertTrue(SysbenchWorkload.isRetryable(new SQLException("deadlock", "40P01")));
        assertTrue(SysbenchWorkload.isRetryable(new SQLException("unique violation", "23505")));
        assertTrue(SysbenchWorkload.isRetryable(new SQLException("Deadlock found", "40001", 1213)));
        assertTrue(SysbenchWorkload.isRetryable(new SQLException("Duplicate entry", "23000", 1062)));
        assertTrue(SysbenchWorkload.isRetryable(new SQLException("Lock wait timeout", "HY000", 1205)));
        assertFalse(SysbenchWorkload.isRetryable(new SQLException("syntax error", "42601")));
    }

    @Test
    public void namesSelectTheTwoMixes() {
        SysbenchSchema schema = new SysbenchSchema(4, 100_000);
        assertTrue(SysbenchWorkload.isSysbench("oltpReadWrite"));
        assertTrue(SysbenchWorkload.isSysbench("oltpReadOnly"));
        assertFalse(SysbenchWorkload.isSysbench("readMostly"));
        assertEquals("oltpReadOnly", SysbenchWorkload.byName("oltpReadOnly", schema).name());
        assertThrows(IllegalArgumentException.class, () -> SysbenchWorkload.byName("oltpPointSelect", schema));
    }
}
