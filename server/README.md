# USL server bundle: the journal-extension campaign on the Hostner servers

This bundle runs the benchmarks and experiments of the journal extension of the ICASC 2026 USL paper (the plateau
start, `Articles/USL_High_Throughput_with_Few_Connections/PLAN.md` in the PhD repository) on the two rented
Hostner servers: **s66 runs the client** (JMH and the live runs) and **s68 runs the database**, PostgreSQL 18 or
MySQL, one at a time. Both are Dell PowerEdge R240 (Xeon E-2276G, 6 cores and 12 threads, 32 GB, Ubuntu 26.04), set
up by the TOSEM `setup-server.sh` (`ROLE=app` on s66, `ROLE=db` on s68). The client needs only Java 25. The IPs and
passwords are in `%USERPROFILE%\.dto2entity\hostner.properties`, outside every repository.

**Run it only after the TOSEM campaign is over.** `run-campaign.sh` refuses to start while another `java` process
runs on s66 (a TOSEM or reservoir block), while PostgreSQL or MySQL runs on s66, and while the database that is not
under test answers on s68.

## Build (on the development machine)

```
mvn -pl core -P testbed -DskipTests package
```

builds `core/target/usl-server.tar.gz` (about 33 MB): the classes, every dependency in `lib/`, and the scripts of
this folder with Unix line endings. Every results folder records the SHA-256 of the jars it ran.

```
scp core/target/usl-server.tar.gz root@<s66-ip>:/root/
scp core/target/usl-server.tar.gz root@<s68-ip>:/root/       # for the setup and record scripts only
ssh root@<server-ip> 'cd /root && tar -xzf usl-server.tar.gz'
```

## One-time setup (outside any measurement window)

On s66 (the client):

1. `bash usl-server/setup-jdk25.sh`: Temurin 25.0.3+9 in `/opt/temurin-25`, checked against Adoptium's SHA-256 (the
   same as the reservoir bundle's; one run serves both). The Temurin 21 of the TOSEM bundles stays the default java.

On s68 (the database server):

2. `PEER_IP=<s66-ip> bash usl-server/setup-postgresql.sh`: the `uslpool` database, a `pg_hba.conf` line for s66
   (`# usl-peer`), and `max_connections = 200` (TOSEM: 100), so the server cap never shapes a curve that goes to 96
   connections. Every other setting stays the TOSEM one (`shared_buffers` 8 GB, `synchronous_commit` on).
3. `PEER_IP=<s66-ip> MYSQL_PASSWORD=<password> PROFILE=tuned bash usl-server/setup-mysql.sh`: MySQL from the
   Ubuntu archive (version held), the `uslpool` database, a `mysql` user for s66 only, port 3306 open to s66, and
   the tuned configuration (8 GB buffer pool like PostgreSQL's shared buffers, 4 GB redo log, `O_DIRECT`), with the
   durable defaults (`innodb_flush_log_at_trx_commit = 1`, `sync_binlog = 1`). MySQL does not start at boot.
4. `bash usl-server/db-switch.sh postgresql|mysql|none` runs exactly one database (or none) between blocks.

The TOSEM `setup-server.sh` already set the `performance` governor, the C1 idle-state limit, the stopped background
jobs, the firewall, and the clock; the server record of every block shows them.

## Blocks

On s66, as root:

```
DB_HOST=<s68-ip> JDBC_PASSWORD=<postgres password> MYSQL_PASSWORD=<mysql password> \
    bash usl-server/run-campaign.sh <block> [<block> ...]
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
| `sweep-mysql-micro` | K1: 3 pools x readMostly, writeHeavy (longTx is PostgreSQL-only) | MySQL curves | ~14 h |
| `sweep-mysql-sysbench` | K1: 3 pools x oltpReadWrite, oltpReadOnly | | ~14 h |
| `sweep-mysql-tpcc` | K1: HikariCP x tpcc | | ~5 h |
| `sweep-mysql-default` | K1: HikariCP x micro and sysbench, MySQL with `PROFILE=default` | how much the configuration moves the plateau | ~9 h |
| `profiles-pg`, `profiles-mysql` | L4 (JMH): 8 policies on HikariCP x increasing, steady, shrinking load x tpcc, oltpReadWrite | throughput and response time per load level, to match against the USL | ~17 h each |
| `live-pg`, `live-mysql` | L3: 10 policies on HikariCP, tpcc and oltpReadWrite, 2-minute phases, 10 repetitions | does the pool follow the concurrency, and at what cost | ~34 h each |
| `live-pools-pg`, `live-pools-mysql` | L3: DBCP2 and Agroal, 4 policies, tpcc, 10 repetitions | the same controllers on other pools | ~14 h each |
| `overhead` | E6: every controller's decision and refit, 10 forks | the control loop is negligible | ~15 min |
| `sim` | the simulator runs (ConcurrencySimulation, PlateauTracking) | deterministic reference | seconds |

Every JMH block uses `JMH_FORKS` forks (10 by default, as the TOSEM revision), with 3 warm-up and 5 measurement
iterations of 2 s, and every live block `REPS` repetitions (10). The estimates assume about 22 s per fork; the smoke
blocks print the real fork times, so check them before planning the month. The sizes of a sweep are
`1, 2, 4, ..., 64, 72, 80, 88, 96` (every second size up to 64, where all plateau starts of the ICASC curves lie,
which locates them within one connection; then every eighth). `SWEEP_POOLS=hikari` narrows a sweep to one pool.

## Order

The reservoir campaign (its own bundle) and this one share the servers; run the reservoir blocks first (about two
days), then:

```
# s68                                  # s66
bash usl-server/db-switch.sh postgresql
                                       bash usl-server/run-campaign.sh smoke-pg
                                       bash usl-server/run-campaign.sh sweep-pg-micro sweep-pg-sysbench sweep-pg-tpcc
                                       bash usl-server/run-campaign.sh profiles-pg
                                       bash usl-server/run-campaign.sh live-pg live-pools-pg
bash usl-server/db-switch.sh mysql
                                       bash usl-server/run-campaign.sh smoke-mysql
                                       bash usl-server/run-campaign.sh sweep-mysql-micro sweep-mysql-sysbench sweep-mysql-tpcc
                                       bash usl-server/run-campaign.sh profiles-mysql
                                       bash usl-server/run-campaign.sh live-mysql live-pools-mysql
PROFILE=default START=1 bash usl-server/setup-mysql.sh
                                       bash usl-server/run-campaign.sh sweep-mysql-default
PROFILE=tuned bash usl-server/setup-mysql.sh
                                       bash usl-server/run-campaign.sh overhead sim
RESTORE=1 bash usl-server/setup-postgresql.sh    # max_connections back to the TOSEM value
bash usl-server/db-switch.sh postgresql
```

That is about nine days of running. If the month runs short, drop the live blocks (L3) first: the load
profiles (L4) answer the same question at 10 forks, and L3 adds only the longer, 2-minute phases.

Run long blocks under `tmux` or `nohup`, since an SSH disconnect would stop
them. Record s68 at the start and the end of every database block (`bash usl-server/server-record.sh >
s68-record-<block>-start.txt`), since the campaign script records only the client.

## Results

Each block writes `results/<block>-<host>-<UTC time>/` on s66:

- `metrics/`: the CSVs of every test in the repository's `metrics/<Figure>/` layout: `jmh-results/` (the JMH JSON
  and CSV of every sweep, `ThroughputVsPoolSizeBenchmark-dense-<database>-<set>`, and its `-latency.csv` with the
  response-time percentiles of every fork), `ConcurrencyAdaptation/` (the live runs), `DatabaseSettings/`, and
  `LinkCheck/`;
- `console.log`, `status.csv` (one row per test with its exit code; 1 means a test failed or none ran),
  and `campaign.txt` (the block, the database, the fork and repetition counts);
- `server-record-start.txt` and `server-record-end.txt`, `pidstat.log` (CPU per process every 10 s, so a saturated
  client shows), and `bundle.sha256`.

Copy the folders into a new folder of the repository, for example `metrics/Hostner-2026-11/`, so that the committed
ICASC results are not overwritten:

```
scp -r root@<s66-ip>:/root/results ./metrics/Hostner-2026-11/
```

K9 (`SearchReplayExperimentTest`) then runs on the development machine over the measured sweeps: copy a sweep's
`jmh-results/*.csv` into `metrics/jmh-results/` and run it there; it needs no database.

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
- **E6, overhead (`OverheadBenchmark`).** The CPU time of one decision and one refit of every controller.
