#!/usr/bin/env bash
# Local validation campaign of the USL article: the blocks of the Hostner campaign (server/run-campaign.sh, read from
# the repository so both run exactly the same tests and options) with fewer forks and repetitions, against the local
# PostgreSQL and MySQL, from the testbed pack. One results folder per block, as on the servers.
#
#   bash local-campaign.sh <repository> <unzipped usl-testbed> <results root> <block>[:<reps>] ...
#
# JMH_FORKS (2) is the fork count of every JMH block; a block's repetitions follow its name (live-pg:1), else REPS (2).
# SWEEP_POOLS narrows the pools of a sweep, as on the servers; <block>@<pools> sets it for one block.
set -uo pipefail
REPO="$1"
PACK="$2"
ROOT="$3"
shift 3
CAMPAIGN="$REPO/server/run-campaign.sh"
JMH_FORKS="${JMH_FORKS:-2}"
DEFAULT_REPS="${REPS:-2}"
HOST="local"

# The variables and the block definitions of the server campaign, without its Linux-only guards.
eval "$(awk '/^FORKS=/{p=1} p{print} /^MYSQL_OPTIONS=/{exit}' "$CAMPAIGN")"
eval "$(awk '/^db_of\(\) \{/{p=1} p{print} p && /^block_tests\(\) \{/{b=1} b && /^\}/{exit}' "$CAMPAIGN")"

WPACK="$(cd "$PACK" && pwd -W)"
CP="$WPACK/core.jar;$WPACK/core-tests.jar;$WPACK/lib/*"
mkdir -p "$ROOT"
cp "$0" "$ROOT/local-campaign.sh" 2>/dev/null

for spec in "$@"; do
    block="${spec%%[:@]*}"
    REPS="$DEFAULT_REPS"
    [[ "$spec" == *:* ]] && REPS="${spec##*:}" && REPS="${REPS%%@*}"
    pools=""
    [[ "$spec" == *@* ]] && pools="${spec##*@}" && pools="${pools%%:*}"
    if [ -n "$pools" ]; then
        export SWEEP_POOLS="$pools"
    else
        unset SWEEP_POOLS
    fi
    # Only the URL of the block's database, as block_options does on the servers: the tests that cover every database
    # (the settings report, the link check) then skip the other one instead of failing on a stopped server.
    case "$(db_of "$block")" in
        postgresql) urls="-Dusl.db.url=jdbc:postgresql://127.0.0.1/uslpool" ;;
        mysql) urls="-Dusl.mysql.url=jdbc:mysql://127.0.0.1/uslpool?$MYSQL_OPTIONS" ;;
        *) urls="" ;;
    esac
    DIR="$ROOT/$block-$HOST-$(date -u +%Y%m%dT%H%M%SZ)"
    mkdir -p "$DIR/metrics"
    echo "=== block $block (forks $FORKS, reps $REPS, pools ${SWEEP_POOLS:-default}) -> $DIR"
    echo "block=$block forks=$FORKS reps=$REPS sweep_pools=${SWEEP_POOLS:-default}" > "$DIR/campaign.txt"
    (cd "$PACK" && sha256sum core.jar core-tests.jar) > "$DIR/bundle.sha256"
    echo "test,start_utc,end_utc,exit_code" > "$DIR/status.csv"
    while read -r test options; do
        [ -n "$test" ] || continue
        start="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
        echo "--- $test $options ($start)" | tee -a "$DIR/console.log"
        # shellcheck disable=SC2086
        (cd "$DIR" && java -Xms4g -Xmx8g $urls $options \
            -cp "$CP" com.vladmihalcea.phd.pool.testbed.TestbedMain "$test" < /dev/null) >> "$DIR/console.log" 2>&1
        code=$?
        echo "$test,$start,$(date -u +%Y-%m-%dT%H:%M:%SZ),$code" >> "$DIR/status.csv"
        echo "$test exit $code"
    done < <(block_tests "$block")
    echo "=== block $block done: $(grep -c ',0$' "$DIR/status.csv") of $(($(wc -l < "$DIR/status.csv") - 1)) tests passed"
done
