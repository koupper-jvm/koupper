# Forced UTF-8 encoding for PowerShell streams
$OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

# Force the native Windows Console code page to UTF-8
chcp 65001 > $null

$octopusJar = "$env:USERPROFILE\.koupper\libs\octopus.jar"
$cliJar     = "$env:USERPROFILE\.koupper\libs\koupper-cli.jar"

# 1. Daemon check - DO NOT USE Test-NetConnection
$octopusRunning = $false
try {
    $client = [System.Net.Sockets.TcpClient]::new()
    $client.Connect("localhost", 9998)
    $client.Close()
    $octopusRunning = $true
} catch {
    $octopusRunning = $false
}

if (-not $octopusRunning) {
    Write-Host "🐙 Octopus Engine is offline. Booting background daemon..." -ForegroundColor Magenta
    Start-Process -FilePath "javaw" -ArgumentList "-jar `"$octopusJar`"" -WindowStyle Hidden
    Start-Sleep -Seconds 2
}

# 2. CLI Execution
# We pipe to Out-Default to force PowerShell's PSReadLine to track the output lines natively.
# If we do not do this, native executables that cause terminal scrolling will desync PSReadLine,
# causing the prompt to jump up and overwrite the top of the output.
& java "-Dfile.encoding=UTF-8" -jar "$cliJar" $args | Out-Default