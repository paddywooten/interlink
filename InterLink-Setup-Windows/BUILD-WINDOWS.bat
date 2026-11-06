@echo off
setlocal EnableExtensions
rem ===========================================================================
rem  InterLink - one-click Windows build
rem
rem  Double-click this file. It runs the Windows build script in
rem  packaging\windows\ and leaves the installer in
rem  packaging\windows\out\InterLink_*.msi
rem
rem  The window stays open when the build finishes (or fails), so you can read
rem  the result. First-run prerequisites are listed in README.md here and in
rem  packaging\windows\README.md.
rem ===========================================================================

set "HERE=%~dp0"
set "SCRIPT=%HERE%..\packaging\windows\build-msi.bat"

echo.
echo InterLink - Windows build
echo =========================

if not exist "%SCRIPT%" (
    echo.
    echo [ERROR] Could not find the build script:
    echo         %SCRIPT%
    echo.
    echo Keep this folder inside the InterLink repository, next to
    echo "packaging" and "desktop".
    echo.
    pause
    exit /b 1
)

call "%SCRIPT%"
exit /b %errorlevel%
