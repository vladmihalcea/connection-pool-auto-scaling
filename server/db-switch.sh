#!/usr/bin/env bash
# Runs exactly one database on the database server (s68), or none: the campaign measures one database at a time,
# and the other must not run next to it. Run as root on the database server between blocks:
#
#   bash db-switch.sh postgresql | mysql | none
#
# It prints what runs afterwards. run-campaign.sh on the application server checks the same from its side (the
# database under test answers, the other does not).
set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
    echo "Run $0 as root" >&2
    exit 1
fi
case "${1:-}" in
    postgresql)
        systemctl stop mysql 2> /dev/null || true
        systemctl start postgresql
        until runuser -u postgres -- pg_isready -q; do sleep 1; done
        ;;
    mysql)
        systemctl stop postgresql
        systemctl start mysql
        until mysqladmin ping --silent 2> /dev/null; do sleep 1; done
        ;;
    none)
        systemctl stop postgresql mysql 2> /dev/null || true
        ;;
    *)
        echo "Usage: $0 postgresql | mysql | none" >&2
        exit 2
        ;;
esac
for service in postgresql mysql; do
    echo "$service: $(systemctl is-active "$service" 2> /dev/null || true)"
done
