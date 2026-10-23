USL testbed pack (connection-pool-auto-scaling)
================================================

Runs the benchmarks and experiments of the harness on a client machine against the PostgreSQL and
MySQL servers of a separate database host. The client needs only Java 25 (on the PATH, or JAVA_HOME).

Database host (once)
--------------------
- PostgreSQL: a "uslpool" database (user postgres, password admin), max_connections above 100, and a
  pg_hba.conf line that admits the client's address.
- MySQL: a "uslpool" database and a mysql/admin user allowed from the client's address.
- Windows Firewall: ports 5432 and 3306 open for the local network only.
Other credentials: set JAVA_OPTS=-Dusl.db.username=... -Dusl.db.password=... (and usl.mysql.*).

Client
------
1. link-check.bat <db-host>
   The SELECT 1 round-trip time and the ceiling throughput of trivial transactions. The ceiling must be
   well above the peak of the workload (about 40,000 tx/s for PostgreSQL read-mostly), or the network
   or the client bounds the curve.
2. dense-sweep.bat <db-host> [postgresql|mysql] [micro|sysbench]
   The dense pool-size sweep, PostgreSQL and the three ICASC micro-workloads by default (about 7 to 10
   hours); "sysbench" runs the sysbench-derived oltpReadWrite and oltpReadOnly instead (about two thirds
   of that; the first run loads 4 sbtest tables of 100,000 rows, which later runs reuse). Besides the
   throughput, it records response-time percentiles (mean, p50, p90, p99, p99.9, max) of every
   transaction, one row per JMH fork. Keep the machine on AC power, in the best-performance power mode,
   with sleep disabled.
3. Anything else: run.bat <db-host> <TestName>, for example run.bat 192.168.1.20 SanityBenchmarkTest.

A database host that does not answer stops the run; it never falls back to a local Docker database.

Results
-------
Everything is written under the metrics folder next to these scripts: the JMH JSON, CSV, and latency
CSV in metrics\jmh-results (named per database for the dense sweep), and the link check in
metrics\LinkCheck. Copy them back into a new folder of the repository's metrics directory, so the
committed ICASC results are not overwritten.
