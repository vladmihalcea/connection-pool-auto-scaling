#!/usr/bin/env bash
# Night 3 of the local validation campaign (19-20 Nov 2026), PostgreSQL only.
#
#   bash night3.sh <repository> <unzipped usl-testbed> gate    the gate: smoke-pg, a live check of the fixed
#                                                              usl-plateau and reactive policies on TPC-C, and the
#                                                              PostgreSQL cases of FlexyPoolPlateauExperimentTest
#   bash night3.sh <repository> <unzipped usl-testbed> chain   profiles-pg, live-pools-pg:1, then shared-pg and
#                                                              open-pg only if they can still end before 08:00
set -uo pipefail
REPO="$1"
PACK="$2"
ROOT="$REPO/metrics/Local-2026-11"
CAMPAIGN="$ROOT/local-campaign.sh"
WPACK="$(cd "$PACK" && pwd -W)"
CP="$WPACK/core.jar;$WPACK/core-tests.jar;$WPACK/lib/*"
URL="-Dusl.db.url=jdbc:postgresql://127.0.0.1/uslpool"

# One test outside the campaign blocks, into the gate folder.
run_test() { # <dir> <name> <test> <options...>
    local dir="$1" name="$2" test="$3"
    shift 3
    local start
    start="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "--- $name ($start)" | tee -a "$dir/console.log"
    # shellcheck disable=SC2068
    (cd "$dir" && java -Xms4g -Xmx8g $URL $@ -cp "$CP" com.vladmihalcea.phd.pool.testbed.TestbedMain "$test" \
        < /dev/null) >> "$dir/console.log" 2>&1
    local code=$?
    echo "$name,$start,$(date -u +%Y-%m-%dT%H:%M:%SZ),$code" >> "$dir/status.csv"
    echo "$name exit $code"
}

# A block only if it can still end before 08:00: <latest start HHMM> <block>.
run_if_time() {
    local now
    now="$(date +%H%M)"
    if [ "$now" -ge 1800 ] || [ "$now" -lt "$1" ]; then
        bash "$CAMPAIGN" "$REPO" "$PACK" "$ROOT" "$2"
    else
        echo "--- $now: $2 skipped (it would not end before 08:00)"
    fi
}

case "${3:-}" in
    gate)
        bash "$CAMPAIGN" "$REPO" "$PACK" "$ROOT" smoke-pg
        DIR="$ROOT/gate-local-$(date -u +%Y%m%dT%H%M%SZ)"
        mkdir -p "$DIR/metrics"
        (cd "$PACK" && sha256sum core.jar core-tests.jar) > "$DIR/bundle.sha256"
        echo "test,start_utc,end_utc,exit_code" > "$DIR/status.csv"
        # usl-plateau must leave the 96 cap at 64 clients and give connections back at 16; reactive must grow.
        run_test "$DIR" live-plateau-tpcc ConcurrencyAdaptationExperimentTest -Dusl.live.databases=postgresql \
            -Dusl.reps=1 -Dusl.live.pools=hikari -Dusl.live.workloads=tpcc -Dusl.live.policies=usl-plateau,reactive \
            -Dusl.live.profile=8:60,64:180,16:60
        # The cases without a fit on the morning of 19 Nov (PostgreSQL Agroal among them).
        run_test "$DIR" flexypool-plateau-pg FlexyPoolPlateauExperimentTest -Dusl.live.databases=postgresql
        # Information only (the chain is PostgreSQL-only): the three MySQL cases, with MySQL kept running tonight.
        run_test "$DIR" flexypool-plateau-mysql FlexyPoolPlateauExperimentTest -Dusl.live.databases=mysql
        echo "=== gate done: $(grep -c ',0$' "$DIR/status.csv") of $(($(wc -l < "$DIR/status.csv") - 1)) passed -> $DIR"
        ;;
    chain)
        bash "$CAMPAIGN" "$REPO" "$PACK" "$ROOT" profiles-pg live-pools-pg:1
        run_if_time 0545 shared-pg
        run_if_time 0615 open-pg
        ;;
    *)
        echo "usage: night3.sh <repository> <pack> gate|chain" >&2
        exit 2
        ;;
esac
