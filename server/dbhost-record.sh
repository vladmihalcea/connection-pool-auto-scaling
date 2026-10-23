#!/usr/bin/env bash
# Records the CPU and disk use of the database server (s66) every 10 s to a CSV, for the whole campaign. The client's
# CPU is in every block's pidstat.log on s68; with this, the analysis can tell which host bounds a curve (in the pilot
# of 20 Nov, the client used up to 1,032% of its 1,200% on readMostly at 64-96 connections, and nothing showed the
# database server's side). The database server runs nothing but the database under test (db-switch.sh), so its
# host-wide CPU is the database's; a host-wide line also stays one line per sample, whereas pidstat writes a line
# for every PostgreSQL backend (one process per connection, up to 200). Slice the CSV per block with the start and
# end times of the block's status.csv (both in UTC).
#
#   bash dbhost-record.sh <output.csv> [interval seconds]
#
# Columns: time_utc, interval_s; busy_pct, the share of all CPUs (0-100) in user, nice, system, irq, softirq, and
# steal time, then user_pct (with nice), system_pct, iowait_pct, and softirq_pct of all CPUs; busiest_cpu_pct, the
# busiest single CPU (a serialized part shows there first); procs_running and procs_blocked at the end of the
# interval (/proc/stat); and for the device that holds /var/lib (the PostgreSQL and MySQL data): its name,
# disk_util_pct (the share of the interval with I/O in flight), disk_writes_s, and disk_write_mb_s.
# It appends, so a restart keeps the earlier rows. start-dbhost-record.sh starts it as a system service.
set -euo pipefail
out="${1:?Give the output CSV}"
interval="${2:-10}"
dev="$(basename "$(readlink -f "$(findmnt -n -o SOURCE --target /var/lib)")")"
if [ ! -s "$out" ]; then
    echo "time_utc,interval_s,busy_pct,user_pct,system_pct,iowait_pct,softirq_pct,busiest_cpu_pct,procs_running,procs_blocked,disk,disk_util_pct,disk_writes_s,disk_write_mb_s" > "$out"
fi
exec awk -v out="$out" -v interval="$interval" -v dev="$dev" '
function snap(   line, f, clock) {
    while ((getline line < "/proc/stat") > 0) {
        split(line, f, " ")
        if (f[1] ~ /^cpu/) {
            busy[f[1]] = f[2] + f[3] + f[4] + f[7] + f[8] + f[9]
            total[f[1]] = busy[f[1]] + f[5] + f[6]
            if (f[1] == "cpu") { usr = f[2] + f[3]; sys = f[4]; iow = f[6]; sirq = f[8] }
        } else if (f[1] == "procs_running") {
            running = f[2]
        } else if (f[1] == "procs_blocked") {
            blocked = f[2]
        }
    }
    close("/proc/stat")
    while ((getline line < "/proc/diskstats") > 0) {
        split(line, f, " ")
        if (f[3] == dev) { writes = f[8]; sectors = f[10]; ticks = f[13] }
    }
    close("/proc/diskstats")
    clock = "date -u +\"%s.%N %Y-%m-%dT%H:%M:%SZ\""
    clock | getline line
    close(clock)
    split(line, f, " ")
    now = f[1]; stamp = f[2]
}
function keep(   c) {
    for (c in busy) { pbusy[c] = busy[c]; ptotal[c] = total[c] }
    pusr = usr; psys = sys; piow = iow; psirq = sirq
    pwrites = writes; psectors = sectors; pticks = ticks; pnow = now
}
BEGIN {
    snap(); keep()
    while (1) {
        system("sleep " interval)
        snap()
        dt = now - pnow
        all = total["cpu"] - ptotal["cpu"]
        if (dt <= 0 || all <= 0) { keep(); continue }
        busiest = 0
        for (c in busy) {
            if (c != "cpu" && (c in ptotal) && total[c] > ptotal[c]) {
                p = 100 * (busy[c] - pbusy[c]) / (total[c] - ptotal[c])
                if (p > busiest) busiest = p
            }
        }
        printf "%s,%.2f,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%d,%d,%s,%.1f,%.0f,%.2f\n", stamp, dt,
            100 * (busy["cpu"] - pbusy["cpu"]) / all, 100 * (usr - pusr) / all, 100 * (sys - psys) / all,
            100 * (iow - piow) / all, 100 * (sirq - psirq) / all, busiest, running, blocked, dev,
            100 * (ticks - pticks) / (1000 * dt), (writes - pwrites) / dt, (sectors - psectors) * 512 / 1e6 / dt >> out
        fflush(out)
        keep()
    }
}'
