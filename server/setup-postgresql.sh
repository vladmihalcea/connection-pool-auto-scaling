#!/usr/bin/env bash
# Prepares the PostgreSQL 18 of the database server (s66, the database host since the TOSEM role swap) for the
# USL campaign. Run as root on the database server, outside any measurement window:
#
#   PEER_IP=<application server IP> bash setup-postgresql.sh
#   RESTORE=1 bash setup-postgresql.sh      # afterwards: max_connections and autovacuum logging back to TOSEM's
#
# It changes only what the campaign needs and keeps every other setting of the TOSEM campaign (on s66, 20 Nov:
# shared_buffers 8 GB, max_wal_size 16 GB, checkpoint_timeout 30 min, synchronous_commit on), which
# DatabaseSettingsReportTest records in every results folder:
#   1. the "uslpool" database (shared with the reservoir bundle's starvation block);
#   2. a pg_hba.conf line that admits the application server for uslpool, marked "# usl-peer";
#   3. max_connections = 200 (the TOSEM default is 100): the largest pool (96) plus the admin pool and the link
#      check must fit with room to spare, so that the server cap never shapes the curve. It needs a restart.
#   4. log_autovacuum_min_duration = 0, so the server log has every autovacuum with its start and duration. The
#      write workloads consume transaction IDs fast (the reservoir V1 block used 190 million in 8.5 hours), so an
#      anti-wraparound vacuum of every database starts whenever the oldest one passes autovacuum_freeze_max_age
#      (200 million); the log places them against the runs.
#   5. vacuumdb --all --freeze, then the checks before a block: the oldest transaction-ID age, prepared
#      transactions, and replication slots (any of the last two holds the xmin horizon, as on the laptop in Nov).
#      Run the script again before each phase; the freeze takes seconds on these small databases.
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
    psql_postgres -c "ALTER SYSTEM RESET log_autovacuum_min_duration"
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

psql_postgres -c "ALTER SYSTEM SET log_autovacuum_min_duration = 0"

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
echo "log_autovacuum_min_duration = $(psql_postgres -c 'SHOW log_autovacuum_min_duration')"

(cd /tmp && runuser -u postgres -- vacuumdb --all --freeze --quiet)
# vacuumdb cannot connect to template0 (datallowconn = false), so its age keeps growing until autovacuum freezes it,
# which takes milliseconds on that template; report it apart from the databases the freeze reached.
echo "Frozen: oldest transaction-ID age $(psql_postgres -c 'SELECT max(age(datfrozenxid)) FROM pg_database WHERE datallowconn')" \
    "(template0: $(psql_postgres -c "SELECT age(datfrozenxid) FROM pg_database WHERE datname = 'template0'"))," \
    "prepared transactions $(psql_postgres -c 'SELECT count(*) FROM pg_prepared_xacts')," \
    "replication slots $(psql_postgres -c 'SELECT count(*) FROM pg_replication_slots')"
if [ "$(psql_postgres -c 'SELECT count(*) FROM pg_prepared_xacts')" != "0" ] \
        || [ "$(psql_postgres -c 'SELECT count(*) FROM pg_replication_slots')" != "0" ]; then
    echo "A prepared transaction or a replication slot holds the xmin horizon: resolve it before any block" >&2
    exit 1
fi
echo "PostgreSQL $PG_MAJOR is ready for the USL campaign"
