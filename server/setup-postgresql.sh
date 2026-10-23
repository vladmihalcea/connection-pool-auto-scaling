#!/usr/bin/env bash
# Prepares the PostgreSQL 18 of the database server (s68, set up with ROLE=db by the TOSEM setup-server.sh) for the
# USL campaign. Run as root on the database server, outside any measurement window:
#
#   PEER_IP=<application server IP> bash setup-postgresql.sh
#   RESTORE=1 bash setup-postgresql.sh      # afterwards: max_connections back to the TOSEM setting
#
# It changes only what the campaign needs and keeps every other setting of setup-server.sh (shared_buffers 8GB,
# synchronous_commit on, and so on), which DatabaseSettingsReportTest records in every results folder:
#   1. the "uslpool" database (shared with the reservoir bundle's starvation block);
#   2. a pg_hba.conf line that admits the application server for uslpool, marked "# usl-peer";
#   3. max_connections = 200 (the TOSEM default is 100): the largest pool (96) plus the admin pool and the link
#      check must fit with room to spare, so that the server cap never shapes the curve. It needs a restart.
# The postgres password stays the one setup-server.sh set (PG_PASSWORD); the campaign takes it as JDBC_PASSWORD.
# The firewall already admits PostgreSQL from PEER_IP (setup-server.sh). Running it again is harmless.
set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
    echo "Run $0 as root" >&2
    exit 1
fi
PG_MAJOR="$(ls /etc/postgresql | sort -n | tail -1)"
PG_HBA="/etc/postgresql/$PG_MAJOR/main/pg_hba.conf"
MAX_CONNECTIONS="${MAX_CONNECTIONS:-200}"

psql_postgres() { (cd /tmp && runuser -u postgres -- psql -X -v ON_ERROR_STOP=1 -qAt "$@"); }
wait_ready() {
    until runuser -u postgres -- pg_isready -q; do
        sleep 1
    done
}

if systemctl is-active --quiet mysql 2>/dev/null; then
    echo "Stopping MySQL, so that only one database runs on this server"
    systemctl stop mysql
fi
systemctl start postgresql
wait_ready

if [ "${RESTORE:-0}" = "1" ]; then
    psql_postgres -c "ALTER SYSTEM RESET max_connections"
    systemctl restart postgresql
    wait_ready
    echo "max_connections = $(psql_postgres -c 'SHOW max_connections') (reset)"
    exit 0
fi

: "${PEER_IP:?Set PEER_IP to the application server IP}"

if [ "$(psql_postgres -c "SELECT 1 FROM pg_database WHERE datname = 'uslpool'")" != "1" ]; then
    psql_postgres -c "CREATE DATABASE uslpool"
    echo "Created database uslpool"
fi

LINE="host    uslpool    postgres    $PEER_IP/32    scram-sha-256    # usl-peer"
if ! grep -qF "# usl-peer" "$PG_HBA"; then
    echo "$LINE" >> "$PG_HBA"
    echo "Added to $PG_HBA: $LINE"
elif ! grep -qF "$LINE" "$PG_HBA"; then
    sed -i "/# usl-peer/c\\$LINE" "$PG_HBA"
    echo "Updated in $PG_HBA: $LINE"
fi

BEFORE="$(psql_postgres -c 'SHOW max_connections')"
if [ "$BEFORE" != "$MAX_CONNECTIONS" ]; then
    psql_postgres -c "ALTER SYSTEM SET max_connections = $MAX_CONNECTIONS"
    systemctl restart postgresql
    wait_ready
    echo "max_connections: $BEFORE -> $(psql_postgres -c 'SHOW max_connections') (RESTORE=1 resets it)"
else
    systemctl reload postgresql
fi
echo "listen_addresses = $(psql_postgres -c 'SHOW listen_addresses'), version: $(psql_postgres -c 'SHOW server_version')"
echo "PostgreSQL $PG_MAJOR is ready for the USL campaign"
