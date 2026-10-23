@echo off
rem Measures the SELECT 1 round-trip time and the ceiling throughput between this client and the
rem database host (metrics\LinkCheck\link-check.csv). Run it before every measurement campaign.
rem
rem   link-check.bat <db-host>
call "%~dp0run.bat" %1 LinkCheckExperimentTest
