package com.vladmihalcea.phd.pool.workload.tpcc;

import java.util.SplittableRandom;

/**
 * The random input generators of the TPC-C specification (clause 2.1.6 and 4.3.2): uniform integers, the
 * non-uniform {@code NURand(A, x, y)} that skews customer and item selection toward hot rows, the
 * syllable-built customer last names, and random strings. All draws go through a caller-supplied seeded
 * {@link SplittableRandom}, so a run is reproducible from its seed.
 *
 * @author Vlad Mihalcea
 */
public final class TpccRandom {

    /** Last-name syllables, indexed by the digits of a number in {@code [0, 999]} (clause 4.3.2.3). */
    private static final String[] SYLLABLES =
            {"BAR", "OUGHT", "ABLE", "PRI", "PRES", "ESE", "ANTI", "CALLY", "ATION", "EING"};

    private static final char[] ALPHANUMERIC =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();

    /**
     * The run-time constants {@code C} of {@code NURand} for customer last names, customer ids and item
     * ids. The specification lets an implementation pick them once per run; fixed values keep runs
     * comparable.
     */
    static final int C_LAST = 157;
    static final int C_ID = 259;
    static final int C_ITEM = 7911;

    private TpccRandom() {
    }

    /** A uniform integer in {@code [min, max]}. */
    public static int uniform(SplittableRandom random, int min, int max) {
        return random.nextInt(min, max + 1);
    }

    /** {@code NURand(A, x, y) = (((random(0, A) | random(x, y)) + C) % (y - x + 1)) + x} (clause 2.1.6). */
    public static int nuRand(SplittableRandom random, int a, int c, int x, int y) {
        return (((uniform(random, 0, a) | uniform(random, x, y)) + c) % (y - x + 1)) + x;
    }

    /** A customer id drawn with {@code NURand(1023, 1, 3000)}. */
    public static int customerId(SplittableRandom random) {
        return nuRand(random, 1023, C_ID, 1, TpccSchema.CUSTOMERS_PER_DISTRICT);
    }

    /** An item id drawn with {@code NURand(8191, 1, 100000)}. */
    public static int itemId(SplittableRandom random) {
        return nuRand(random, 8191, C_ITEM, 1, TpccSchema.ITEMS);
    }

    /** A run-time customer last name, {@code lastName(NURand(255, 0, 999))}. */
    public static String randomLastName(SplittableRandom random) {
        return lastName(nuRand(random, 255, C_LAST, 0, 999));
    }

    /** The last name built from the three digits of {@code number} in {@code [0, 999]} (clause 4.3.2.3). */
    public static String lastName(int number) {
        return SYLLABLES[number / 100] + SYLLABLES[(number / 10) % 10] + SYLLABLES[number % 10];
    }

    /** A random alphanumeric string whose length is uniform in {@code [minLength, maxLength]}. */
    public static String alphanumeric(SplittableRandom random, int minLength, int maxLength) {
        int length = uniform(random, minLength, maxLength);
        char[] chars = new char[length];
        for (int i = 0; i < length; i++) {
            chars[i] = ALPHANUMERIC[random.nextInt(ALPHANUMERIC.length)];
        }
        return new String(chars);
    }

    /** A random numeric string of exactly {@code length} digits. */
    public static String numeric(SplittableRandom random, int length) {
        char[] chars = new char[length];
        for (int i = 0; i < length; i++) {
            chars[i] = (char) ('0' + random.nextInt(10));
        }
        return new String(chars);
    }

    /**
     * An {@code i_data} or {@code s_data} value: a random string where 10% of the rows contain
     * {@code "ORIGINAL"} at a random position (clause 4.3.3.1).
     */
    public static String data(SplittableRandom random, int minLength, int maxLength) {
        String value = alphanumeric(random, minLength, maxLength);
        if (random.nextInt(10) == 0) {
            int position = random.nextInt(value.length() - 8 + 1);
            value = value.substring(0, position) + "ORIGINAL" + value.substring(position + 8);
        }
        return value;
    }
}
