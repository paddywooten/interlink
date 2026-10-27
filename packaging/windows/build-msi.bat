@echo off
setlocal EnableExtensions
rem ===========================================================================
rem  One-click InterLink build for Windows: produces InterLink_*.msi
rem
rem  Double-click this file. It checks the toolchain, installs the frontend
rem  dependencies, builds the app and leaves the installer in
rem  packaging\windows\out\ (and opens that folder for you).
rem
rem  One-time prerequisites: Rust from https://rustup.rs, Node.js 20+ from
rem  https://nodejs.org, WebView2 runtime (preinstalled on Windows 11) - see
rem  packaging\windows\README.md.
rem ===========================================================================

set "HERE=%~dp0"
set "ROOT=%HERE%..\.."
set "OUT=%HERE%out"
rem rustup installs cargo here; a double-clicked window may predate that PATH
set "PATH=%USERPROFILE%\.cargo\bin;%PATH%"

echo.
echo [1/4] Checking prerequisites

where node >nul 2>nul
if errorlevel 1 goto :no_node

where npm >nul 2>nul
if errorlevel 1 goto :no_node

where cargo >nul 2>nul
if errorlevel 1 goto :no_cargo

echo.
echo [2/4] Installing frontend dependencies
pushd "%ROOT%"
if errorlevel 1 goto :no_root
call npm install --no-audit --no-fund
if errorlevel 1 goto :build_failed

echo.
echo [3/4] Building the app and the MSI
echo        (the first run compiles Rust in release mode - several minutes)
call npm run tauri build -- --bundles msi
if errorlevel 1 goto :build_failed
popd

echo.
echo [4/4] Collecting the installer
if not exist "%OUT%" mkdir "%OUT%"
del /q "%OUT%\*.msi" 2>nul
copy /y "%ROOT%\desktop\src-tauri\target\release\bundle\msi\*.msi" "%OUT%" >nul
if errorlevel 1 goto :no_msi

for %%F in ("%OUT%\*.msi") do echo        %%~nxF

echo.
echo Done. Installer folder:
echo        %OUT%
explorer "%OUT%"
echo.
pause
exit /b 0

:no_node
echo.
echo [ERROR] Node.js 20+ was not found on PATH.
echo         Install the LTS from https://nodejs.org, then double-click this file again.
goto :end

:no_cargo
echo.
echo [ERROR] Rust was not found on PATH.
echo         Install it from https://rustup.rs with the default
echo         x86_64-pc-windows-msvc toolchain, then double-click this file again.
goto :end

:no_root
echo.
echo [ERROR] Could not open the repository folder:
echo        %ROOT%
goto :end

:build_failed
popd
echo.
echo [ERROR] The build failed - see the output above.
goto :end

:no_msi
echo.
echo [ERROR] The build reported success but no .msi was produced.
echo         Look in desktop\src-tauri\target\release\bundle\msi\
goto :end

:end
echo.
pause
exit /b 1
