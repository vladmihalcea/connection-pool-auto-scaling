#!/usr/bin/env bash
# Installs and configures MySQL on the database server (s66) for the USL campaign. Run as root on the database
# server, outside any measurement window (MySQL starts once during the installation):
#
#   PEER_IP=<application server IP> MYSQL_PASSWORD=<password> PROFILE=tuned bash setup-mysql.sh
#   PROFILE=default bash setup-mysql.sh     # later: switch to the default configuration (sweep-mysql-default)
#
# 1. It installs mysql-server from the Ubuntu archive and holds its version (the server record and
#    DatabaseSettingsReportTest record which one).
# 2. It writes /etc/mysql/mysql.conf.d/zz-usl.cnf for PROFILE:
#      default  only what the campaign needs: listening on the network, and max_connections = 200;
#      tuned    the same, plus the memory and redo log sized like the PostgreSQL of the TOSEM campaign, so the two
#               databases are compared on equal terms: innodb_buffer_pool_size = 8G (s66's PostgreSQL:
#               shared_buffers 8 GB, 25% of the memory), innodb_redo_log_capacity = 4G (so a sweep never waits on a
#               redo checkpoint), O_DIRECT, and innodb_io_capacity = 2000 for the SSD.
#    Both keep the durable defaults, innodb_flush_log_at_trx_commit = 1 and sync_binlog = 1, as PostgreSQL keeps
#    synchronous_commit = on: every commit waits for its log flush.
# 3. It creates the "uslpool" database and a "mysql" user that may connect from PEER_IP only, with MYSQL_PASSWORD,
#    which the campaign reads from the environment too. The SQL goes through stdin, so the password never appears
#    on a command line.
# 4. It opens 3306 to PEER_IP in the firewall.
# 5. It disables the automatic start of MySQL and stops it, so that it never runs next to PostgreSQL or a TOSEM
#    block; db-switch.sh starts the database of a block. START=1 leaves it running.
# Running it again is harmless; PEER_IP and MYSQL_PASSWORD are needed only for the first run.
set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
    echo "Run $0 as root" >&2
    exit 1
fi
PROFILE="${PROFILE:-tuned}"
case "$PROFILE" in
    default | tuned) ;;
    *) echo "PROFILE must be default or tuned, not $PROFILE" >&2; exit 1 ;;
esac
CONF=/etc/mysql/mysql.conf.d/zz-usl.cnf

if ! dpkg -s mysql-server > /dev/null 2>&1; then
    : "${PEER_IP:?Set PEER_IP to the application server IP}"
    : "${MYSQL_PASSWORD:?Set MYSQL_PASSWORD for the mysql user}"
    export DEBIAN_FRONTEND=noninteractive
    apt-get -y -q -o DPkg::Lock::Timeout=600 update
    apt-get -y -q -o DPkg::Lock::Timeout=600 install mysql-server
fi
# Hold the installed MySQL packages only: dpkg-query also lists names it merely knows of (mysql-server-8.0 on Ubuntu
# 26.04, whose packages carry no version suffix), and apt-mark fails on those. An installed package shows as "ii",
# or as "hi" once it is held, so a rerun matches both (with none, apt-mark fails and the script stops).
apt-mark hold $(dpkg-query -W -f '${db:Status-Abbrev} ${Package}\n' 'mysql-*' 2>/dev/null | awk '$1 ~ /^[hi]i$/ {print $2}') > /dev/null
echo "MySQL package: $(dpkg-query -W -f '${Package} ${Version}\n' mysql-server), held: $(apt-mark showhold | grep -c '^mysql-')"

if systemctl is-active --quiet postgresql; then
    echo "Stopping PostgreSQL, so that only one database runs on this server"
    systemctl stop postgresql
fi

{
    echo "# Written by setup-mysql.sh of connection-pool-auto-scaling, PROFILE=$PROFILE"
    echo "[mysqld]"
    echo "bind-address = 0.0.0.0"
    echo "mysqlx-bind-address = 127.0.0.1"
    echo "max_connections = 200"
    if [ "$PROFILE" = tuned ]; then
        echo "innodb_buffer_pool_size = 8G"
        echo "innodb_redo_log_capacity = 4G"
        echo "innodb_flush_method = O_DIRECT"
        echo "innodb_io_capacity = 2000"
    fi
} > "$CONF"
echo "Wrote $CONF:"
sed 's/^/    /' "$CONF"

systemctl restart mysql
until mysqladmin ping --silent 2> /dev/null; do
    sleep 1
done

if [ -n "${PEER_IP:-}" ] && [ -n "${MYSQL_PASSWORD:-}" ]; then
    mysql --protocol=socket -uroot <<SQL
CREATE DATABASE IF NOT EXISTS uslpool;
CREATE USER IF NOT EXISTS 'mysql'@'$PEER_IP' IDENTIFIED BY '$MYSQL_PASSWORD';
ALTER USER 'mysql'@'$PEER_IP' IDENTIFIED BY '$MYSQL_PASSWORD';
GRANT ALL PRIVILEGES ON uslpool.* TO 'mysql'@'$PEER_IP';
FLUSH PRIVILEGES;
SQL
    echo "Database uslpool and user mysql@$PEER_IP are ready"
    ufw allow from "$PEER_IP" to any port 3306 proto tcp > /dev/null
    echo "Firewall: 3306 open to $PEER_IP"
fi

mysql --protocol=socket -uroot -N -e "SELECT CONCAT('version ', VERSION(), ', max_connections ', @@max_connections,
    ', buffer pool ', @@innodb_buffer_pool_size DIV 1048576, ' MB, flush_log_at_trx_commit ',
    @@innodb_flush_log_at_trx_commit, ', sync_binlog ', @@sync_binlog)"

systemctl disable mysql > /dev/null 2>&1 || true
if [ "${START:-0}" = "1" ]; then
    echo "MySQL runs (START=1); stop it with db-switch.sh none or db-switch.sh postgresql"
else
    systemctl stop mysql
    echo "MySQL is stopped and does not start at boot; db-switch.sh mysql starts it for a block"
fi
