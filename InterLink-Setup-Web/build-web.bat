@echo off
setlocal EnableExtensions
rem ===========================================================================
rem  InterLink - one-click browser version build
rem
rem  Double-click this file. It runs the browser build script in
rem  packaging\web\ and leaves interlink-web.exe in packaging\web\out\.
rem
rem  Run that .exe, open the http://127.0.0.1:... link it prints, and the
rem  InterLink UI appears in your browser.
rem
rem  The window stays open when the build finishes (or fails), so you can read
rem  the result. Prerequisites are listed in README.md here and in
rem  packaging\web\README.md.
rem ===========================================================================

set "HERE=%~dp0"
set "SCRIPT=%HERE%..\packaging\web\build-web.bat"

echo.
echo InterLink - browser version
echo ===========================

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
