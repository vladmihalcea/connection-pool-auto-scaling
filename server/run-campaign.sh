#!/usr/bin/env bash
# Runs blocks of the USL journal-extension campaign on the application server (s68) against the database server
# (s66), one results folder per block:
#
#   DB_HOST=<s66-ip> JDBC_PASSWORD=... MYSQL_PASSWORD=... bash run-campaign.sh <block> [<block> ...]
#
# Blocks (README.md has the order and the durations; <db> is pg or mysql):
#   smoke-<db>           every test of the campaign at a tiny size (about 10 minutes); the acceptance gate
#   link-<db>            the round-trip time and the ceiling throughput of the link (about 1 minute)
#   sweep-<db>-micro     K1 dense sweep, 3 pools x the ICASC micro-workloads (readMostly, writeHeavy, and longTx on pg)
#   sweep-<db>-sysbench  K1 dense sweep, 3 pools x oltpReadWrite, oltpReadOnly
#   sweep-<db>-tpcc      K1 dense sweep, HikariCP x tpcc, a freshly loaded schema per fork
#   sweep-mysql-default  K1 dense sweep, HikariCP x micro + sysbench, for MySQL in its default configuration
#   fixed-<db>           K3 static: HikariCP x micro + oltpReadWrite with the same 128 clients at every size, the
#                        closed loop of the article's response-time bound R(N_eps) / R(N*) <= 1 / (1 - eps)
#   profiles-<db>        L4 (JMH), every policy on HikariCP under increasing, steady, and shrinking load (tpcc,
#                        oltpReadWrite), the per-iteration rows to match against the USL
#   live-<db>            L3, every policy on HikariCP while the client concurrency changes (tpcc, oltpReadWrite)
#   live-pools-<db>      L3 on DBCP2 and Agroal, the static default and the three plateau-seeking policies (tpcc)
#   drift-<db>           RQ4 drift: L3 on tpcc at a steady 64 clients for 10 minutes (the load drifts as its hot rows
#                        age), the USL controllers with and without @drift:rescale
#   open-<db>            K7 and H5: Poisson arrivals at 30% to 95% of the calibrated peak against pools capped at
#                        N*, N_eta, and N_0.05 (readMostly, oltpReadWrite)
#   shared-<db>          K4: up to 6 application instances join one per minute and share the server's
#                        max_connections, under static N*, N_eta, and default sizing and the adaptive controllers
#   overhead             E6, the CPU cost of every controller's decision and refit (no database)
#   sim                  the simulator runs of the controllers (no database, deterministic, seconds)
#   pilot-pg             the pilot of 20 Nov before the campaign, about 4.5 hours, 2 forks and 1 repetition, not
#                        reported: HikariCP sweeps of tpcc and the micro-workloads (curve shapes, H1a scoring, TPC-C's
#                        window noise), live runs of static-10, static-96, usl-plateau, and usl-response-time on tpcc
#                        and oltpReadWrite (the controllers, the warm-up), and shared (max_connections binds)
#   pilot-mysql          the pilot on MySQL, about 1 hour: the HikariCP sweep of readMostly and writeHeavy
#
# Every block that uses a database first records its settings (DatabaseSettingsReportTest) and checks the link
# (LinkCheckExperimentTest). Environment: DB_HOST (the database server, preferably its private address),
# JDBC_PASSWORD (PostgreSQL) and MYSQL_PASSWORD (MySQL), read by the harness from the environment, so they never
# appear on a command line; JMH_FORKS (10) is the fork count of every JMH block, and REPS (10) the repetitions of
# every live block, so all reported blocks share them; SWEEP_POOLS narrows the pools of a sweep (hikari,dbcp2,agroal);
# FORCE=1 starts even if another java process runs. Results go to ./results/<block>-<host>-<UTC time>/.
set -uo pipefail

BUNDLE="$(cd "$(dirname "$0")" && pwd)"
HOST="$(hostname -s)"
FORKS="${JMH_FORKS:-10}"
REPS="${REPS:-10}"
SIZES="1,2..64:2,72..96:8"
SWEEP_ITERATIONS="-Djmh.forks=$FORKS -Djmh.warmup=3 -Djmh.iters=5 -Djmh.secs=2"
# MySQL's sweeps go on to 128 connections: in the pilot of 20 Nov, its micro-workload curves still rose at 96, so
# their peak lay outside the grid. A sweep runs min(N + 16, threads) clients, so 144 JMH threads keep the queue of 16
# up to 128 connections; up to 112 connections the clients are the same as with the default 128 threads.
MYSQL_SIZES="1,2..64:2,72..128:8"
MYSQL_THREADS=144
# The fixed-clients sweep: 128 clients at every size, so the queue is 128 - N; it starts at 4 connections, since a
# queue of 127 behind one connection risks the 10 s acquisition timeout under the pools' unfair handoff. On MySQL it
# goes on to 128 connections as well (no queue there), so that its N* lies inside the grid.
FIXED_SIZES="4,8..64:4,72..96:8"
MYSQL_FIXED_SIZES="4,8..64:4,72..128:8"
# The short calibration sweep of the smoke runs of K4 and K7 (0.5 s warm-up and 1 s per size).
SMOKE_CALIBRATION="-Dusl.calibration.warmupMillis=500 -Dusl.calibration.measureMillis=1000"
MYSQL_OPTIONS="useSSL=false&allowPublicKeyRetrieval=true&rewriteBatchedStatements=true"
# The pilot's sweeps: the campaign's sizes and iterations at 2 forks.
PILOT_BUDGET="-Djmh.forks=2 -Djmh.warmup=3 -Djmh.iters=5 -Djmh.secs=2 -Djmh.param.poolSize=$SIZES"
PILOT_POLICIES="static-10,static-96,usl-plateau,usl-response-time"

if [ $# -lt 1 ]; then
    sed -n '2,39p' "$0"
    exit 2
fi

# ---------------------------------------------------------------- the tests of every block
# One line per test: <TestClassName> [-D options]. Options with spaces are not supported.
db_of() {
    case "$1" in
        *-pg | *-pg-* | smoke-pg | link-pg | pilot-pg) echo postgresql ;;
        *-mysql | *-mysql-* | smoke-mysql | link-mysql | pilot-mysql) echo mysql ;;
        *) echo none ;;
    esac
}

micro_workloads() {
    [ "$1" = postgresql ] && echo "readMostly,writeHeavy,longTx" || echo "readMostly,writeHeavy"
}

sweep() { # <database> <set> <workloads> <pools>
    local grid="-Djmh.param.poolSize=$SIZES"
    [ "$1" = mysql ] && grid="-Djmh.param.poolSize=$MYSQL_SIZES -Djmh.threads=$MYSQL_THREADS"
    echo "ThroughputVsPoolSizeBenchmarkTest $SWEEP_ITERATIONS $grid -Djmh.param.database=$1 -Djmh.param.workload=$3 -Djmh.param.pool=$4 -Dusl.results.name=ThroughputVsPoolSizeBenchmark-dense-$1-$2"
}

fixed_sizes() { # <database>
    [ "$1" = mysql ] && echo "$MYSQL_FIXED_SIZES" || echo "$FIXED_SIZES"
}

block_tests() {
    local block="$1" db
    db="$(db_of "$block")"
    local pools="${SWEEP_POOLS:-hikari,dbcp2,agroal}"
    if [ "$db" != none ]; then
        echo "DatabaseSettingsReportTest"
        echo "LinkCheckExperimentTest"
    fi
    case "$block" in
        smoke-pg | smoke-mysql)
            echo "ThroughputVsPoolSizeBenchmarkTest -Djmh.forks=1 -Djmh.warmup=1 -Djmh.iters=1 -Djmh.secs=1 -Djmh.param.database=$db -Djmh.param.pool=hikari -Djmh.param.poolSize=4,32 -Djmh.param.workload=readMostly,oltpReadWrite,tpcc -Dusl.results.name=ThroughputVsPoolSizeBenchmark-smoke-$db"
            echo "ConcurrencyAdaptationExperimentTest -Dusl.live.databases=$db -Dusl.reps=1 -Dusl.live.profile=8:15,64:15 -Dusl.live.workloads=oltpReadWrite,tpcc -Dusl.live.policies=static-10,genetic,usl-plateau,usl-response-time"
            echo "LoadProfileBenchmarkTest -Djmh.forks=1 -Djmh.warmup=1 -Djmh.secs=2 -Dusl.profile.levels=8,64 -Dusl.profile.itersPerLevel=2 -Djmh.param.database=$db -Djmh.param.workload=oltpReadWrite -Djmh.param.policy=static-96,usl-response-time -Dusl.results.name=LoadProfileBenchmark-smoke-$db"
            echo "OverheadBenchmarkTest -Djmh.forks=1 -Djmh.warmup=1 -Djmh.iters=1 -Djmh.secs=1"
            echo "ThroughputVsPoolSizeBenchmarkTest -Djmh.forks=1 -Djmh.warmup=1 -Djmh.iters=1 -Djmh.secs=1 -Djmh.param.database=$db -Djmh.param.pool=hikari -Djmh.param.poolSize=4,32 -Djmh.param.workload=readMostly -Dusl.sweep.clients=64 -Dusl.results.name=ThroughputVsPoolSizeBenchmark-smoke-fixed-$db"
            echo "ConcurrencyAdaptationExperimentTest -Dusl.live.databases=$db -Dusl.reps=1 -Dusl.live.profile=64:20 -Dusl.live.workloads=tpcc -Dusl.live.policies=usl-plateau@drift:rescale,usl-response-time@drift:rescale"
            echo "OpenArrivalExperimentTest -Dusl.open.databases=$db -Dusl.reps=1 -Dusl.open.workloads=readMostly -Dusl.open.loads=0.5 -Dusl.open.caps=neta -Dusl.open.seconds=5 -Dusl.open.warmupSeconds=2 $SMOKE_CALIBRATION"
            echo "SharedDatabaseExperimentTest -Dusl.shared.databases=$db -Dusl.reps=1 -Dusl.shared.workloads=readMostly -Dusl.shared.policies=static-neta,usl-response-time -Dusl.shared.instances=2 -Dusl.shared.phaseSeconds=10 $SMOKE_CALIBRATION"
            ;;
        link-pg | link-mysql)
            ;;
        sweep-pg-micro | sweep-mysql-micro)
            sweep "$db" micro "$(micro_workloads "$db")" "$pools"
            ;;
        sweep-pg-sysbench | sweep-mysql-sysbench)
            sweep "$db" sysbench "oltpReadWrite,oltpReadOnly" "$pools"
            ;;
        sweep-pg-tpcc | sweep-mysql-tpcc)
            sweep "$db" tpcc tpcc "${SWEEP_POOLS:-hikari}"
            ;;
        sweep-mysql-default)
            sweep mysql default-config "readMostly,writeHeavy,oltpReadWrite,oltpReadOnly" "${SWEEP_POOLS:-hikari}"
            ;;
        fixed-pg | fixed-mysql)
            echo "ThroughputVsPoolSizeBenchmarkTest $SWEEP_ITERATIONS -Djmh.param.poolSize=$(fixed_sizes "$db") -Dusl.sweep.clients=128 -Djmh.param.database=$db -Djmh.param.workload=$(micro_workloads "$db"),oltpReadWrite -Djmh.param.pool=${SWEEP_POOLS:-hikari} -Dusl.results.name=ThroughputVsPoolSizeBenchmark-fixed128-$db"
            ;;
        profiles-pg | profiles-mysql)
            echo "LoadProfileBenchmarkTest -Djmh.forks=$FORKS -Djmh.param.database=$db -Dusl.results.name=LoadProfileBenchmark-$db"
            ;;
        live-pg | live-mysql)
            echo "ConcurrencyAdaptationExperimentTest -Dusl.live.databases=$db -Dusl.reps=$REPS -Dusl.live.pools=hikari -Dusl.live.workloads=tpcc,oltpReadWrite"
            ;;
        live-pools-pg | live-pools-mysql)
            echo "ConcurrencyAdaptationExperimentTest -Dusl.live.databases=$db -Dusl.reps=$REPS -Dusl.live.pools=dbcp2,agroal -Dusl.live.workloads=tpcc -Dusl.live.policies=static-10,genetic,usl-plateau,usl-response-time"
            ;;
        overhead)
            echo "OverheadBenchmarkTest -Djmh.forks=$FORKS -Djmh.warmup=5 -Djmh.iters=10 -Djmh.secs=1"
            ;;
        drift-pg | drift-mysql)
            echo "ConcurrencyAdaptationExperimentTest -Dusl.live.databases=$db -Dusl.reps=$REPS -Dusl.live.pools=hikari -Dusl.live.workloads=tpcc -Dusl.live.profile=64:600 -Dusl.live.policies=usl-plateau,usl-plateau@drift:rescale,usl-response-time,usl-response-time@drift:rescale"
            ;;
        open-pg | open-mysql)
            echo "OpenArrivalExperimentTest -Dusl.open.databases=$db -Dusl.reps=$REPS"
            ;;
        shared-pg | shared-mysql)
            echo "SharedDatabaseExperimentTest -Dusl.shared.databases=$db -Dusl.reps=$REPS"
            ;;
        sim)
            echo "ConcurrencySimulationExperimentTest"
            echo "PlateauTrackingExperimentTest"
            echo "DriftSimulationExperimentTest"
            ;;
        pilot-pg)
            echo "ThroughputVsPoolSizeBenchmarkTest $PILOT_BUDGET -Djmh.param.database=$db -Djmh.param.workload=tpcc -Djmh.param.pool=hikari -Dusl.results.name=ThroughputVsPoolSizeBenchmark-pilot-$db-tpcc"
            echo "ThroughputVsPoolSizeBenchmarkTest $PILOT_BUDGET -Djmh.param.database=$db -Djmh.param.workload=$(micro_workloads "$db") -Djmh.param.pool=hikari -Dusl.results.name=ThroughputVsPoolSizeBenchmark-pilot-$db-micro"
            echo "ConcurrencyAdaptationExperimentTest -Dusl.live.databases=$db -Dusl.reps=1 -Dusl.live.pools=hikari -Dusl.live.workloads=tpcc,oltpReadWrite -Dusl.live.policies=$PILOT_POLICIES"
            echo "SharedDatabaseExperimentTest -Dusl.shared.databases=$db -Dusl.reps=1"
            ;;
        pilot-mysql)
            echo "ThroughputVsPoolSizeBenchmarkTest $PILOT_BUDGET -Djmh.param.database=$db -Djmh.param.workload=$(micro_workloads "$db") -Djmh.param.pool=hikari -Dusl.results.name=ThroughputVsPoolSizeBenchmark-pilot-$db-micro"
            ;;
        *)
            return 1
            ;;
    esac
}

for block in "$@"; do
    if ! block_tests "$block" > /dev/null; then
        echo "Unknown block: $block" >&2
        exit 2
    fi
done

# ---------------------------------------------------------------- guards (measurement non-interference)
if [ "${FORCE:-0}" != "1" ] && pgrep -x java > /dev/null; then
    echo "Another java process runs on $HOST (a TOSEM or reservoir block?):" >&2
    ps -o pid,etime,args -C java >&2
    echo "Wait until it ends, or set FORCE=1." >&2
    exit 3
fi
for service in postgresql mysql; do
    if systemctl is-active --quiet "$service" 2>/dev/null; then
        echo "$service runs on $HOST, the application server; stop it (systemctl stop $service), so that nothing but" >&2
        echo "the client runs here." >&2
        exit 3
    fi
done
GOVERNOR="$(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor 2>/dev/null || echo unknown)"
if [ "$GOVERNOR" != "performance" ]; then
    echo "WARNING: the CPU governor is '$GOVERNOR', not 'performance' (see the TOSEM setup-server.sh)" >&2
fi

port_open() { # <host> <port>
    timeout 3 bash -c "exec 3<>/dev/tcp/$1/$2" 2>/dev/null
}

# Only the database under test may run on the database server, and it must answer.
check_database_server() { # <database>
    : "${DB_HOST:?Set DB_HOST to the database server IP}"
    local port other other_port
    if [ "$1" = postgresql ]; then
        port=5432; other=mysql; other_port=3306
        : "${JDBC_PASSWORD:?Set JDBC_PASSWORD to the postgres password of the database server}"
        export JDBC_PASSWORD
    else
        port=3306; other=postgresql; other_port=5432
        : "${MYSQL_PASSWORD:?Set MYSQL_PASSWORD to the password of the mysql user of the database server}"
        export MYSQL_PASSWORD
    fi
    if ! port_open "$DB_HOST" "$port"; then
        echo "$1 does not answer on $DB_HOST:$port; start it there with db-switch.sh $1" >&2
        return 1
    fi
    if port_open "$DB_HOST" "$other_port"; then
        echo "$other also answers on $DB_HOST:$other_port; stop it there (db-switch.sh $1), so that only the database" >&2
        echo "under test runs on the database server." >&2
        return 1
    fi
}

block_options() { # <database>
    case "$1" in
        postgresql) echo "-Dusl.db.url=jdbc:postgresql://$DB_HOST/uslpool" ;;
        mysql) echo "-Dusl.mysql.url=jdbc:mysql://$DB_HOST/uslpool?$MYSQL_OPTIONS" ;;
    esac
}

FAILED=0
for block in "$@"; do
    db="$(db_of "$block")"
    if [ "$db" != none ]; then
        check_database_server "$db" || exit 3
    fi

    DIR="results/$block-$HOST-$(date -u +%Y%m%dT%H%M%SZ)"
    mkdir -p "$DIR/metrics"
    DIR="$(cd "$DIR" && pwd)"
    echo "=== block $block on $HOST -> $DIR"
    bash "$BUNDLE/server-record.sh" > "$DIR/server-record-start.txt" 2>&1
    (cd "$BUNDLE" && sha256sum ./*.jar lib/*.jar) > "$DIR/bundle.sha256"
    echo "test,start_utc,end_utc,exit_code" > "$DIR/status.csv"
    {
        echo "block=$block database=$db db_host=${DB_HOST:-} jmh_forks=$FORKS reps=$REPS sweep_pools=${SWEEP_POOLS:-default}"
    } > "$DIR/campaign.txt"

    PIDSTAT_PID=""
    if command -v pidstat > /dev/null; then
        pidstat -u -h -l 10 > "$DIR/pidstat.log" 2>&1 &
        PIDSTAT_PID=$!
    else
        echo "WARNING: pidstat not found (install sysstat); no per-process log" | tee -a "$DIR/console.log"
    fi

    while read -r test options; do
        [ -n "$test" ] || continue
        start="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
        echo "--- $test $options ($start)" | tee -a "$DIR/console.log"
        # shellcheck disable=SC2086
        (cd "$DIR" && bash "$BUNDLE/run.sh" "$test" $(block_options "$db") $options < /dev/null) 2>&1 \
            | tee -a "$DIR/console.log"
        code=${PIPESTATUS[0]}
        echo "$test,$start,$(date -u +%Y-%m-%dT%H:%M:%SZ),$code" >> "$DIR/status.csv"
        if [ "$code" != "0" ]; then
            FAILED=1
            echo "!!! $test ended with exit code $code" | tee -a "$DIR/console.log"
        fi
    done < <(block_tests "$block")

    [ -n "$PIDSTAT_PID" ] && kill "$PIDSTAT_PID" 2>/dev/null
    bash "$BUNDLE/server-record.sh" > "$DIR/server-record-end.txt" 2>&1
    echo "=== block $block done: $(grep -c ',0$' "$DIR/status.csv") of $(($(wc -l < "$DIR/status.csv") - 1)) tests passed"
done
exit $FAILED
