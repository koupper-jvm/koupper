# CLI Command Checklist

This checklist is versioned and intended for manual validation on local machines before release.

Public-facing counterpart: `koupper-docs/docs/production/script-execution-checklist.md`.

## Prerequisites

```powershell
kotlinc -script install.kts -- --doctor
koupper help
```

## Core Help Surface

```powershell
koupper help
koupper help new
koupper help run
koupper help module
koupper help job
```

## Script Execution

```powershell
koupper new smoke-standalone.kts
koupper run smoke-standalone.kts --json-file examples/cli-report-generator.input.json
koupper run examples/hello-world.kts "Local Runner"
koupper run examples/cli-report-generator.kts --json-file examples/cli-report-generator.input.json
```

## Module Scaffolding + Script Imports

```powershell
koupper new module name="smoke-script",version="1.0.1",package="smoke.script",template="default"
koupper module smoke-script
koupper module add-scripts name="smoke-script" --script-inclusive "extensions/sample.kts"
koupper module add-scripts name="smoke-script" --script-exclusive "extensions/sample.kts" --overwrite
koupper module add-scripts name="smoke-script" --script-wildcard-inclusive "extensions/*"
koupper module add-scripts name="smoke-script" --script-wildcard-exclusive "extensions/*" --overwrite
```

## Job Lifecycle (Queue -> List -> Execute -> Empty)

```powershell
koupper new module name="smoke-jobs",version="2.0.0",package="smoke.jobs",template="jobs"
koupper module smoke-jobs
cd .\smoke-jobs\
koupper job init --force

# Seed one file-driver job (same flow used by full smoke suite)
@'
import com.koupper.octopus.annotations.Export
data class WorkerInput(val payload: String?)
@Export
val worker: (WorkerInput) -> String = { input ->
    println("Processed payload: ${input.payload}")
    "processed"
}
'@ | Set-Content -Path queued-worker.kts -Encoding ASCII

New-Item -ItemType Directory -Force -Path .\jobs\default | Out-Null

$manualJob = [ordered]@{
  id = "smoke-job-1"
  fileName = "queued-worker.kts"
  functionName = "worker"
  params = @{ arg0 = '{"payload":"smoke-job-1"}' }
  scriptPath = (Join-Path (Get-Location).Path "queued-worker.kts").Replace("\\", "/")
  origin = "checklist"
  context = (Get-Location).Path.Replace("\\", "/")
  sourceType = "script"
} | ConvertTo-Json -Depth 10

Set-Content -Path .\jobs\default\smoke-job-1.json -Value $manualJob -Encoding ASCII

koupper job list --configId=local-file
koupper job run-worker --configId=local-file
koupper job list --configId=local-file
koupper job status --configId=local-file
cd ..
```

## Job Listener + Worker Logger Isolation

Use this to verify listener logs are still emitted even when worker jobs define their own `@Logger` destination.

```powershell
cd .\smoke-jobs\

@'
package tdn.jobs.extensions

import com.koupper.octopus.annotations.Export
import com.koupper.octopus.annotations.Logger
import com.koupper.logging.GlobalLogger.log

data class Input(val payload: String?)

@Export
@Logger(destination = "file:LOGFILE[yyyy-MM-dd]", level = "INFO")
val myScript: (Input) -> Unit = { input ->
    log.info { "WORKER payload=${input.payload}" }
}
'@ | Set-Content -Path .\extensions\myScript.kts -Encoding ASCII

@'
import com.koupper.octopus.annotations.Export
import com.koupper.octopus.annotations.Logger
import com.koupper.octopus.annotations.JobsListener
import com.koupper.octopus.process.JobEvent
import com.koupper.logging.GlobalLogger.log

@JobsListener(debug = true, configId = "job-callbacks")
@Export
@Logger(destination = "file:example[yyyy-MM-dd]", level = "INFO")
val jobsListener: (JobEvent) -> Int = { c ->
    log.info { "Procesando JobEvent con id=${c.jobId}" }
    200
}
'@ | Set-Content -Path .\extensions\jobsListener.kts -Encoding ASCII

# Trigger jobs and listener according to your module's standard flow, then verify both files contain output:
# - logs/LOGFILE.<date>.log
# - logs/example.<date>.log

cd ..
```

## Pipeline Module Scaffold

```powershell
koupper new module name="smoke-pipeline",version="3.1.0",package="smoke.pipeline",template="pipelines"
koupper module smoke-pipeline
```

## Automated Equivalent

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\examples\full-smoke-suite.ps1
```
