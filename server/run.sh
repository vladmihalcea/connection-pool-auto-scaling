#!/usr/bin/env bash
# Runs one benchmark or experiment test class of the bundle in the current directory, which receives its outputs
# under ./metrics (created if missing). Needs only Java 25: JAVA_HOME, else /opt/temurin-25, else java on the PATH.
#
#   bash run.sh <TestClassName> [-Dname=value ...]
#
# The database server is given by the system properties, for example
#   -Dusl.db.url=jdbc:postgresql://<db-ip>/uslpool
#   -Dusl.mysql.url='jdbc:mysql://<db-ip>/uslpool?useSSL=false&allowPublicKeyRetrieval=true&rewriteBatchedStatements=true'
# and the passwords by the environment (JDBC_PASSWORD for PostgreSQL, MYSQL_PASSWORD for MySQL), so that they never
# appear on a command line. A configured server that does not answer stops the run; it never falls back to Docker.
#
# JVM_OPTS adds options to the launcher JVM (default: a 4 GB heap); the JMH benchmarks fork their own JVMs. The exit
# code is the launcher's: 0 when the tests passed (a database that is not configured is skipped), 1 when one failed
# or none ran, 2 when no class matches.
set -euo pipefail

if [ $# -lt 1 ]; then
    sed -n '2,15p' "$0"
    exit 2
fi
BUNDLE="$(cd "$(dirname "$0")" && pwd)"
TEST="$1"
shift

if [ -n "${JAVA_HOME:-}" ]; then
    JAVA="$JAVA_HOME/bin/java"
elif [ -x /opt/temurin-25/bin/java ]; then
    JAVA=/opt/temurin-25/bin/java
else
    JAVA=java
fi
if ! "$JAVA" -version 2>&1 | head -1 | grep -q '"25'; then
    echo "Java 25 is required, found: $("$JAVA" -version 2>&1 | head -1) ($JAVA). Run setup-jdk25.sh or set JAVA_HOME." >&2
    exit 2
fi
mkdir -p metrics
# shellcheck disable=SC2086
exec "$JAVA" ${JVM_OPTS:--Xms4g -Xmx4g} "$@" -cp "$BUNDLE/core.jar:$BUNDLE/core-tests.jar:$BUNDLE/lib/*" \
    com.vladmihalcea.phd.pool.testbed.TestbedMain "$TEST"
