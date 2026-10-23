package com.vladmihalcea.phd.pool.controller;

import com.vladmihalcea.phd.pool.usl.SizingTarget;

import java.util.Arrays;
import java.util.List;

/**
 * Creates the sizing policies by id, so the simulated and the live experiments compare the same set under the
 * same names:
 * <ul>
 *   <li>{@code static-<n>}: a fixed pool of {@code n} connections ({@code static-10} is HikariCP's default);</li>
 *   <li>{@code reactive}: FlexyPool's grow-on-timeout strategy ({@link ReactiveTimeoutPolicy});</li>
 *   <li>{@code pid}: the control-theoretic baseline ({@link PidPoolSizePolicy});</li>
 *   <li>{@code hill-climb}: the model-free throughput climb ({@link ThroughputProbePolicy});</li>
 *   <li>{@code genetic}: the search-based baseline ({@link GeneticPoolSizePolicy});</li>
 *   <li>{@code usl-optimum}: the ICASC controller, which targets {@code N*} ({@link UslAutoScalingController});</li>
 *   <li>{@code usl-plateau}: the target below {@code N*} from a throughput fit ({@link UslPlateauController});</li>
 *   <li>{@code usl-response-time}: the elastic controller that follows the demand and characterizes steady loads
 *       with a lease-time fit ({@link UslResponseTimeController}).</li>
 * </ul>
 * The plateau-seeking policies and the genetic search take a {@link SizingTarget} (by default the point of
 * diminishing returns, {@code eta:0.1}); an id can carry its own, {@code usl-response-time@eps:0.01}, so one
 * experiment can compare both targets.
 *
 * @author Vlad Mihalcea
 */
public final class Policies {

    /** Every adaptive policy, in the order the experiments report them. */
    public static final List<String> ADAPTIVE = List.of("reactive", "pid", "hill-climb", "genetic", "usl-optimum",
            "usl-plateau", "usl-response-time");

    private Policies() {
    }

    /**
     * @param id                   the policy id (see the class comment), optionally with its own target after
     *                             {@code @}, for example {@code usl-response-time@eps:0.01}
     * @param minSize              the smallest pool size
     * @param maxSize              the largest pool size
     * @param target               where below {@code N*} the plateau-seeking policies and the genetic search size
     *                             the pool, unless the id names its own
     * @param seed                 the seed of the randomized policies
     * @param reactiveTimeoutNanos the p99 acquisition time above which the reactive policy grows the pool
     */
    public static ScalingPolicy create(String id, int minSize, int maxSize, SizingTarget target, long seed,
                                       long reactiveTimeoutNanos) {
        int at = id.indexOf('@');
        String base = at < 0 ? id : id.substring(0, at);
        SizingTarget own = at < 0 ? target : SizingTarget.parse(id.substring(at + 1));
        ScalingPolicy policy = createBase(base, minSize, maxSize, own, seed, reactiveTimeoutNanos);
        return at < 0 ? policy : new Named(id, policy);
    }

    private static ScalingPolicy createBase(String id, int minSize, int maxSize, SizingTarget target, long seed,
                                            long reactiveTimeoutNanos) {
        if (id.startsWith("static-")) {
            return new StaticPolicy(id, Integer.parseInt(id.substring("static-".length())));
        }
        return switch (id) {
            case "reactive" -> new ReactiveTimeoutPolicy(minSize, maxSize, reactiveTimeoutNanos);
            case "pid" -> new PidPoolSizePolicy(PidPoolSizePolicy.Config.defaults(minSize, maxSize));
            case "hill-climb" -> new ThroughputProbePolicy(ThroughputProbePolicy.Config.defaults(minSize, maxSize));
            case "genetic" -> new GeneticPoolSizePolicy(
                    GeneticPoolSizePolicy.Config.defaults(minSize, maxSize, target, seed));
            case "usl-optimum" -> new UslAutoScalingController(minSize, maxSize);
            case "usl-plateau" -> new UslPlateauController(
                    UslPlateauController.Config.defaults(minSize, maxSize).withTarget(target));
            case "usl-response-time" -> new UslResponseTimeController(
                    UslResponseTimeController.Config.defaults(minSize, maxSize, target));
            default -> throw new IllegalArgumentException("Unknown policy: " + id + " (known: static-<n>, "
                    + String.join(", ", ADAPTIVE) + ")");
        };
    }

    /** The policy under its full id (with the target suffix), so the CSV series stay apart. */
    public record Named(String id, ScalingPolicy delegate) implements ScalingPolicy {
        @Override
        public int decide(WindowSample sample) {
            return delegate.decide(sample);
        }

        @Override
        public void reset() {
            delegate.reset();
        }

        @Override
        public String state() {
            return delegate.state();
        }
    }

    /** The size a policy's pool starts at: its own size for a static policy, else {@code minSize}. */
    public static int initialSize(String id, int minSize) {
        return id.startsWith("static-") ? Integer.parseInt(id.substring("static-".length()).split("@")[0]) : minSize;
    }

    /** The policy itself, unwrapped from its {@link Named} id. */
    public static ScalingPolicy unwrap(ScalingPolicy policy) {
        return policy instanceof Named named ? named.delegate() : policy;
    }

    /** Splits a comma-separated list of policy ids. */
    public static List<String> parse(String spec) {
        return Arrays.stream(spec.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
