# DBCP2 without `testOnBorrow`

**Question.** Is DBCP2's lower throughput (vs. HikariCP/Agroal) caused by its lock-based pool
(commons-pool2 `LinkedBlockingDeque` + `synchronized` state changes) or by its per-borrow validation?

`BasicDataSource` defaults to `testOnBorrow = true`. With no `validationQuery`, every borrow calls
`Connection.isValid()`, which PgJDBC 42.7.7 implements as an empty simple query, i.e. one extra
database round trip per transaction. HikariCP 6.0.0 skips liveness checks for connections used within
the last 500 ms (`aliveBypassWindowMs`); Agroal 1.11 has idle validation off by default.

`dbcp2-notob` is the same DBCP2 2.9.0 adapter with `setTestOnBorrow(false)`.

## Run

2026-10-15, same machine as the paper's E1, local PostgreSQL 18, Java 25 (Temurin 25.0.3), 128 threads.
HikariCP and DBCP2 were re-measured in the same session as the baseline. Smaller budget than the paper
(5×5 s measured vs. 10×10 s), so the 99.9% errors are about 2-3%.

```
cd core
mvn -P benchmark-tests -Dit.test=ThroughputVsPoolSizeBenchmarkTest \
    "-Djmh.params=pool=hikari,dbcp2,dbcp2-notob;workload=readMostly,writeHeavy" \
    -Djmh.forks=2 -Djmh.warmup=3 -Djmh.iters=5 -Djmh.secs=5 integration-test
```

The runner writes to `metrics/jmh-results/`; the outputs were moved here so the paper's E1 results stay intact.

## Files

- `ThroughputVsPoolSizeBenchmark.{csv,json}` — raw JMH results (60 points).
- `run.log` — full Maven/JMH log (5003 s, 0 failures).
- `Fit.java` — fits every curve with the repo's `UslFitter` (reproduces the paper's Table I exactly from
  the E1 CSV). Run after `mvn install`:
  `java -cp "core/target/classes;<m2>/org/apache/commons/commons-math3/3.6.1/commons-math3-3.6.1.jar" metrics/DBCP2-without-testOnBorrow/Fit.java metrics/DBCP2-without-testOnBorrow/ThroughputVsPoolSizeBenchmark.csv`
- `usl-fit.txt` — its output (± is the 95% CI).

## Results

Read-mostly throughput (tx/s):

| N | DBCP2 | DBCP2, no `testOnBorrow` | HikariCP |
|---|---|---|---|
| 1 | 4,999 | 7,050 | 6,822 |
| 4 | 15,136 | 27,134 | 19,026 |
| 8 | 24,453 | 39,773 | 31,450 |
| 24 | 39,166 | 48,202 | 50,521 |
| peak | 41,265 ± 936 | 48,304 ± 1,241 | 50,521 ± 695 |

Write-heavy peak: DBCP2 35,168, DBCP2 no `testOnBorrow` 40,191, HikariCP 41,464.

| Curve | λ | σ (×10⁻²) | κ (×10⁻⁴) | N* | R² |
|---|---|---|---|---|---|
| read-mostly DBCP2 | 5,061 | 8.48 ± 1.80 | 3.45 ± 1.12 | 51 | 0.997 |
| read-mostly DBCP2, no `testOnBorrow` | 9,726 | 15.70 ± 5.00 | 4.50 ± 3.29 | 43 | 0.987 |
| read-mostly HikariCP | 6,466 | 8.21 ± 2.70 | 5.07 ± 1.78 | 42 | 0.993 |
| write-heavy DBCP2 | 3,396 | 7.04 ± 1.61 | 1.86 ± 0.95 | 70 | 0.998 |
| write-heavy DBCP2, no `testOnBorrow` | 5,633 | 11.32 ± 1.41 | 1.92 ± 0.85 | 67 | 0.999 |
| write-heavy HikariCP | 3,796 | 5.91 ± 1.32 | 2.74 ± 0.80 | 58 | 0.998 |

## Findings

1. The per-borrow `isValid()` round trip is the main cause of DBCP2's lower throughput. Disabling it
   closes the whole gap at N=1 and about three quarters of the peak gap (read-mostly 7.0k of 9.3k,
   write-heavy 5.0k of 6.3k). The residual is about 4% on read-mostly and within noise on write-heavy,
   so the lock-based pool is at most a minor factor.
2. Without validation, DBCP2 beats HikariCP at N=2-16 (up to +43% at N=4). Unverified hypothesis: with
   16 waiters always queued, HikariCP's `requite` hands each returned connection to a parked waiter via
   its `SynchronousQueue` (idle until that thread wakes), while DBCP2's deque lets an already-running
   thread barge in. At larger N the database is the bottleneck and the difference vanishes.
3. Removing a parallel per-connection cost raised λ (as expected) but also raised σ, with
   non-overlapping CIs: the faster curve saturates earlier against the shared host/database limit, and the
   USL attributes that to contention. The coefficients describe the whole system's curve, not individual
   pool mechanisms.
4. Read-mostly N* drops from 51 to 43, matching HikariCP's 42; write-heavy N* barely moves (70 to 67 vs.
   58), so validation does not explain the write-heavy N* gap.
