@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"
if "%HF_ENDPOINT%"=="" set "HF_ENDPOINT=https://hf-mirror.com"

if not exist "target\SimpleRAG-1.0-SNAPSHOT.jar" (
    echo Building SimpleRAG...
    call mvn.cmd -q -DskipTests package
    if errorlevel 1 exit /b 1
)

set "JAVA_LAUNCHER=java"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_LAUNCHER=%JAVA_HOME%\bin\java.exe"

"%JAVA_LAUNCHER%" -cp "target\SimpleRAG-1.0-SNAPSHOT.jar" com.simplerag.embedding.ModelDownloader models\multilingual-minilm
if errorlevel 1 exit /b 1

echo.
echo Model installation completed. Rebuild the index in SimpleRAG.
pause
endlocal
