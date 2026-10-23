@echo off
rem The dense pool-size sweep (K1 of the plateau-start study): 68 pool sizes (1 to 40, then every second
rem size up to 96) for the three pools at the ICASC settings. The micro set (the three ICASC workloads)
rem takes about 7 to 10 hours per database; the sysbench set (the sysbench-derived oltpReadWrite and
rem oltpReadOnly) about two thirds of that. Throughput goes to
rem metrics\jmh-results\ThroughputVsPoolSizeBenchmark-dense-<database>-<set>.csv and the response-time
rem percentiles to the matching -latency.csv.
rem
rem   dense-sweep.bat <db-host> [postgresql|mysql] [micro|sysbench]
rem
rem Narrow it with JAVA_OPTS, for example:
rem   set JAVA_OPTS=-Djmh.param.pool=hikari
setlocal
set "DATABASE=%~2"
if "%DATABASE%"=="" set "DATABASE=postgresql"
set "SET=%~3"
if "%SET%"=="" set "SET=micro"
if /i "%SET%"=="micro" (
    set "WORKLOADS=readMostly,writeHeavy,longTx"
) else if /i "%SET%"=="sysbench" (
    set "WORKLOADS=oltpReadWrite,oltpReadOnly"
) else (
    echo Unknown workload set "%SET%": use micro or sysbench
    exit /b 2
)
set "JAVA_OPTS=-Djmh.param.poolSize=1..40,42..96:2 -Djmh.param.database=%DATABASE% -Djmh.param.workload=%WORKLOADS% -Dusl.results.name=ThroughputVsPoolSizeBenchmark-dense-%DATABASE%-%SET% %JAVA_OPTS%"
call "%~dp0run.bat" %1 ThroughputVsPoolSizeBenchmarkTest
