@echo off
REM Run a single JMH benchmark family through the failsafe plugin (requires Docker for the DB-backed ones).
REM Usage: run-benchmark.bat Sanity^|ThroughputVsPoolSize^|Overhead

if "%1" == "" goto usage

pushd core
set type=%1
call mvn -P benchmark-tests clean process-test-resources
mvn -P benchmark-tests -Dit.test=%type%BenchmarkTest integration-test
popd
goto:eof

:usage
echo Usage: %0 Sanity^|ThroughputVsPoolSize^|Overhead
