package com.vladmihalcea.phd.pool.usl;

import java.util.Locale;

/**
 * Where below {@code N*} a controller sizes the pool, given a fitted {@link UslModel}:
 * <ul>
 *   <li>{@link Elasticity} ({@code eta:<value>}, the default {@code eta:0.1}): the point of diminishing returns,
 *       the smallest size whose throughput elasticity {@code d ln X / d ln N} is at most {@code eta}
 *       ({@link UslModel#poolSizeAtElasticity}). The share of the peak it reaches follows from the shape of the
 *       curve (about 97% to 99.5% at {@code eta = 0.1} on the ICASC shapes);</li>
 *   <li>{@link Tolerance} ({@code eps:<value>}): the start of the plateau for a fixed tolerance, the smallest size
 *       within {@code epsilon} of the peak ({@link UslModel#poolSizeAtFraction(double, int)}).</li>
 * </ul>
 * Both are capped at {@code N*}, so the size always lies between the start of the plateau and the optimum.
 *
 * @author Vlad Mihalcea
 */
public sealed interface SizingTarget {

    /** The default: diminishing returns at {@code eta = 0.1} (10% more connections buy at most 1% more throughput). */
    SizingTarget DEFAULT = new Elasticity(0.1);

    /** The target pool size for the model, at most {@code min(N*, maxPoolSize)} and at least 1. */
    int poolSize(UslModel model, int maxPoolSize);

    /** A compact id for CSV files and logs, the same form {@link #parse} reads: {@code eta:0.1}, {@code eps:0.01}. */
    String id();

    /** The point of diminishing returns. */
    record Elasticity(double eta) implements SizingTarget {
        public Elasticity {
            if (!(eta > 0.0 && eta < 1.0)) {
                throw new IllegalArgumentException("eta must be in (0, 1): " + eta);
            }
        }

        @Override
        public int poolSize(UslModel model, int maxPoolSize) {
            int nStar = model.optimalPoolSize();
            int cap = nStar == Integer.MAX_VALUE ? maxPoolSize : Math.min(nStar, maxPoolSize);
            return Math.max(1, Math.min(cap, model.poolSizeAtElasticity(eta, maxPoolSize)));
        }

        @Override
        public String id() {
            return "eta:" + format(eta);
        }
    }

    /** The start of the plateau for a fixed tolerance below the peak. */
    record Tolerance(double epsilon) implements SizingTarget {
        public Tolerance {
            if (!(epsilon > 0.0 && epsilon < 1.0)) {
                throw new IllegalArgumentException("epsilon must be in (0, 1): " + epsilon);
            }
        }

        @Override
        public int poolSize(UslModel model, int maxPoolSize) {
            int nStar = model.optimalPoolSize();
            int cap = nStar == Integer.MAX_VALUE ? maxPoolSize : Math.min(nStar, maxPoolSize);
            return Math.max(1, Math.min(cap, model.poolSizeAtFraction(1.0 - epsilon, maxPoolSize)));
        }

        @Override
        public String id() {
            return "eps:" + format(epsilon);
        }
    }

    /** Reads {@code eta:<value>} or {@code eps:<value>}; a bare number is a tolerance. */
    static SizingTarget parse(String spec) {
        String text = spec.trim().toLowerCase(Locale.ROOT);
        if (text.startsWith("eta:")) {
            return new Elasticity(Double.parseDouble(text.substring(4)));
        }
        if (text.startsWith("eps:")) {
            return new Tolerance(Double.parseDouble(text.substring(4)));
        }
        return new Tolerance(Double.parseDouble(text));
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%s", value);
    }
}
