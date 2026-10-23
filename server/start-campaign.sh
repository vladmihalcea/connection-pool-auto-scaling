#!/usr/bin/env bash
# Starts run-campaign.sh as the transient system service usl-campaign, outside the ssh session, so a disconnect does
# not stop it and no login shell runs next to the forks. Run as root on the application server (s68), with the
# postgres password of the database server on the first line of stdin and the password of its mysql user on the
# second (an empty line for a database no block uses). The passwords go to a root-only environment file, never on a
# command line; the output goes to /root/usl-campaign-<name>.log, and the results to /root/usl/results (apart from
# the reservoir campaign's /root/results).
#
#   bash start-campaign.sh <name> "<blocks>" <database server IP> < password-lines
#
# Environment passed on to the campaign: JMH_FORKS, REPS, and SWEEP_POOLS (see run-campaign.sh), when set.
# Follow it with systemctl status usl-campaign and the log; stop it with systemctl stop usl-campaign (the current
# test is killed, the finished results folders stay). The reservoir bundle's start-campaign.sh works the same way.
set -euo pipefail
BUNDLE="$(cd "$(dirname "$0")" && pwd)"
name="${1:?Give a name for the log}"
blocks="${2:?Give the blocks, e.g. \"link-pg smoke-pg\"}"
db_host="${3:?Give the database server IP}"
read -r jdbc_password || true
read -r mysql_password || true
umask 077
env_file=/root/.usl-campaign.env
{
    echo "JDBC_PASSWORD=$jdbc_password"
    echo "MYSQL_PASSWORD=$mysql_password"
    echo "DB_HOST=$db_host"
    echo "HOME=/root"
    for variable in JMH_FORKS REPS SWEEP_POOLS; do
        if [ -n "${!variable:-}" ]; then
            echo "$variable=${!variable}"
        fi
    done
} > "$env_file"
log="/root/usl-campaign-$name.log"
mkdir -p /root/usl
# shellcheck disable=SC2086
systemd-run --unit=usl-campaign --collect \
    --property=EnvironmentFile="$env_file" --property=WorkingDirectory=/root/usl \
    --property=StandardOutput=append:"$log" --property=StandardError=append:"$log" \
    bash "$BUNDLE/run-campaign.sh" $blocks && echo "Started the usl-campaign service ($blocks), log $log"
