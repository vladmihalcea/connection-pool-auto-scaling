#!/usr/bin/env bash
# Starts dbhost-record.sh as the transient system service usl-dbhost on the database server (s66), outside the ssh
# session, at normal priority (the reservoir campaign's telemetry log starved for minutes at nice 19, and one
# sample per 10 s costs nothing measurable). It writes /root/usl-records/dbhost-<name>.csv and runs until stopped:
# start it before the first block of the campaign and stop it after the last one (systemctl stop usl-dbhost).
#
#   bash start-dbhost-record.sh <name>
set -euo pipefail
BUNDLE="$(cd "$(dirname "$0")" && pwd)"
name="${1:?Give a name for the CSV, e.g. campaign}"
mkdir -p /root/usl-records
csv="/root/usl-records/dbhost-$name.csv"
systemd-run --unit=usl-dbhost --collect --property=WorkingDirectory=/root/usl-records \
    --property=StandardOutput=append:/root/usl-records/dbhost-"$name".log \
    --property=StandardError=append:/root/usl-records/dbhost-"$name".log \
    bash "$BUNDLE/dbhost-record.sh" "$csv" && echo "Started the usl-dbhost service, CSV $csv"
