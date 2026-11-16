@echo off
setlocal EnableExtensions
rem ===========================================================================
rem  InterLink - build the browser version (interlink-web)
rem
rem  Double-click this file. It builds the InterLink laptop backend with the
rem  browser UI and leaves interlink-web.exe in packaging\web\out\.
rem
rem  Prerequisites (the script names whichever is missing):
rem    Node.js 20+     https://nodejs.org
rem    Rust (stable)   https://rustup.rs
rem
rem  Run the .exe, then open the http://127.0.0.1:... link it prints.
rem ===========================================================================

set "HERE=%~dp0"
set "ROOT=%HERE%..\.."
set "DESKTOP=%ROOT%\desktop"

echo.
echo InterLink - browser version
echo ===========================

if not exist "%DESKTOP%\src-tauri" (
    echo.
    echo [ERROR] Could not find desktop\src-tauri.
    echo         Keep packaging\ inside the InterLink repository, next to
    echo         desktop\ and android\.
    echo.
    pause
    exit /b 1
)

where node >nul 2>nul
if errorlevel 1 (
    echo.
    echo [ERROR] Node.js is not installed.
    echo         Install Node.js 20+ from https://nodejs.org, then run this again.
    echo.
    pause
    exit /b 1
)

where cargo >nul 2>nul
if errorlevel 1 (
    echo.
    echo [ERROR] Rust is not installed.
    echo         Install it from https://rustup.rs, then run this again.
    echo.
    pause
    exit /b 1
)

echo.
echo ==^> frontend ^(React to static files^)
pushd "%DESKTOP%"
call npm install --no-audit --no-fund
if errorlevel 1 goto :failed
call npm run build
if errorlevel 1 goto :failed

echo.
echo ==^> laptop backend + web server ^(release^)
pushd "%DESKTOP%\src-tauri"
cargo build --release --no-default-features --features web --bin interlink-web
if errorlevel 1 goto :failed
popd

if not exist "%HERE%out" mkdir "%HERE%out"
copy /Y "%DESKTOP%\src-tauri\target\release\interlink-web.exe" "%HERE%out\interlink-web.exe" >nul
if errorlevel 1 goto :failed
popd

echo.
echo Built: %HERE%out\interlink-web.exe
echo.
echo Run it, then open the http://127.0.0.1:27350/?t=... link it prints.
echo The UI appears in your browser and pairs with the phone exactly like the
echo desktop app. Only one InterLink laptop program can own the phone link
echo (TCP/UDP 27341) at a time, so close the desktop app first.
echo.
pause
exit /b 0

:failed
echo.
echo [ERROR] The build failed. The messages above name the missing piece.
echo.
pause
exit /b 1
