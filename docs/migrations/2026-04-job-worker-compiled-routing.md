# Migration: Job Worker Compiled Routing Fix

**Date:** 2026-04-10
**Branch:** `fix/job-worker-compiled-routing`
**Affects:** `koupper job run-worker`

## Problem

Workers processing SQS (or file/Redis) jobs with `sourceType: "compiled"` in the task payload
would fail with a `FileNotFoundException` because `runPendingJobs()` always routed execution
through the script-file path regardless of `sourceType`. The compiled runner (`JobRunner.runCompiled`)
existed but was never called from the CLI worker path.

Additionally, `SqsJobDriver.forEachPending()` deleted the SQS message immediately after
deserializing it — before execution. A failure during execution would permanently lose the job.

## Changes

### `JobResult.Ok` — new optional fields (non-breaking)

```kotlin
data class Ok(
    val configName: String?,
    val task: KouTask,
    val ackFn: (() -> Unit)? = null,    // invoke on success to permanently remove the job
    val releaseFn: (() -> Unit)? = null  // invoke on failure to allow retry (no-op for SQS)
)
```

Existing code that constructs `JobResult.Ok(configName, task)` is unaffected.

### `JobRunner.runPendingJobs()` — sourceType routing

The mapping loop now branches on `task.sourceType`:

- `"compiled"` → `JobRunner.runCompiled(taskContext, task)`
- anything else → existing script-file path (unchanged behavior)

Both branches emit a log line: `[job-worker] branch=compiled|script job=<id>`.
On failure, `releaseFn` is called before returning the error. On success, `ackFn` is called.

### `SqsJobDriver.forEachPending()` — deferred ack

The `deleteMessage` call was moved out of the driver and into the `ackFn` lambda returned in
each `JobResult.Ok`. The SQS client is kept open for the duration of the batch so lambdas
can still reference it. The `releaseFn` is a no-op — when it is invoked (on failure), the
message is not deleted and becomes visible again after the visibility timeout, enabling retry.

## Upgrade Steps

No changes required for consumers. The fix is transparent:

- Jobs with `sourceType: "compiled"` in the task JSON now route to the compiled runner.
- Jobs with `sourceType: "script"` (the default) behave identically to before.
- SQS jobs that fail execution are no longer silently lost.

## Testing

See `orchestrator-core/src/test/kotlin/com/koupper/orchestrator/JobRunnerTest.kt`:

- `script branch returns error when script metadata is missing`
- `compiled branch calls runCompiled and does not invoke runScriptContent`
- `ackFn is invoked after successful script execution`
- `releaseFn is invoked when script execution throws`
- `releaseFn is invoked when script path is missing`
- `ackFn not called when execution fails`
- `JobResult Ok can be constructed without ackFn and releaseFn`
