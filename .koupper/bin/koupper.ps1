# Forzar encoding en la sesión actual de PowerShell
$OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$koupperJar = "$env:USERPROFILE\.koupper\libs\koupper-cli-4.5.0.jar"
$logDir = "$env:USERPROFILE\.koupper\logs"

# Crear directorio de logs si no existe
if (-not (Test-Path $logDir)) { 
    New-Item -ItemType Directory -Path $logDir | Out-Null 
}

# Verificar si Octopus (puerto 9998) ya está corriendo
$octopusRunning = $false
try {
    $client = New-Object System.Net.Sockets.TcpClient
    $client.Connect("localhost", 9998)
    $client.Close()
    $octopusRunning = $true
} catch {
    $octopusRunning = $false
}

# Si no corre, lo iniciamos usando el script start-octopus.ps1
if (-not $octopusRunning) {
    Write-Host "Iniciando Octopus..." -ForegroundColor Cyan
    Start-Process -NoNewWindow -FilePath "powershell" -ArgumentList "-File `"$env:USERPROFILE\.koupper\bin\start-octopus.ps1`""
    Start-Sleep -Seconds 2
}

# EJECUCIÓN MAESTRA: 
# Usamos cmd /c chcp 65001 para que la consola de Windows entienda UTF-8 
# y Java no convierta los emojis en signos de interrogación.
cmd /c "chcp 65001 >nul && java -Dfile.encoding=UTF-8 -jar `"$koupperJar`" $args"