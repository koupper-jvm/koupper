# Configurar el entorno para UTF-8
$OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

# Variables de entorno para Java
$env:JAVA_TOOL_OPTIONS = "-Dfile.encoding=UTF-8"
$libsDir = "$env:USERPROFILE\.koupper\libs"
$logFile = "$env:USERPROFILE\.koupper\logs\octopus.log"

Set-Location $libsDir

# Lanzamos Octopus usando el puente de CMD para asegurar que el redireccionamiento 
# de logs (>>) no corrompa los caracteres especiales.
cmd /c "chcp 65001 >nul && java -Dfile.encoding=UTF-8 -cp * com.koupper.octopus.OctopusKt >> `"$logFile`" 2>&1"