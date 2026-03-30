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
    $output = & $Command 2>&1
    if ($output) {
        $output | ForEach-Object { Write-Host $_ }
    }

    $outputText = ($output | Out-String)

    if ($LASTEXITCODE -ne 0) {
        throw "Step failed ($Label) with exit code $LASTEXITCODE"
    }

    if ($outputText -match "InvocationTargetException|Script error:|No function annotated with @Export was found\.") {
        throw "Step failed ($Label) due to runtime error output."
    }

    return $outputText
}

function Assert-OutputContains {
    param(
        [string]$Output,
        [string]$Needle,
        [string]$Description
    )

    if ($Output -notmatch [Regex]::Escape($Needle)) {
        throw "Expected output to contain '$Needle' for $Description"
    }
}

function Assert-OutputNotContains {
    param(
        [string]$Output,
        [string]$Needle,
        [string]$Description
    )

    if ($Output -match [Regex]::Escape($Needle)) {
        throw "Output unexpectedly contained '$Needle' for $Description"
    }
}

$scriptPath = $MyInvocation.MyCommand.Path
$examplesDir = Split-Path -Parent $scriptPath
$repoRoot = Split-Path -Parent $examplesDir
$workspace = Join-Path $examplesDir ".smoke-workspace"
$isWindowsOs = [System.Runtime.InteropServices.RuntimeInformation]::IsOSPlatform([System.Runtime.InteropServices.OSPlatform]::Windows)

$standaloneScript = Join-Path $examplesDir "smoke-standalone.kts"
$standaloneInputJson = Join-Path $examplesDir "smoke-standalone.input.json"
$inlineReportJson = Join-Path $examplesDir "smoke-report-inline.input.json"
$extDir = Join-Path $workspace "extensions"
$moduleScript = "smoke-script"
$moduleJobs = "smoke-jobs"
$modulePipe = "smoke-pipeline"
$jobWorkerScript = "queued-worker.kts"

function Assert-PathExists {
    param(
        [string]$Path,
        [string]$Description
    )

    if (-not (Test-Path $Path)) {
        throw "Missing expected $Description at: $Path"
    }
}

function Assert-ModuleScaffold {
    param([string]$ModuleName)

    $moduleRoot = Join-Path $workspace $ModuleName
    Assert-PathExists -Path $moduleRoot -Description "module directory"
    Assert-PathExists -Path (Join-Path $moduleRoot "settings.gradle") -Description "settings.gradle"
    Assert-PathExists -Path (Join-Path $moduleRoot "build.gradle") -Description "build.gradle"
}

function Cleanup-SmokeArtifacts {
    Write-Step "Cleaning smoke artifacts"
    if (Test-Path $workspace) { Remove-Item -Recurse -Force $workspace }
    if (Test-Path $standaloneScript) { Remove-Item -Force $standaloneScript }
    if (Test-Path $standaloneInputJson) { Remove-Item -Force $standaloneInputJson }
    if (Test-Path $inlineReportJson) { Remove-Item -Force $inlineReportJson }
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

    $shim = $null
    if (-not [string]::IsNullOrWhiteSpace($env:USERPROFILE)) {
        $shim = Join-Path $env:USERPROFILE ".koupper\bin\koupper.ps1"
    }

    if ($shim -and (Test-Path $shim)) {
        & powershell -NoProfile -ExecutionPolicy Bypass -File $shim @Args
        return
    }

    $homeDir = if (-not [string]::IsNullOrWhiteSpace($env:HOME)) { $env:HOME } else { $HOME }
    if ([string]::IsNullOrWhiteSpace($homeDir)) {
        throw "Unable to resolve HOME directory to locate koupper shim"
    }

    $unixShim = Join-Path $homeDir ".koupper/bin/koupper"
    if (Test-Path $unixShim) {
        & $unixShim @Args
        return
    }

    throw "koupper command not found and no shim found in ~/.koupper/bin"
}

Push-Location $repoRoot
try {
    Write-Step "Ensure local installation artifacts"
    & { kotlinc -script install.kts }

    if ($LASTEXITCODE -ne 0) {
        Write-Host "Install failed, retrying with force mode..." -ForegroundColor Yellow
        if ($isWindowsOs) {
            Stop-KoupperProcesses
        }
        & { kotlinc -script install.kts -- --force }
        if ($LASTEXITCODE -ne 0) {
            throw "Step failed (Ensure local installation artifacts) with exit code $LASTEXITCODE"
        }
    }

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

    @'
{"payload":"from-smoke"}
'@ | Set-Content -Path $standaloneInputJson -Encoding ASCII

    @'
{"reportName":"Smoke","region":"Global","items":[{"name":"License","value":99.0},{"name":"Support","value":15.0}]}
'@ | Set-Content -Path $inlineReportJson -Encoding ASCII

    Run-External "Create standalone script in examples" { Invoke-Koupper new examples/smoke-standalone.kts }
    Run-External "Run standalone script with json payload" { Invoke-Koupper run examples/smoke-standalone.kts --json-file examples/smoke-standalone.input.json }
    Run-External "Run hello-world" { Invoke-Koupper run examples/hello-world.kts "Smoke" }
    Run-External "Run report generator (json-file mode)" { Invoke-Koupper run examples/cli-report-generator.kts --json-file examples/cli-report-generator.input.json }
    Run-External "Run report generator (inline payload file)" { Invoke-Koupper run examples/cli-report-generator.kts --json-file examples/smoke-report-inline.input.json }

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
'@ | Set-Content -Path (Join-Path $extDir "sample.kts") -Encoding ASCII

    @'
package %PACKAGE%

import com.koupper.octopus.annotations.Export

@Export
val extraScript: () -> String = {
    "extra"
}
'@ | Set-Content -Path (Join-Path $extDir "extra.kt") -Encoding ASCII

    Push-Location $workspace
    try {
        Run-External "Generate script module" { Invoke-Koupper new module name="smoke-script",version="1.0.0",package="smoke.script",template="default" }
        Run-External "Validate script module scaffold" { Assert-ModuleScaffold -ModuleName $moduleScript }
        Run-External "Inspect script module" { Invoke-Koupper module $moduleScript }
        Run-External "Add scripts inclusive" { Invoke-Koupper module add-scripts name="smoke-script" --script-inclusive "extensions/sample.kts" }
        Run-External "Add scripts exclusive with overwrite" { Invoke-Koupper module add-scripts name="smoke-script" --script-exclusive "extensions/sample.kts" --overwrite }
        Run-External "Add scripts wildcard inclusive" { Invoke-Koupper module add-scripts name="smoke-script" --script-wildcard-inclusive "extensions/*" }
        Run-External "Add scripts wildcard exclusive with overwrite" { Invoke-Koupper module add-scripts name="smoke-script" --script-wildcard-exclusive "extensions/*" --overwrite }

        Run-External "Generate jobs module" { Invoke-Koupper new module name="smoke-jobs",version="1.0.0",package="smoke.jobs",template="jobs" }
        Run-External "Validate jobs module scaffold" { Assert-ModuleScaffold -ModuleName $moduleJobs }
        Run-External "Inspect jobs module" { Invoke-Koupper module $moduleJobs }

        Push-Location (Join-Path $workspace $moduleJobs)
        try {
            Run-External "Initialize jobs config" { Invoke-Koupper job init --force }

            @'
import com.koupper.octopus.annotations.Export
data class WorkerInput(val payload: String?)

@Export
val worker: (WorkerInput) -> String = { input ->
    println("Processed payload: ${input.payload}")
    "processed"
}
'@ | Set-Content -Path $jobWorkerScript -Encoding ASCII

            $moduleContext = (Get-Location).Path
            $jobsQueueDir = Join-Path $moduleContext "jobs/default"
            New-Item -ItemType Directory -Force -Path $jobsQueueDir | Out-Null

            $scriptPath = (Join-Path $moduleContext $jobWorkerScript).Replace("\\", "/")
            $contextPath = $moduleContext.Replace("\\", "/")

            $manualJob = [ordered]@{
                id = "smoke-job-1"
                fileName = "queued-worker.kts"
                functionName = "worker"
                params = @{ arg0 = '{"payload":"smoke-job-1"}' }
                scriptPath = $scriptPath
                origin = "smoke-suite"
                context = $contextPath
                sourceType = "script"
            } | ConvertTo-Json -Depth 10

            Set-Content -Path (Join-Path $jobsQueueDir "smoke-job-1.json") -Value $manualJob -Encoding ASCII

            Run-External "Seed one smoke job in local-file queue" { Get-Item (Join-Path $jobsQueueDir "smoke-job-1.json") | Out-Null }
            $listBeforeRun = Run-External "List jobs by config (should show pending job)" { Invoke-Koupper job list --configId=local-file }
            Assert-OutputNotContains -Output $listBeforeRun -Needle "No jobs found" -Description "job listing before worker execution"
            Assert-OutputNotContains -Output $listBeforeRun -Needle "Failed to read job" -Description "manual queued job parsing"

            $workerRunOutput = Run-External "Run job worker" { Invoke-Koupper job run-worker --configId=local-file }
            Assert-OutputNotContains -Output $workerRunOutput -Needle "Failed to execute job" -Description "worker execution"

            $listAfterRun = Run-External "List jobs by config after worker" { Invoke-Koupper job list --configId=local-file }
            Assert-OutputNotContains -Output $listAfterRun -Needle "Failed to read job" -Description "post-worker queue state"
            if ($listAfterRun -match "Job ID:") {
                throw "Expected queue to be empty after worker execution"
            }

            Run-External "Show job status" { Invoke-Koupper job status --configId=local-file }
        } finally {
            Pop-Location
        }

        Run-External "Generate pipeline module" { Invoke-Koupper new module name="smoke-pipeline",version="1.0.0",package="smoke.pipeline",template="pipelines" }
        Run-External "Validate pipeline module scaffold" { Assert-ModuleScaffold -ModuleName $modulePipe }
        Run-External "Inspect pipeline module" { Invoke-Koupper module $modulePipe }
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
