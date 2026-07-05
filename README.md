# connection-pool-auto-scaling

Benchmarks and a control-theoretic prototype that apply **Neil Gunther's Universal Scalability Law
(USL)** to **database connection pool sizing**. Supports the ICASC 2026 paper *"Auto-Scaling Database
Connection Pools Using the Universal Scalability Law: A Control-Theoretic Approach"* (PhD Pillar 2).

The project instruments three production pools — **HikariCP**, **Apache DBCP2** and **Agroal** — on
PostgreSQL, extracts each pool's USL contention (σ) and coherency (κ) coefficients from its
throughput-vs-size curve, compares the USL-predicted optimum against Little's Law and M/M/c (Erlang-C),
and drives a **`UslAutoScalingController`** that re-estimates the optimum online and tracks it through
workload changes. The controller sits on FlexyPool's `PoolAdapter` actuator, so it can drive a real
FlexyPool deployment unchanged.

## Requirements

- **Java 21**, Maven. Local repository at `d:/.m2/repository`.
- **Docker** for the PostgreSQL-backed benchmarks (Testcontainers, `postgres:17-alpine`). The USL
  math, the controller, and the convergence/regime-shift experiments run **without Docker** against a
  deterministic simulator.

## Build & test

```bash
mvn clean install                 # build + fast unit tests (no Docker)
test.bat                          # fast unit tests only: USL model+fitter, queueing models,
                                  #   controller convergence/regime-shift/vs-reactive, workload
run-benchmark.bat Sanity          # smoke-test the JMH + Docker + pool plumbing
run-performance-benchmarks.bat    # E1 throughput sweep + E6 overhead  -> metrics/jmh-results
run-autoscaling-experiments.bat   # E2 fit, E3 model compare, E4 convergence, E5 regime, E7 memory
render-charts.bat                 # gnuplot every metrics/**/*.gp to SVG + PDF
```

Heavy work is gated behind the `benchmark-tests` Maven profile and named `*BenchmarkTest`,
`*ExperimentTest` or `*ReportTest` (surefire excludes them from the fast build; failsafe includes them:
`mvn -P benchmark-tests -Dit.test=<Name> integration-test`). DB-backed tests skip themselves when no
Docker daemon is reachable.

## Experiments → claims

| Exp | What it produces | Docker | Output |
|-----|------------------|--------|--------|
| E1  | Throughput vs. pool size, per pool × workload (JMH closed loop) | yes | `metrics/jmh-results/` |
| E2  | USL fit: λ/σ/κ + 95% CIs, R², N* per pool; measured-vs-fitted overlay | no¹ | `metrics/UslFit/` |
| E3  | USL vs. Little's Law vs. Erlang-C vs. HikariCP heuristic, vs. measured argmax | no¹ | `metrics/ModelComparison/` |
| E4  | Convergence of each policy on a stationary workload | no² | `metrics/Convergence/` |
| E5  | Response to a mid-run workload regime shift | no² | `metrics/RegimeShift/` |
| E6  | CPU overhead of the controller decision and the USL refit | no | `metrics/jmh-results/OverheadBenchmark.*` |
| E7  | Retained client-side memory: estimator vs. pool internals | partial³ | `metrics/MemoryFootprint/` |

¹ E2/E3 consume the measured E1 CSV when present, else a seeded synthetic ground-truth dataset (logged).
² E4/E5 run on the deterministic simulator; the identical policies also drive the live pool.
³ E7 always reports the estimator footprint; the pool-internals section needs Docker.

## Headline results (current committed data)

- **E3** — the USL optimum is far larger than the coherency-blind baselines predict (e.g. HikariCP
  readMostly: USL N\*≈142 vs. Little 33, Erlang-C 43, heuristic 17), because none of the classical
  models has a cross-connection coordination term.
- **E4** — the USL controller reaches **100% of the offline-oracle throughput** from a cold start, while
  a static pool sized at the client-thread count sits in the **retrograde region (~80%)** and the
  reactive latency-chasing policy overshoots the optimum.
- **E5** — after the optimum drops (N\* 81 → 37), the USL controller is the **only** policy that
  recovers: it **shrinks** the pool to the new optimum; the grow-only reactive policy cannot come back
  down, and the static "oracle for phase 1" is now badly over-sized.
- **E6/E7** — the control machinery is ~15 µs/window of CPU and ~1.6 KB of state, negligible beside a
  millisecond transaction and a multi-megabyte pool.

> The committed `metrics/` data for E2–E5 is at smoke/synthetic scale so the pipeline is reproducible
> offline; regenerate E1 at full scale (with Docker) for the paper's measured curves — E2/E3 then pick
> up the real CSV automatically.

## Architecture

- `api/PoolUnderTest` — resizable-pool SPI (mirrors FlexyPool's `PoolAdapter`).
- `adapter/` — `HikariPoolUnderTest`, `Dbcp2PoolUnderTest`, `AgroalPoolUnderTest` (all support **live**
  `setMaxPoolSize`), `PoolFactory`, and `FlexyPoolAdapterBridge` onto FlexyPool.
- `usl/` — `UslModel` (closed form + N\*), `UslFitter` (Levenberg–Marquardt + regression cross-check),
  `QueueModels` (Little, Erlang-C/B, HikariCP heuristic).
- `controller/` — `ScalingPolicy` SPI, `StaticPolicy`, `ReactiveTimeoutPolicy` (FlexyPool-style),
  `UslAutoScalingController` (online estimate → dead-band → saturated step → regime-flush).
- `workload/` — `Schema` (pgbench-style accounts) + `OltpWorkload` (readMostly/writeHeavy/longTx).
- test `sim/` — `SimulatedSystem` + `PolicyRunner` for Docker-free control experiments.
