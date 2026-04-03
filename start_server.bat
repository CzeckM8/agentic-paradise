@echo off
setlocal EnableExtensions

set "ROOT=%~dp0"
set "ENV_FILE=%ROOT%.env"
set "SERVER_DIR=%ROOT%smallville"
set "SERVER_PORT=8080"

cd /d "%SERVER_DIR%" || (
    echo Failed to open server directory: %SERVER_DIR%
    pause
    exit /b 1
)

REM Load .env variables if present.
if exist "%ENV_FILE%" (
    echo Loading configuration from .env...
    for /f "usebackq eol=# tokens=1,* delims==" %%A in ("%ENV_FILE%") do (
        if /i "%%~A"=="GOOGLE_AI_API_KEY" set "GOOGLE_AI_API_KEY=%%~B"
        if /i "%%~A"=="LLM_PROVIDER" set "LLM_PROVIDER=%%~B"
        if /i "%%~A"=="LLM_MODEL" set "LLM_MODEL=%%~B"
        if /i "%%~A"=="SERVER_PORT" set "SERVER_PORT=%%~B"
    )
)

echo Checking for stale process on port %SERVER_PORT%...
for /f "tokens=5" %%a in ('netstat -ano 2^>nul ^| findstr ":%SERVER_PORT% "') do (
    echo Killing stale PID %%a on port %SERVER_PORT%
    taskkill /PID %%a /F >nul 2>&1
)

if not exist logs mkdir logs
for /f %%i in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd_HHmmss"') do set LOGSTAMP=%%i
set "LOGFILE=logs\runtime_%LOGSTAMP%.log"
set "LATESTLOG=logs\runtime_latest.log"

set "JAVA_OPTS="
if /i "%LLM_PROVIDER%"=="google_ai" (
    if defined GOOGLE_AI_API_KEY set "JAVA_OPTS=-Dgoogleai.api.key=%GOOGLE_AI_API_KEY% -Dllm.provider=google_ai"
)
if defined LLM_MODEL (
    set "JAVA_OPTS=%JAVA_OPTS% -Dllm.model=%LLM_MODEL%"
)

echo Starting Smallville on port %SERVER_PORT%...
break > "%LATESTLOG%"
java %JAVA_OPTS% -jar "target\smallville-1.3.0.jar" --api-key o --port %SERVER_PORT% >> "%LATESTLOG%" 2>&1
copy /Y "%LATESTLOG%" "%LOGFILE%" >nul

echo.
echo Logs: %LATESTLOG%
echo Server exited with code %ERRORLEVEL%.
pause