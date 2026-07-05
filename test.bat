@echo off
REM Run the fast unit tests only (no Docker required): USL model + fitter, queueing models,
REM the auto-scaling controller against the simulated pool, and workload determinism.

pushd core
mvn test %*
popd
