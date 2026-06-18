@echo off
REM ===========================================================================
REM Rebuilds the Angular UI and copies it into the Spring Boot static resources.
REM
REM Requirements: Node.js 20+ and npm 10+ on PATH.
REM ===========================================================================

setlocal

cd /d "%~dp0"

echo === Installing dependencies ===
call npm install --no-audit --no-fund || goto :error

echo === Building Angular production bundle ===
call npm run build || goto :error

echo === Copying build output into ..\src\main\resources\static ===
if exist "..\src\main\resources\static" rmdir /s /q "..\src\main\resources\static"
mkdir "..\src\main\resources\static"
xcopy /e /i /y "dist\*" "..\src\main\resources\static\" || goto :error

echo.
echo Frontend build complete.
exit /b 0

:error
echo.
echo Frontend build FAILED.
exit /b 1

