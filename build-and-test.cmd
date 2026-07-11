@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"

call mvn.cmd -q clean test package
if errorlevel 1 exit /b 1

set "JAVA_LAUNCHER=java"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_LAUNCHER=%JAVA_HOME%\bin\java.exe"
"%JAVA_LAUNCHER%" -ea -cp "target\test-classes;target\SimpleRAG-1.0-SNAPSHOT.jar" com.simplerag.search.SemanticSearchEngineTest
if errorlevel 1 exit /b 1

"%JAVA_LAUNCHER%" -ea -cp "target\test-classes;target\SimpleRAG-1.0-SNAPSHOT.jar" com.simplerag.service.CourseFeaturesTest
if errorlevel 1 exit /b 1
endlocal
