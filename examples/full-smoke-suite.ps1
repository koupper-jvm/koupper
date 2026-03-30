param(
    [switch]$KeepArtifacts,
    [switch]$ForceInstall
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Write-Step($message) {
    Write-Host "`n==> $message" -ForegroundColor Cyan
}

function Run-External {
    param(
        [string]$Label,
        [scriptblock]$Command
    )

    Write-Step $Label
    & $Command
    if ($LASTEXITCODE -ne 0) {
        throw "Step failed ($Label) with exit code $LASTEXITCODE"
    }
}

$scriptPath = $MyInvocation.MyCommand.Path
$examplesDir = Split-Path -Parent $scriptPath
$repoRoot = Split-Path -Parent $examplesDir
$workspace = Join-Path $examplesDir ".smoke-workspace"
$isWindowsOs = [System.Runtime.InteropServices.RuntimeInformation]::IsOSPlatform([System.Runtime.InteropServices.OSPlatform]::Windows)

$standaloneScript = Join-Path $examplesDir "smoke-standalone.kts"
$extDir = Join-Path $workspace "extensions"
$moduleScript = "smoke-script"
$moduleJobs = "smoke-jobs"
$modulePipe = "smoke-pipeline"

function Cleanup-SmokeArtifacts {
    Write-Step "Cleaning smoke artifacts"
    if (Test-Path $workspace) { Remove-Item -Recurse -Force $workspace }
    if (Test-Path $standaloneScript) { Remove-Item -Force $standaloneScript }
}

function Stop-KoupperProcesses {
    $procs = Get-CimInstance Win32_Process |
        Where-Object {
            ($_.CommandLine -like "*\.koupper\libs\octopus.jar*") -or
            ($_.CommandLine -like "*\.koupper\libs\koupper-cli.jar*")
        }

    foreach ($p in $procs) {
        Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
    }
}

function Invoke-Koupper {
    param(
        [Parameter(ValueFromRemainingArguments = $true)]
        [string[]]$Args
    )

    $cmd = Get-Command koupper -ErrorAction SilentlyContinue
    if ($cmd) {
        & koupper @Args
        return
    }

    $shim = Join-Path $env:USERPROFILE ".koupper\bin\koupper.ps1"
    if (-not (Test-Path $shim)) {
        throw "koupper command not found and shim missing at $shim"
    }

    & powershell -NoProfile -ExecutionPolicy Bypass -File $shim @Args
}

Push-Location $repoRoot
try {
    Run-External "Ensure local installation artifacts" { kotlinc -script install.kts }

    if ($ForceInstall) {
        Run-External "Stop running Koupper processes" { Stop-KoupperProcesses }
        Run-External "Refresh local installation (force)" { kotlinc -script install.kts -- --force }
    }
    Run-External "Check CLI availability" { Invoke-Koupper help }
    Run-External "Run install doctor" { kotlinc -script install.kts -- --doctor }

    Run-External "Show help: new" { Invoke-Koupper help new }
    Run-External "Show help: run" { Invoke-Koupper help run }
    Run-External "Show help: module" { Invoke-Koupper help module }
    Run-External "Show help: job" { Invoke-Koupper help job }

    Run-External "Create standalone script in examples" { Invoke-Koupper new examples/smoke-standalone.kts }
    Run-External "Run standalone script with json payload" { Invoke-Koupper run examples/smoke-standalone.kts '{"payload":"from-smoke"}' }
    Run-External "Run hello-world" { Invoke-Koupper run examples/hello-world.kts "Smoke" }
    Run-External "Run report generator (json-file mode)" { Invoke-Koupper run examples/cli-report-generator.kts --json-file examples/cli-report-generator.input.json }
    Run-External "Run report generator (inline json mode)" { Invoke-Koupper run examples/cli-report-generator.kts '{"reportName":"Smoke","region":"Global","items":[{"name":"License","value":99.0},{"name":"Support","value":15.0}]}' }

    if (Test-Path $workspace) { Remove-Item -Recurse -Force $workspace }
    New-Item -ItemType Directory -Path $workspace | Out-Null
    New-Item -ItemType Directory -Path $extDir | Out-Null

    @'
package %PACKAGE%

import com.koupper.octopus.annotations.Export

@Export
val sampleScript: () -> String = {
    "sample"
}
'@ | Set-Content -Path (Join-Path $extDir "sample.kts") -Encoding UTF8

    @'
package %PACKAGE%

import com.koupper.octopus.annotations.Export

@Export
val extraScript: () -> String = {
    "extra"
}
'@ | Set-Content -Path (Join-Path $extDir "extra.kt") -Encoding UTF8

    Push-Location $workspace
    try {
        Run-External "Generate script module" { Invoke-Koupper new module name="smoke-script",version="1.0.0",package="smoke.script",template="default" }
        Run-External "Inspect script module" { Invoke-Koupper module $moduleScript }
        Run-External "Add scripts inclusive" { Invoke-Koupper module add-scripts name="smoke-script" --script-inclusive "extensions/sample.kts" }
        Run-External "Add scripts exclusive with overwrite" { Invoke-Koupper module add-scripts name="smoke-script" --script-exclusive "extensions/sample.kts" --overwrite }
        Run-External "Add scripts wildcard inclusive" { Invoke-Koupper module add-scripts name="smoke-script" --script-wildcard-inclusive "extensions/*" }
        Run-External "Add scripts wildcard exclusive with overwrite" { Invoke-Koupper module add-scripts name="smoke-script" --script-wildcard-exclusive "extensions/*" --overwrite }

        Push-Location (Join-Path $workspace $moduleScript)
        try {
            Run-External "Build script module" { if ($isWindowsOs) { .\gradlew.bat build -x test } else { ./gradlew build -x test } }
        } finally {
            Pop-Location
        }

        Run-External "Generate jobs module" { Invoke-Koupper new module name="smoke-jobs",version="1.0.0",package="smoke.jobs",template="jobs" }
        Run-External "Inspect jobs module" { Invoke-Koupper module $moduleJobs }

        Push-Location (Join-Path $workspace $moduleJobs)
        try {
            Run-External "Initialize jobs config" { Invoke-Koupper job init --force }
            Run-External "Build jobs environment" { Invoke-Koupper job build-environment }
            Run-External "Run jobs module main (enqueue task)" { if ($isWindowsOs) { .\gradlew.bat run } else { ./gradlew run } }
            Run-External "List jobs by config" { Invoke-Koupper job list --configId=local-file }
            Run-External "Run job worker" { Invoke-Koupper job run-worker --configId=local-file }
            Run-External "Show job status" { Invoke-Koupper job status --configId=local-file }
        } finally {
            Pop-Location
        }

        Run-External "Generate pipeline module" { Invoke-Koupper new module name="smoke-pipeline",version="1.0.0",package="smoke.pipeline",template="pipelines" }
        Run-External "Inspect pipeline module" { Invoke-Koupper module $modulePipe }

        Push-Location (Join-Path $workspace $modulePipe)
        try {
            Run-External "Build pipeline module" { if ($isWindowsOs) { .\gradlew.bat build -x test } else { ./gradlew build -x test } }
            Run-External "Run pipeline module main" { if ($isWindowsOs) { .\gradlew.bat run } else { ./gradlew run } }
        } finally {
            Pop-Location
        }
    } finally {
        Pop-Location
    }

    Write-Host "`nSmoke suite completed successfully." -ForegroundColor Green
}
finally {
    Pop-Location
    if (-not $KeepArtifacts) {
        Cleanup-SmokeArtifacts
    }
}
