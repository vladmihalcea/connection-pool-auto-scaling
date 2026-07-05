@echo off
REM Run the JMH performance benchmarks (E1 throughput sweep, E6 overhead). Requires Docker.
REM Produces JSON + CSV under metrics\jmh-results.

pushd core
call mvn -P benchmark-tests clean process-test-resources

call mvn -P benchmark-tests -Dit.test=ThroughputVsPoolSizeBenchmarkTest integration-test &if not errorlevel 1 ^
call mvn -P benchmark-tests -Dit.test=OverheadBenchmarkTest integration-test
popd
