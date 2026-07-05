@echo off
REM Render every gnuplot script under metrics\ to SVG + PDF (requires gnuplot on PATH).

setlocal enabledelayedexpansion
for /r metrics %%f in (*.gp) do (
    echo Rendering %%f
    pushd "%%~dpf"
    gnuplot "%%~nxf"
    popd
)
endlocal
