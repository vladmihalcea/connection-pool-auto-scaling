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

- **Java 25**, Maven. Local repository at `d:/.m2/repository`.
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

### Two-machine testbed (client and database on separate hosts)

```bash
# On the client: point every test at the database host (also passed on to the forked JMH JVMs).
# An explicitly set URL that does not answer stops the run instead of falling back to Docker.
mvn -P benchmark-tests -Dit.test=LinkCheckExperimentTest integration-test ^
    -Dusl.db.url=jdbc:postgresql://<db-host>/uslpool ^
    -Dusl.mysql.url="jdbc:mysql://<db-host>/uslpool?useSSL=false&allowPublicKeyRetrieval=true&rewriteBatchedStatements=true"
```

`LinkCheckExperimentTest` measures the `SELECT 1` round-trip time and the ceiling throughput of trivial
two-round-trip transactions (`metrics/LinkCheck/link-check.csv`); the ceiling must sit well above the
workload's peak, so neither the network nor the client bounds the curve. Any JMH `@Param` can be overridden
with `-Djmh.param.<name>=...`, using values and inclusive ranges with an optional step, e.g. the dense
plateau-start sweep `-Djmh.param.poolSize=1..40,42..96:2`.

Besides the three ICASC workloads, `-Djmh.param.workload` accepts the sysbench-derived `oltpReadWrite` and
`oltpReadOnly` (`workload/sysbench/`: sysbench's default OLTP transaction on 4 `sbtest` tables of 100,000
rows, `-Dusl.sysbench.tables` / `-Dusl.sysbench.tableSize`; sysbench itself keeps one connection per thread,
so it cannot drive a pool). The pack's `dense-sweep.bat <db-host> [postgresql|mysql] [micro|sysbench]`
selects the database and the workload set.

`ThroughputVsPoolSizeBenchmark` takes `-Djmh.param.database=postgresql|mysql` (PostgreSQL by default) and,
besides the JMH throughput, writes the response-time percentiles of every measured transaction to
`<results>-latency.csv` (one row per fork); `-Dusl.results.name=<name>` renames a run's result files.

A client machine needs only Java 25: `mvn -pl core -P testbed package` builds `core/target/usl-testbed.zip`
(the classes, every dependency in `lib/`, and the scripts of `core/src/testbed/`). Unzip it on the client and
run `link-check.bat <db-host>`, `dense-sweep.bat <db-host>`, or `run.bat <db-host> <TestName>`; extra settings
go in `JAVA_OPTS`, and results land in the pack's `metrics` folder (see its `README.txt`).

### Journal extension: response-time and search-based sizing, and the server campaign

The journal extension sizes the pool at the start of the plateau, the fewest connections within ε of the peak
(99% of it by default), and adds:

- `usl/LeaseTimeFitter` and `UslModel.responseTimeAt` / `concurrencyForResponseTime` / `throughputForResponseTime`:
  the USL in its response-time form, from Little's Law (Schwartz, *Practical Scalability Analysis with the
  Universal Scalability Law*, 2015, Eq. 6, 9, and 10). The lease time is quadratic in the busy connections,
  `W(c) = W0 (1 + σ(c − 1) + κc(c − 1))`, so σ, κ, and λ = 1/W0 follow from linear least squares, with no start
  values and no X(1).
- `usl/SizingTarget`: where below N\* a controller sizes the pool, by default the point of diminishing returns
  (`eta:0.1`, `UslModel.poolSizeAtElasticity`: the smallest N whose throughput elasticity d ln X / d ln N is at
  most 0.1), or a fixed tolerance below the peak (`eps:0.01`). Both lie between the start of the plateau and N\*.
- `controller/UslResponseTimeController` (`usl-response-time`): elastic; the pool follows the offered load (busy
  plus waiting requests), and a steady load that the pool limits is characterized by a sweep of pool sizes that
  finds Nmax, a lease-time USL fit, and a target below Nmax, with one model per load level (reused when a known
  model predicts the new level's lease times).
- `controller/GeneticPoolSizePolicy` (`genetic`), the search-based baseline of Newsom (2022) run online, and
  `controller/PidPoolSizePolicy` (`pid`), the control-theoretic baseline; `controller/Policies` creates every
  policy by id.
- `flexypool/PolicyConnectionAcquisitionStrategy`: one FlexyPool strategy for any `ScalingPolicy`, which also
  times acquisitions, so every policy runs live through the same monitoring and actuation path.
- L4, `LoadProfileBenchmark` (JMH, 10 forks): every policy under increasing, steady, and shrinking load, with one
  row per measurement iteration (load, pool size, throughput, response-time percentiles, Little's Law's M/X) to
  match against the USL.
- L3, `ConcurrencyAdaptationExperimentTest`: every policy live while the client concurrency changes
  (8, 32, 128, 64, 16 clients) on the TPC-C-derived and sysbench-derived loads, 10 repetitions with 95% intervals;
  `ConcurrencySimulationExperimentTest` is its simulator counterpart.
- K9, `SearchReplayExperimentTest`: the genetic search and hill climbing against the USL controllers, replayed on
  the measured curves (how many pool sizes each measures, and where it ends).
- The `tpcc` workload in the dense JMH sweep (a freshly loaded schema per fork), and
  `DatabaseSettingsReportTest`, which records the database server's settings.

`mvn -pl core -P testbed -DskipTests package` also builds `core/target/usl-server.tar.gz`, the Linux bundle of
the campaign on two Hostner servers (client and database): `server/README.md` is the runbook, with every block
at 10 JMH forks or 10 repetitions (about nine days). The database passwords come from `JDBC_PASSWORD` and `MYSQL_PASSWORD`, so they
never appear on a command line.

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
| K3/K5 | Plateau start (N_ε) vs. peak (N\*) targeting across a regime shift | no² | `metrics/PlateauTracking/` |
| L1  | Plateau strategy live via FlexyPool: {HikariCP, DBCP2, Agroal} × {PostgreSQL, MySQL} | yes⁴ | `metrics/FlexyPoolPlateau/` |
| L2  | TPC-C-derived load: static pool-size sweep + USL fit vs. the live plateau strategy | yes⁴ | `metrics/TpccPlateau/` |
| K1  | Dense sweep, 37 sizes × 3 pools × micro, sysbench-derived, TPC-C-derived × PostgreSQL, MySQL | yes⁴ | `metrics/jmh-results/ThroughputVsPoolSizeBenchmark-dense-*` |
| L4  | Every policy under increasing, steady, shrinking load (JMH, 10 forks), per-iteration rows | yes⁴ | `metrics/jmh-results/LoadProfileBenchmark*` |
| L3  | Every policy live under changing client concurrency (TPC-C-, sysbench-derived), 10 reps | yes⁴ | `metrics/ConcurrencyAdaptation/` |
| L3s | The same on the simulator | no² | `metrics/ConcurrencySimulation/` |
| K9  | Genetic search and hill climbing vs. the USL controllers, replayed on measured curves | no¹ | `metrics/SearchReplay/` |

¹ E2/E3 consume the measured E1 CSV when present, else a seeded synthetic ground-truth dataset (logged).
² E4/E5/K3/K5 run on the deterministic simulator; the identical policies also drive the live pool.
³ E7 always reports the estimator footprint; the pool-internals section needs Docker.
⁴ A local `uslpool` database (PostgreSQL `postgres`/`admin`, MySQL `mysql`/`admin`) is used when it
answers, otherwise a Testcontainer; run them with `run-live-experiments.bat`.

## Headline results (current committed data)

- **E3** — scored against the measured plateau start (the smallest pool size within 99% of peak
  throughput), Little's Law and Erlang-C under-provision in all nine pool/workload cases (e.g. HikariCP
  readMostly: plateau start 24, USL N\*=45, Little 6, Erlang-C 11, heuristic 25), because neither
  queueing model has a cross-connection coordination term. Mean absolute error: USL 14, heuristic 15,
  Erlang-C 25, Little 30; worst case: USL 22 vs. heuristic 39.
- **E4** — the USL controller reaches **100% of the offline-oracle throughput** from a cold start, while
  a static pool sized at the client-thread count sits in the **retrograde region (~80%)** and the
  reactive latency-chasing policy overshoots the optimum.
- **E5** — after the optimum drops (N\* 81 → 37), the USL controller is the **only** policy that
  recovers: it **shrinks** the pool to the new optimum; the grow-only reactive policy cannot come back
  down, and the static "oracle for phase 1" is now badly over-sized.
- **K3/K5** — the plateau controller (`usl-plateau`, ε = 5%) holds the pool at the start of the plateau
  N_ε instead of N\*: 56 connections at 94.8% of the peak vs. 80 at 99.7% for the N\* controller, and
  after the shift 27 at 95.5% vs. 37 at 99.9%, matching the static N_ε oracle in both phases.
- **E6/E7** — the control machinery is ~15 µs/window of CPU and ~1.6 KB of state, negligible beside a
  millisecond transaction and a multi-megabyte pool.

> E2/E3 are computed from the committed measured E1 curves
> (`metrics/jmh-results/ThroughputVsPoolSizeBenchmark.csv`); E4/E5 run on the deterministic simulator,
> so they are reproducible offline.

## Architecture

- `api/PoolUnderTest` — resizable-pool SPI (mirrors FlexyPool's `PoolAdapter`).
- `adapter/` — `HikariPoolUnderTest`, `Dbcp2PoolUnderTest`, `AgroalPoolUnderTest` (all support **live**
  `setMaxPoolSize`), `PoolFactory`, and `FlexyPoolAdapterBridge` onto FlexyPool.
- `usl/` — `UslModel` (closed form, N\* and the plateau start N_ε), `UslFitter` (Levenberg–Marquardt + regression cross-check),
  `QueueModels` (Little, Erlang-C/B, HikariCP heuristic).
- `controller/` — `ScalingPolicy` SPI, `StaticPolicy`, `ReactiveTimeoutPolicy` (FlexyPool-style),
  `UslAutoScalingController` (online estimate → dead-band → saturated step → regime-flush), and
  `UslPlateauController` (same loop targeting N_ε, with a saturation filter, a Little's Law floor and
  periodic probes at N\*).
- `flexypool/` — FlexyPool strategies: `UslAutoScalingConnectionAcquisitionStrategy` (N\*),
  `UslPlateauConnectionAcquisitionStrategy` (N_ε, grows and shrinks the live pool); `WindowedPoolMetrics`,
  a FlexyPool `Metrics` decorator that turns FlexyPool's concurrency histograms into per-window
  busy-connection and wait-queue means; `SurplusConnectionRetirer`, which closes the connections HikariCP
  and DBCP2 keep above a lowered cap; and `AgroalPoolAdapter`, the FlexyPool adapter Agroal lacks.
- `workload/` — `Schema` (pgbench-style accounts) + `OltpWorkload` (readMostly/writeHeavy/longTx; longTx
  is PostgreSQL-only), and `workload/tpcc/` — a TPC-C-derived schema, loader and the five transactions in
  the standard 45/43/4/4/4 mix (closed loop without think times, like BenchBase; not an audited TPC-C).
- test `sim/` — `SimulatedSystem` + `PolicyRunner` for Docker-free control experiments.
