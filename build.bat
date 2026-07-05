@echo off
REM Build the project and run the fast unit tests (USL math, controller simulation, workload determinism).

mvn clean install
goto:eof
