@echo off
setlocal enabledelayedexpansion

chcp 65001 >nul

set "KOUPPER_JAR=%userprofile%\.koupper\libs\koupper-cli-4.5.0.jar"
set "OCTOPUS_JAR=%userprofile%\.koupper\libs\octopus-6.1.0.jar"

:: logs (opcional, por si quieres seguir guardando cosas)
set "LOG_DIR=%userprofile%\.koupper\logs"
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"

:: ==============================
:: Ensure Octopus is running (9998)
:: ==============================
powershell -Command "$c = New-Object System.Net.Sockets.TcpClient; try { $c.Connect('localhost', 9998); $c.Close(); exit 0 } catch { exit 1 }"
if %ERRORLEVEL% neq 0 (
    echo Iniciando Octopus...
    powershell -Command "Start-Process -NoNewWindow -FilePath 'cmd' -ArgumentList '/c', '%userprofile%\.koupper\bin\start-octopus.bat'"
    timeout /t 2 >nul
)

:: ==============================
:: Run Koupper CLI in this console (so prompts work)
:: ==============================
java -Dfile.encoding=UTF-8 -jar "%KOUPPER_JAR%" %*
exit /b %errorlevel%