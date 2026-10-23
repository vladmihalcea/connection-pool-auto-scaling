@echo off
REM Run the live, database-backed plateau experiments: L1 (the plateau strategy on HikariCP, DBCP2 and
REM Agroal over PostgreSQL and MySQL) and L2 (TPC-C-derived sweep vs. the live strategy). Each live case
REM runs for 90 s; L2 also loads the TPC-C schema (-Dtpcc.warehouses=2 by default) twice per database.

pushd core
call mvn -P benchmark-tests -Dit.test=FlexyPoolPlateauExperimentTest integration-test &if not errorlevel 1 ^
call mvn -P benchmark-tests -Dit.test=TpccPlateauExperimentTest integration-test %*
popd
