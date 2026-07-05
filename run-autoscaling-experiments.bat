@echo off
REM Run the deterministic auto-scaling experiments (E2 USL fit, E3 model comparison, E4 convergence,
REM E5 regime shift, E7 memory). The USL-fit and model-comparison reports and the simulated-pool
REM convergence runs need no Docker; the PostgreSQL-backed variants do.

pushd core
call mvn -P benchmark-tests clean process-test-resources

call mvn -P benchmark-tests -Dit.test=UslFitReportTest integration-test &if not errorlevel 1 ^
call mvn -P benchmark-tests -Dit.test=ModelComparisonReportTest integration-test &if not errorlevel 1 ^
call mvn -P benchmark-tests -Dit.test=AutoScalingConvergenceExperimentTest integration-test &if not errorlevel 1 ^
call mvn -P benchmark-tests -Dit.test=RegimeShiftExperimentTest integration-test &if not errorlevel 1 ^
call mvn -P benchmark-tests -Dit.test=MemoryFootprintReportTest integration-test
popd
