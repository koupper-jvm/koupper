@echo off
setlocal enabledelayedexpansion

chcp 65001 >nul

set JAR_PATH=%userprofile%\.koupper\libs\koupper-cli-4.5.0.jar
set OCTOPUS_PATH=%userprofile%\.koupper\libs\octopus-5.0.0.jar

set JAR_PATH=%JAR_PATH:\=\\%
set OCTOPUS_PATH=%OCTOPUS_PATH:\=\\%

:: Definir rutas de los archivos de log
set KOUPPER_LOG=%userprofile%\.koupper\logs\koupper-cli.log
set OCTOPUS_LOG=%userprofile%\.koupper\logs\octopus.log

:: Crear directorio de logs si no existe
if not exist "%userprofile%\.koupper\logs" mkdir "%userprofile%\.koupper\logs"

:: ==============================
:: Koupper CLI
:: ==============================
wmic process where "name='javaw.exe'" get CommandLine | findstr /I /C:"%JAR_PATH%" >nul
if %ERRORLEVEL% neq 0 (
    echo Iniciando Koupper CLI...
    powershell -Command "Start-Process -NoNewWindow -FilePath 'javaw' -ArgumentList '-Dfile.encoding=UTF-8', '-jar', '%userprofile%\.koupper\libs\koupper-cli-4.5.0.jar' -RedirectStandardOutput '%KOUPPER_LOG%' -RedirectStandardError '%userprofile%\.koupper\logs\koupper-cli-error.log'"
    timeout /t 5 >nul
) 

:: Verificar si Octopus está corriendo en el puerto 9998
powershell -Command "$socket = New-Object System.Net.Sockets.TcpClient; try { $socket.Connect('localhost', 9998); $socket.Close(); exit 0 } catch { exit 1 }"
if %ERRORLEVEL% neq 0 (    
    echo Iniciando Octopus...
    powershell -Command "Start-Process -NoNewWindow -FilePath 'cmd' -ArgumentList '/c', '%userprofile%\.koupper\bin\start-octopus.bat'"
    timeout /t 5 >nul
)

:: Enviar el comando exactamente como estaba antes
powershell -Command ^
    "$socket = New-Object System.Net.Sockets.TcpClient; " ^
    "try { " ^
        "$socket.Connect('localhost', 9999); " ^
    "} catch { " ^
        "Write-Output 'Error: No se pudo conectar al servidor de sockets'; " ^
        "exit 1; " ^
    "} " ^
    "$stream = $socket.GetStream(); " ^
    "$writer = New-Object System.IO.StreamWriter($stream); " ^
    "$reader = New-Object System.IO.StreamReader($stream); " ^
    "$writer.AutoFlush = $true; " ^
    "$writer.WriteLine('%CD% %*'); " ^
    "$response = $reader.ReadToEnd(); " ^
    "$writer.Close(); " ^
    "$socket.Close(); " ^
    "if (-not [string]::IsNullOrEmpty($response)) { " ^
        "Write-Output $response; " ^
    "}"