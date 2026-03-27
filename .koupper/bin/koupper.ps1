# Forced UTF-8 encoding
$OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$octopusJar = "$env:USERPROFILE\.koupper\libs\octopus.jar"
$cliJar     = "$env:USERPROFILE\.koupper\libs\koupper-cli.jar"

# 1. Daemon check - DO NOT USE Test-NetConnection (causes terminal cursor corruption via progress bars)
$octopusRunning = $false
try {
    $client = New-Object System.Net.Sockets.TcpClient
    $client.Connect("localhost", 9998)
    $client.Close()
    $octopusRunning = $true
} catch {
    $octopusRunning = $false
}

if (-not $octopusRunning) {
    Write-Host "🐙 Octopus Engine is offline. Booting background daemon..." -ForegroundColor Magenta
    # Use javaw and Hidden window to ensure it's fully detached
    Start-Process -FilePath "javaw" -ArgumentList "-jar `"$octopusJar`"" -WindowStyle Hidden
    Start-Sleep -Seconds 2
}

# 2. CLI Execution (Synchronous)
& java "-Dfile.encoding=UTF-8" -jar "$cliJar" $args