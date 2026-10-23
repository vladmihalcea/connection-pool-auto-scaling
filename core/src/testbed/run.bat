@echo off
rem Runs one benchmark or experiment of the connection-pool-auto-scaling harness on this client machine
rem against the PostgreSQL and MySQL servers of a remote database host. Needs only Java 25, on the PATH
rem or in JAVA_HOME.
rem
rem   run.bat <db-host> <TestName>
rem
rem Extra settings go in the JAVA_OPTS environment variable, for example:
rem   set JAVA_OPTS=-Djmh.param.pool=hikari -Djmh.param.workload=readMostly
rem Results are written to the metrics folder next to this script.
setlocal
if "%~2"=="" (
    echo Usage: run.bat ^<db-host^> ^<TestName^>
    echo   e.g. run.bat 192.168.1.20 LinkCheckExperimentTest
    exit /b 2
)
cd /d "%~dp0"
if not exist metrics mkdir metrics
set "JAVA_EXE=java"
if defined JAVA_HOME set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
"%JAVA_EXE%" %JAVA_OPTS% ^
    "-Dusl.db.url=jdbc:postgresql://%~1/uslpool" ^
    "-Dusl.mysql.url=jdbc:mysql://%~1/uslpool?useSSL=false&allowPublicKeyRetrieval=true&rewriteBatchedStatements=true" ^
    -cp "core.jar;core-tests.jar;lib\*" com.vladmihalcea.phd.pool.testbed.TestbedMain %~2
exit /b %ERRORLEVEL%
