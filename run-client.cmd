@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"

if not exist "target\SimpleRAG-1.0-SNAPSHOT.jar" (
    echo Building SimpleRAG...
    call mvn.cmd -q -DskipTests package
    if errorlevel 1 exit /b 1
)

set "JAVA_LAUNCHER=javaw"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javaw.exe" set "JAVA_LAUNCHER=%JAVA_HOME%\bin\javaw.exe"
start "SimpleRAG" "%JAVA_LAUNCHER%" -jar "target\SimpleRAG-1.0-SNAPSHOT.jar"
endlocal
