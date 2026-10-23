# USL server bundle: the journal-extension campaign on the Hostner servers

This bundle runs the benchmarks and experiments of the journal extension of the ICASC 2026 USL paper (the plateau
start, `Articles/USL_High_Throughput_with_Few_Connections/PLAN.md` in the PhD repository) on the two rented
Hostner servers: **s68 runs the client** (JMH and the live runs) and **s66 runs the database**, PostgreSQL 18 or
MySQL, one at a time. Both are Dell PowerEdge R240 (Xeon E-2276G, 6 cores and 12 threads, 32 GB, Ubuntu 26.04), set
up by the TOSEM `setup-server.sh`; s66 has been the database host since the TOSEM role swap (PostgreSQL 18.6 with the
campaign settings) and s68 the application host. The database runs on s66 because its disk flushes a WAL record of
8 kB in 51 us against 147 us on s68 (`pg_test_fsync`, two drives behind the same PERC H330), which made every write
commit 0.1 ms slower with s68 as the database; the commit-bound workloads (writeHeavy, TPC-C, MySQL with durable
commits) would otherwise measure the slower disk. The client needs only Java 25. The IPs and passwords are in
`%USERPROFILE%\.dto2entity\hostner.properties`, outside every repository.

**Run it only after the TOSEM and reservoir campaigns are over.** `run-campaign.sh` refuses to start while another
`java` process runs on s68 (a TOSEM or reservoir block), while PostgreSQL or MySQL runs on s68, and while the
database that is not under test answers on s66. The reservoir blocks also run on s66, so check that no `java`
process runs there either before the first USL block.

## Build (on the development machine)

```
mvn -pl core -P testbed -DskipTests package
```

builds `core/target/usl-server.tar.gz` (about 33 MB): the classes, every dependency in `lib/`, and the scripts of
this folder with Unix line endings. Every results folder records the SHA-256 of the jars it ran.

```
scp core/target/usl-server.tar.gz root@<s68-ip>:/root/
scp core/target/usl-server.tar.gz root@<s66-ip>:/root/       # for the setup and record scripts only
ssh root@<server-ip> 'cd /root && tar -xzf usl-server.tar.gz'
```

## One-time setup (outside any measurement window)

On s68 (the client):

1. `bash usl-server/setup-jdk25.sh`: Temurin 25.0.3+9 in `/opt/temurin-25`, checked against Adoptium's SHA-256 (the
   same as the reservoir bundle's; one run serves both). The Temurin 21 of the TOSEM bundles stays the default java.

On s66 (the database server):

2. `PEER_IP=<s68-ip> bash usl-server/setup-postgresql.sh`: the `uslpool` database, a `pg_hba.conf` line for s68
   (`# usl-peer`), and `max_connections = 200` (TOSEM: 100), so the server cap never shapes a curve that goes to 96
   connections, and `log_autovacuum_min_duration = 0`, so the server log places every autovacuum against the runs.
   Every other setting stays the TOSEM one (`shared_buffers` 8 GB, `synchronous_commit` on). It ends with
   `vacuumdb --all --freeze` and the checks of the xmin horizon (prepared transactions, replication slots), so run it
   again before each phase.
3. `PEER_IP=<s68-ip> MYSQL_PASSWORD=<password> PROFILE=tuned bash usl-server/setup-mysql.sh`: MySQL from the
   Ubuntu archive (version held), the `uslpool` database, a `mysql` user for s68 only, port 3306 open to s68, and
   the tuned configuration (8 GB buffer pool like PostgreSQL's shared buffers, 4 GB redo log, `O_DIRECT`), with the
   durable defaults (`innodb_flush_log_at_trx_commit = 1`, `sync_binlog = 1`). MySQL does not start at boot.
4. `bash usl-server/db-switch.sh postgresql|mysql|none` runs exactly one database (or none) between blocks.
5. `bash usl-server/start-dbhost-record.sh campaign` before the first block: `dbhost-record.sh` as the service
   `usl-dbhost`, which writes the CPU (all CPUs and the busiest one, iowait, the run queue) and the data disk's use
   of s66 every 10 s to `/root/usl-records/dbhost-campaign.csv` until `systemctl stop usl-dbhost`. The client's CPU is
   in every block's `pidstat.log`; together they show which host bounds a curve (the pilot's client used up to
   1,032% of its 1,200% on readMostly). Only the database under test runs on s66, so its host-wide CPU is the
   database's, in one line per sample (pidstat would write one per PostgreSQL backend).

The TOSEM `setup-server.sh` already set the `performance` governor, the C1 idle-state limit, the stopped background
jobs, the firewall, and the clock; the server record of every block shows them.

## Blocks

On s68, as root:

```
DB_HOST=<s66-ip> JDBC_PASSWORD=<postgres password> MYSQL_PASSWORD=<mysql password> \
    bash usl-server/run-campaign.sh <block> [<block> ...]
```

For long blocks, `start-campaign.sh` runs the same as the transient service `usl-campaign`, so an SSH disconnect
does not stop it; it takes the postgres password on the first line of stdin and the mysql password on the second, into
a root-only environment file, and writes the results to `/root/usl/results` (apart from the reservoir campaign's):

```
bash usl-server/start-campaign.sh <name> "<blocks>" <s66-ip> < password-lines     # log: /root/usl-campaign-<name>.log
```

The passwords are read by the harness from the environment, so they never appear on a command line or in
`pidstat.log`. A configured database that does not answer stops the block; it never falls back to Docker. Every
block that uses a database first records the database's settings (`DatabaseSettingsReportTest`) and checks the link
(`LinkCheckExperimentTest`: the `SELECT 1` round-trip time and the ceiling throughput, which must lie well above the
workload's peak).

| Block | Tests | Answers | Duration (estimate) |
|---|---|---|---|
| `smoke-pg`, `smoke-mysql` | every test at a tiny size (JMH sweep, load profiles, live run, overhead) | acceptance gate | ~12 min |
| `link-pg`, `link-mysql` | settings record and link check only | the network does not bound the curve | ~1 min |
| `sweep-pg-micro` | K1: 3 pools x readMostly, writeHeavy, longTx x 37 sizes | the measured plateau start (H1), K2, K9 | ~20 h |
| `sweep-pg-sysbench` | K1: 3 pools x oltpReadWrite, oltpReadOnly | the same on the sysbench-derived mix | ~14 h |
| `sweep-pg-tpcc` | K1: HikariCP x tpcc, a freshly loaded schema per fork | the same on the TPC-C-derived mix | ~5 h |
| `sweep-mysql-micro` | K1: 3 pools x readMostly, writeHeavy (longTx is PostgreSQL-only), 41 sizes up to 128 | MySQL curves | ~15 h |
| `sweep-mysql-sysbench` | K1: 3 pools x oltpReadWrite, oltpReadOnly, up to 128 | | ~15 h |
| `sweep-mysql-tpcc` | K1: HikariCP x tpcc, up to 128 | | ~5.5 h |
| `sweep-mysql-default` | K1: HikariCP x micro and sysbench, MySQL with `PROFILE=default`, up to 128 | how much the configuration moves the plateau | ~10 h |
| `fixed-pg`, `fixed-mysql` | K3 static: HikariCP x micro + oltpReadWrite, 128 clients at every size (4, 8-64 in steps of 4, 72-96 in steps of 8; on MySQL to 128) | the closed-loop response-time bound `R(N_eps) / R(N*) <= 1 / (1 - eps)` and whether the throughput depends on the queue length | ~5 h |
| `profiles-pg`, `profiles-mysql` | L4 (JMH): 8 policies on HikariCP x increasing, steady, shrinking load x tpcc, oltpReadWrite | throughput and response time per load level, to match against the USL | ~17 h each |
| `live-pg`, `live-mysql` | L3: 10 policies on HikariCP, tpcc and oltpReadWrite, 2-minute phases, 10 repetitions | does the pool follow the concurrency, and at what cost | ~34 h each |
| `live-pools-pg`, `live-pools-mysql` | L3: DBCP2 and Agroal, 4 policies, tpcc, 10 repetitions | the same controllers on other pools | ~14 h each |
| `drift-pg`, `drift-mysql` | RQ4 drift: L3 on tpcc at a steady 64 clients for 10 minutes, `usl-plateau` and `usl-response-time` each with and without `@drift:rescale`, 10 repetitions | does rescaling the history instead of flushing it hold the plateau start while the load drifts | ~7.5 h each |
| `open-pg`, `open-mysql` | K7 and H5: Poisson arrivals at 30% to 95% of the calibrated peak, pools capped at N*, N_eta, and N_0.05, readMostly and oltpReadWrite, 10 repetitions | the response-time cost of the plateau start under open arrivals (vs. Erlang C), and whether demand-limited windows lie on the lease-time USL | ~7.5 h each |
| `shared-pg`, `shared-mysql` | K4: up to 6 instances join one per minute and share `max_connections`, 5 policies, readMostly and oltpReadWrite, 10 repetitions | what the saved connections are worth when instances share a server (H3) | ~10 h each |
| `overhead` | E6: every controller's decision and refit, 10 forks | the control loop is negligible | ~15 min |
| `sim` | the simulator runs (ConcurrencySimulation, PlateauTracking, DriftSimulation) | deterministic reference | seconds |
| `pilot-pg` | the pilot before the campaign, 2 forks and 1 repetition, not reported: HikariCP sweeps of tpcc and the micro-workloads, live runs of `static-10`, `static-96`, `usl-plateau`, `usl-response-time` on tpcc and oltpReadWrite, and `shared` | the curve shapes and H1a scoring on the server, TPC-C's window noise, the warm-up, whether `max_connections` binds, and the block durations | ~4.5 h |
| `pilot-mysql` | the HikariCP sweep of readMostly and writeHeavy, 2 forks | MySQL end to end | ~1 h |

Every JMH block uses `JMH_FORKS` forks (10 by default, as the TOSEM revision), with 3 warm-up and 5 measurement
iterations of 2 s, and every live block `REPS` repetitions (10). The estimates assume about 22 s per fork; the smoke
blocks print the real fork times, so check them before planning the month. The sizes of a sweep are
`1, 2, 4, ..., 64, 72, 80, 88, 96` (every second size up to 64, where all plateau starts of the ICASC curves lie,
which locates them within one connection; then every eighth). `SWEEP_POOLS=hikari` narrows a sweep to one pool.
The MySQL sweeps go on to 128 (`104, 112, 120, 128`), since the pilot's MySQL curves still rose at 96; they run 144
JMH threads, so the queue stays 16 clients at every size (`min(N + 16, threads)` clients; up to 112 connections the
same as with the default 128 threads). The MySQL `fixed` sweep goes to 128 as well, with no queue at 128.

## Order

The reservoir campaign (its own bundle) and this one share the servers; run the reservoir blocks first. They end on
20 Nov with the V1 rerun (about 15:00 UTC). The order below (revised 20 Nov) runs first the blocks that the local
validation nights of 17–20 Nov already exercised, and copies and scores the sweeps before the controller blocks start,
so that a problem in the fits shows while there is still time to fix it.

Before it, a pilot of about 6 hours (20 Nov evening): `link-mysql smoke-mysql`, then `pilot-mysql` (MySQL running
on s66), then `db-switch.sh postgresql` and `link-pg smoke-pg`, then `pilot-pg`. Its results are not reported; they
decide whether the campaign starts as planned, and what to trim, before 11 days of server time are spent.

```
# s66                                  # s68
# Before the first block: no stuck transactions or replication slots (pg_prepared_xacts, pg_replication_slots), the
# databases frozen (vacuumdb --all --freeze, so no anti-wraparound vacuum starts during a run), and the settings
# recorded (server-record.sh). The laptop's server held its xmin horizon for a month (found 18 Nov).
bash usl-server/db-switch.sh postgresql
bash usl-server/start-dbhost-record.sh campaign     # s66's CPU and disk every 10 s, for the whole campaign
                                       bash usl-server/run-campaign.sh link-pg smoke-pg
# Phase A, the PostgreSQL sweeps (K1, K2, K3), about 44 h:
                                       bash usl-server/run-campaign.sh sweep-pg-micro sweep-pg-sysbench sweep-pg-tpcc fixed-pg
# Copy the results and score them (PlateauStartReportTest: H1a, H1b, the ratio bound). If the bundle changed since
# the start (fixes from the last local night), deploy it here, between blocks.
# Phase B, the PostgreSQL controllers, about 90-97 h (profiles-pg took 1.4 times its estimate locally):
                                       bash usl-server/run-campaign.sh sim overhead drift-pg open-pg shared-pg
                                       bash usl-server/run-campaign.sh profiles-pg live-pools-pg live-pg
bash usl-server/db-switch.sh mysql
# Phase C, MySQL, about 137 h:
                                       bash usl-server/run-campaign.sh smoke-mysql
                                       bash usl-server/run-campaign.sh sweep-mysql-micro sweep-mysql-sysbench sweep-mysql-tpcc fixed-mysql
PROFILE=default START=1 bash usl-server/setup-mysql.sh
                                       bash usl-server/run-campaign.sh sweep-mysql-default
PROFILE=tuned bash usl-server/setup-mysql.sh
                                       bash usl-server/run-campaign.sh drift-mysql open-mysql shared-mysql
                                       bash usl-server/run-campaign.sh profiles-mysql live-pools-mysql live-mysql
RESTORE=1 bash usl-server/setup-postgresql.sh    # max_connections back to the TOSEM value
bash usl-server/db-switch.sh postgresql
systemctl stop usl-dbhost
```

That is about eleven and a half to twelve days of running (nine for the original blocks, two and a half for `fixed`,
`drift`, `open`, and `shared` on both databases, plus the longer load profiles, and about 4 h for the MySQL sweeps
to 128), so the start on 21 Nov (after the pilot) ends on about 3 Dec, two days before the servers go back on 5 Dec. Every block needs both servers, so the blocks run one after
the other. The live blocks (L3) come last in each phase because they are the first to drop if the month runs short:
the load profiles (L4) answer the same question at 10 forks, and L3 adds only the longer, 2-minute phases. Drop
`live-mysql` first, then `live-pools-mysql`; then run the MySQL `open` and `shared` blocks with `REPS=5`. `shared-*` needs the server's `max_connections` to bind: `setup-postgresql.sh` sets
200, so six instances at an `N*` of 45 or more ask for more connections than the server admits.

Run long blocks under `tmux` or `nohup`, since an SSH disconnect would stop
them. Record s66 at the start and the end of every database block (`bash usl-server/server-record.sh >
s66-record-<block>-start.txt`), since the campaign script records only the client.

## Results

Each block writes `results/<block>-<host>-<UTC time>/` on s68:

- `metrics/`: the CSVs of every test in the repository's `metrics/<Figure>/` layout: `jmh-results/` (the JMH JSON
  and CSV of every sweep, `ThroughputVsPoolSizeBenchmark-dense-<database>-<set>`, and its `-latency.csv` with the
  response-time percentiles of every fork), `ConcurrencyAdaptation/` (the live runs), `DatabaseSettings/`, and
  `LinkCheck/`;
- `console.log`, `status.csv` (one row per test with its exit code; 1 means a test failed or none ran),
  and `campaign.txt` (the block, the database, the fork and repetition counts);
- `server-record-start.txt` and `server-record-end.txt`, `pidstat.log` (CPU per process every 10 s, so a saturated
  client shows), and `bundle.sha256`.

On s66, `/root/usl-records/` holds the database server's records and `dbhost-campaign.csv` (its CPU and disk every
10 s, in UTC); slice the CSV per block with the times in the block's `status.csv`, and copy it with the results.

Copy the folders into a new folder of the repository, for example `metrics/Hostner-2026-11/`, so that the committed
ICASC results are not overwritten:

```
scp -r root@<s68-ip>:/root/results ./metrics/Hostner-2026-11/
```

K9 (`SearchReplayExperimentTest`) then runs on the development machine over the measured sweeps: copy a sweep's
`jmh-results/*.csv` into `metrics/jmh-results/` and run it there; it needs no database.

K1, K2, and the response-time bound (`PlateauStartReportTest`) also run on the development machine, over the JMH JSON
of the sweeps (`-Dusl.analysis.sweeps=<a.json,b.json>`, by default every `ThroughputVsPoolSizeBenchmark*.json` in
`metrics/jmh-results/`) and their `-latency.csv`: per curve and tolerance (1%, 2%, 5%, 10%), the measured plateau
start against the fitted one of both estimators (H1, within 3 connections), the share of the measured peak the
fitted size delivers, 95% bootstrap intervals over the forks for the plateau start and the optimum (H4), the shape
number and the closed-form `N_eps / N^`, Little's Law for the measured mean response time, and, for the `fixed-*`
sweeps, `R(N_eps) / R(N*)` against `1 / (1 - eps)` (`metrics/PlateauStart/`).

## The experiments

- **K1, the dense sweep (`ThroughputVsPoolSizeBenchmark`).** A closed loop of `poolSize + 16` clients (so the pool
  is always saturated with a queue of 16) runs the workload against each pool held at each size, after holding all
  its connections once so the pool is full. The score is transactions per second, and each fork adds the
  response-time percentiles (mean, p50, p90, p99, p99.9, max) of every transaction, from the request for a
  connection to its return. The curves give the measured plateau start for 1%, 2%, 5%, and 10% below the peak,
  which the USL fit and the lease-time fit predict (H1), and the static oracles the live policies are compared with.
- **The sizing target.** Every plateau-seeking policy sizes the pool below `Nmax`, between the start of the plateau
  and `Nmax`: by default at the point of diminishing returns (`eta:0.1`), the smallest size whose throughput
  elasticity `d ln X / d ln N` is at most 0.1, so 10% more connections would buy at most 1% more throughput. The
  share of the peak this reaches depends on the shape of the curve (about 97% to 99.5% on the ICASC shapes), unlike
  a fixed tolerance; `@eps:0.01` after a policy id runs it at a fixed 99% instead.
- **`usl-response-time`, the elastic controller.** The pool follows the offered load (busy connections plus waiting
  requests), growing on demand and shrinking when connections go unused. When a load holds steady and the pool
  limits the throughput, the controller characterizes it: it sweeps pool sizes from a quarter of the load upward
  (x1.25 every 2 s) until the throughput has fallen twice past the peak (the measured `Nmax`), or downward when the
  peak lies below the start, fits the USL in its lease-time form (`W(c) = W0 (1 + sigma (c - 1) + kappa c (c - 1))`,
  Little's Law, Schwartz 2015), and holds the target below `Nmax`. Each load level keeps its own model; a new level
  first checks whether a known model predicts its lease times and reuses it, and a lease time 25% off the model
  means the workload changed and the load is characterized again.
- **L4, the load profiles (`LoadProfileBenchmark`, JMH).** Each policy sizes the pool from a cold start while the
  number of clients is 8, 16, 32, 64, 128 (increasing), 64 throughout (steady), or 128 down to 8 (shrinking), 4
  iterations of 5 s per level, 10 forks. JMH reports every iteration's throughput, and
  `jmh-results/LoadProfileBenchmark-<db>-iterations.csv` adds per iteration: the clients `M`, the mean pool size and
  open connections, the busy connections and queue, the acquisition and lease times, the response-time percentiles,
  Little's Law's `M / X`, and the policy's state and estimates. `static-96` under increasing load traces the USL in
  its load form `X(M)`; under steady load, a policy's settled size `N` predicts `X(min(M, N))` and the lease time
  `W(N)`.
- **L3, the live concurrency runs (`ConcurrencyAdaptationExperimentTest`).** The same policies plus hill climbing
  and the reactive growth, outside JMH, with 2-minute phases of 8, 32, 128, 64, and 16 clients, shuffled per
  repetition, 10 repetitions. Outputs per window (pool size, throughput, busy connections, queue, acquisition,
  lease, and response time, the policy's estimates and state) and per phase (steady pool size, connection-seconds
  held, throughput, response-time percentiles), and a summary with 95% Student-t intervals over the repetitions.
- **K3 static, the fixed-clients sweep (`fixed-*`).** The K1 sweep with the same 128 clients at every size
  (`-Dusl.sweep.clients=128`), the closed loop of the article's response-time claim: by Little's Law the mean
  response time is `128 / X(N)`, so `R(N_eps) / R(N*) = X(N*) / X(N_eps) <= 1 / (1 - eps)`, which
  `PlateauStartReportTest` checks next to the p99 ratio (which the bound does not cover).
- **Drift (`drift-*`, `DriftSimulationExperimentTest`).** The draft's controller answers a drift of the throughput with
  a flush and a new probe ladder, which never converged on the TPC-C-derived load. With `@drift:rescale`, a drift
  rescales the history instead: the target depends on `sigma` and `kappa`, not on `lambda`, so a level drift leaves it
  where it was, and only drifts that repeat within 30 windows (a change of shape) still flush. The simulator compares
  both on a smooth 70% decay, two 30% drops, and a change of shape; `drift-*` does it live, on TPC-C without reloads.
- **K7 and H5, open arrivals (`OpenArrivalExperimentTest`).** A short calibration sweep fits the curve; then Poisson
  arrivals at 30% to 95% of the calibrated peak run against static pools at `N*`, `N_eta`, and `N_0.05`, one virtual
  thread per request, with the response time from the scheduled arrival (no coordinated omission). Per window: the
  mean busy connections and waiting requests (lease and wait time over the window), the lease time, and the response
  percentiles; per run: the mean wait against Erlang C and the response-time percentiles; for H5, the lease time of
  every window without a queue against the calibrated lease-time USL at its mean busy count.
- **K4, shared database (`SharedDatabaseExperimentTest`).** Up to six instances (each with its own FlexyPool-wrapped
  HikariCP pool, policy, and 64 clients) join one per minute; per phase the total configured, open, and busy
  connections against `max_connections`, the aggregate throughput against the calibrated curve at the total busy
  connections (does the database see one curve in the total?), the failed acquisitions, and the per-instance
  throughput range.
- **E6, overhead (`OverheadBenchmark`).** The CPU time of one decision and one refit of every controller.
