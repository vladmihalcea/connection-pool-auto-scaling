# Run elevated before each night of local benchmarks. It stops the database servers the night does not use, so that
# they take no CPU, memory or disk time from PostgreSQL and the benchmark JVM:
#   - Oracle and SQL Server, always;
#   - MySQL, unless -KeepMySQL is given (the nights that run MySQL blocks).
# PostgreSQL always stays up. Only the running state changes: the startup types stay as they are, so the services come
# back after a reboot and the script has to run again each night.
#
#   Start-Process powershell -Verb RunAs -ArgumentList '-NoProfile -ExecutionPolicy Bypass -File "<this file>"'
param([switch]$KeepMySQL)

$patterns = @('^Oracle', '^MSSQL', '^SQLAgent\$', '^SQLBrowser$', '^SQLTELEMETRY', '^SQLWriter$')
if (-not $KeepMySQL) {
    $patterns += '^MySQL'
}
$log = Join-Path $PSScriptRoot 'prepare-night.log'
"== $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz') KeepMySQL=$KeepMySQL" | Out-File -Append -Encoding utf8 $log

foreach ($service in Get-Service) {
    $match = $patterns | Where-Object { $service.Name -match $_ }
    if ($match -and $service.Status -ne 'Stopped') {
        try {
            Stop-Service -Name $service.Name -Force -ErrorAction Stop
            "stopped  $($service.Name)" | Out-File -Append -Encoding utf8 $log
        } catch {
            "FAILED   $($service.Name): $($_.Exception.Message)" | Out-File -Append -Encoding utf8 $log
        }
    }
}

Get-Service | Where-Object { $_.Name -match 'oracle|mssql|^sql|mysql|postgres' } | Sort-Object Name |
        ForEach-Object { '{0,-8} {1}' -f $_.Status, $_.Name } | Out-File -Append -Encoding utf8 $log
