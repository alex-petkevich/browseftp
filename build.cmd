@echo off
REM ===========================================================================
REM Convenience wrapper around the Maven Wrapper.
REM
REM Builds the runnable Spring Boot jar (backend + embedded Angular UI):
REM   target\ftpclient-1.0.0-alpha.jar
REM
REM Any extra arguments are forwarded to Maven, so you can do e.g.:
REM   build.cmd -DskipTests
REM   build.cmd -Pbuild-frontend
REM ===========================================================================

setlocal

cd /d "%~dp0"

if "%JAVA_HOME%"=="" (
    echo [WARN] JAVA_HOME is not set. Using whatever 'java' is on PATH.
) else (
    echo Using JAVA_HOME=%JAVA_HOME%
)

call mvnw.cmd clean package %*

endlocal

