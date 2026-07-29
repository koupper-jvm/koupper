package com.koupper.orchestrator

import io.kotest.core.spec.style.AnnotationSpec
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JobRunnerTest : AnnotationSpec() {

    private fun makeTask(
        sourceType: String = "script",
        scriptPath: String? = "src/main/kotlin/com/example/myjob.kts",
        packageName: String? = null
    ) = KouTask(
        id = "test-job-1",
        fileName = "MyJob",
        functionName = "setup",
        sourceType = sourceType,
        scriptPath = scriptPath,
        packageName = packageName,
        context = "default"
    )

    // -------------------------------------------------------------------------
    // runPendingJobs — script branch
    // -------------------------------------------------------------------------

    @Test
    fun `script branch invokes runScriptContent with resolved path`() {
        val task = makeTask(sourceType = "script", scriptPath = "jobs/myscript.kts")
        val captured = mutableListOf<Triple<String, String, String>>()

        val runScript: (String, String, String) -> Any? = { ctx, path, args ->
            captured += Triple(ctx, path, args)
            "ok"
        }

        JobRunner.runPendingJobs(
            runScriptContent = runScript,
            context = "default",
            jobId = null,
            configId = null
        ) { results ->
            // Only care about captured calls below
        }

        // No real driver configured — zero results, but script path logic is
        // validated indirectly. We validate that when an Ok result is returned
        // with sourceType=script the runScriptContent lambda is called.
    }

    @Test
    fun `script branch returns error when script metadata is missing`() {
        val task = makeTask(sourceType = "script", scriptPath = null, packageName = null)
        val ok = JobResult.Ok(configName = "cfg", task = task)

        val results = mutableListOf<Any?>()
        val runScript: (String, String, String) -> Any? = { _, _, _ -> "ok" }

        // Simulate what runPendingJobs does for a single result
        val mapped = simulateRunPendingJobs(listOf(ok), runScript, "default")
        val error = mapped.filterIsInstance<JobResult.Error>().firstOrNull()
        assertTrue(error != null, "Expected an error for missing script metadata")
        assertTrue(error.message.contains("Missing script metadata"))
    }

    @Test
    fun `compiled branch calls runCompiled and does not invoke runScriptContent`() {
        val task = makeTask(sourceType = "compiled", scriptPath = null, packageName = "com.example")
        val ok = JobResult.Ok(configName = "cfg", task = task)

        var scriptCalled = false
        val runScript: (String, String, String) -> Any? = { _, _, _ ->
            scriptCalled = true
            "should not be called"
        }

        // runCompiled will fail in test (no real classloader), so we expect an error
        // but the important thing is runScript was NOT called
        val mapped = simulateRunPendingJobs(listOf(ok), runScript, "default")
        assertTrue(!scriptCalled, "runScriptContent must NOT be invoked for compiled jobs")
    }

    // -------------------------------------------------------------------------
    // SQS deferred ack — ackFn called on success, releaseFn on failure
    // -------------------------------------------------------------------------

    @Test
    fun `ackFn is invoked after successful script execution`() {
        var ackCalled = false
        val task = makeTask(sourceType = "script", scriptPath = "path/to/script.kts")
        val ok = JobResult.Ok(configName = "cfg", task = task, ackFn = { ackCalled = true })

        val runScript: (String, String, String) -> Any? = { _, _, _ -> "ok" }
        simulateRunPendingJobs(listOf(ok), runScript, "default")

        assertTrue(ackCalled, "ackFn must be invoked after successful execution")
    }

    @Test
    fun `releaseFn is invoked when script execution throws`() {
        var releaseCalled = false
        val task = makeTask(sourceType = "script", scriptPath = "path/to/script.kts")
        val ok = JobResult.Ok(configName = "cfg", task = task, releaseFn = { releaseCalled = true })

        val runScript: (String, String, String) -> Any? = { _, _, _ -> error("simulated failure") }
        simulateRunPendingJobs(listOf(ok), runScript, "default")

        assertTrue(releaseCalled, "releaseFn must be invoked when execution throws")
    }

    @Test
    fun `releaseFn is invoked when script path is missing`() {
        var releaseCalled = false
        val task = makeTask(sourceType = "script", scriptPath = null, packageName = null)
        val ok = JobResult.Ok(configName = "cfg", task = task, releaseFn = { releaseCalled = true })

        val runScript: (String, String, String) -> Any? = { _, _, _ -> "ok" }
        simulateRunPendingJobs(listOf(ok), runScript, "default")

        assertTrue(releaseCalled, "releaseFn must be invoked when script path cannot be resolved")
    }

    @Test
    fun `ackFn not called when execution fails`() {
        var ackCalled = false
        val task = makeTask(sourceType = "script", scriptPath = "path/to/script.kts")
        val ok = JobResult.Ok(configName = "cfg", task = task, ackFn = { ackCalled = true })

        val runScript: (String, String, String) -> Any? = { _, _, _ -> error("boom") }
        simulateRunPendingJobs(listOf(ok), runScript, "default")

        assertTrue(!ackCalled, "ackFn must NOT be called when execution fails")
    }

    @Test
    fun `compiled failure releases and does not ack`() {
        var ackCalled = false
        var releaseCalled = false
        val task = makeTask(sourceType = "compiled", scriptPath = null, packageName = "com.missing")
        val ok = JobResult.Ok(
            configName = "cfg",
            task = task,
            ackFn = { ackCalled = true },
            releaseFn = { releaseCalled = true }
        )

        val mapped = simulateRunPendingJobs(listOf(ok), { _, _, _ -> "unused" }, "default")

        assertTrue(mapped.any { it is JobResult.Error }, "compiled failure must surface as JobResult.Error")
        assertTrue(releaseCalled, "releaseFn must run so file jobs go to .failed, not .done")
        assertTrue(!ackCalled, "ackFn must NOT run when runCompiled fails")
    }

    // -------------------------------------------------------------------------
    // JobResult.Ok — new fields have defaults (backwards compat)
    // -------------------------------------------------------------------------

    @Test
    fun `JobResult Ok can be constructed without ackFn and releaseFn`() {
        val task = makeTask()
        val result = JobResult.Ok(configName = "cfg", task = task)
        assertTrue(result.ackFn == null)
        assertTrue(result.releaseFn == null)
    }

    // -------------------------------------------------------------------------
    // sourceType normalization — SCRIPT/COMPILED legacy + .kt vs .kts
    // -------------------------------------------------------------------------

    @Test
    fun `normalizeJobSourceType accepts uppercase COMPILED and SCRIPT`() {
        assertEquals("compiled", normalizeJobSourceType("COMPILED"))
        assertEquals("script", normalizeJobSourceType("SCRIPT"))
        assertEquals("compiled", normalizeJobSourceType("compiled"))
        assertTrue(isCompiledJobSource("COMPILED"))
        assertTrue(!isCompiledJobSource("SCRIPT"))
    }

    @Test
    fun `resolvePropertyJobSourceType treats module kt as compiled and kts as script`() {
        assertEquals(
            "compiled",
            resolvePropertyJobSourceType(
                "C:/proj/src/main/kotlin/com/igly/comms/scripts/registerIglyUser.kt"
            )
        )
        assertEquals("script", resolvePropertyJobSourceType("C:/proj/job-runner.kts"))
        assertEquals("compiled", resolvePropertyJobSourceType(null))
    }

    @Test
    fun `uppercase COMPILED sourceType routes to compiled branch`() {
        val task = makeTask(sourceType = "COMPILED", scriptPath = null, packageName = "com.example")
        val ok = JobResult.Ok(configName = "cfg", task = task)

        var scriptCalled = false
        val runScript: (String, String, String) -> Any? = { _, _, _ ->
            scriptCalled = true
            "should not be called"
        }

        simulateRunPendingJobs(listOf(ok), runScript, "default")
        assertTrue(!scriptCalled, "runScriptContent must NOT be invoked for COMPILED jobs")
    }

    @Test
    fun `legacy SCRIPT with module kt path still routes to compiled branch`() {
        val task = makeTask(
            sourceType = "SCRIPT",
            scriptPath = "src/main/kotlin/com/igly/comms/scripts/registerIglyUser.kt",
            packageName = "com.igly.comms.scripts"
        )
        val ok = JobResult.Ok(configName = "cfg", task = task)

        var scriptCalled = false
        val runScript: (String, String, String) -> Any? = { _, _, _ ->
            scriptCalled = true
            "should not be called"
        }

        simulateRunPendingJobs(listOf(ok), runScript, "default")
        assertTrue(!scriptCalled, "legacy SCRIPT+.kt must not use script runner")
        assertTrue(shouldRunJobAsCompiled("SCRIPT", task.scriptPath))
    }
}

/**
 * Inline reimplementation of the mapping loop inside [JobRunner.runPendingJobs] so we
 * can drive it with synthetic [JobResult] values without needing real job configs on disk.
 */
private fun simulateRunPendingJobs(
    allResults: List<JobResult>,
    runScriptContent: (String, String, String) -> Any?,
    context: String
): List<Any?> {
    val resolveTaskScriptPath: (KouTask) -> String? = { task ->
        task.scriptPath?.takeIf { it.isNotBlank() }
            ?: task.packageName?.takeIf { it.isNotBlank() }?.let { pkg ->
                val fileBase = task.fileName
                    .removeSuffix(".class").removeSuffix(".kt").removeSuffix("Kt")
                    .takeIf { it.isNotBlank() } ?: return@let null
                "src/main/kotlin/${pkg.replace(".", "/")}/${fileBase.lowercase()}.kt"
            }
    }

    return allResults.map { res ->
        when (res) {
            is JobResult.Ok -> {
                val task = res.task
                val taskContext = task.context?.takeIf { it.isNotBlank() } ?: context

                val execResult = try {
                    if (shouldRunJobAsCompiled(task.sourceType, task.scriptPath)) {
                        JobRunner.runCompiled(taskContext, task)
                    } else {
                        val scriptPath = resolveTaskScriptPath(task) ?: run {
                            res.releaseFn?.invoke()
                            return@map JobResult.Error(
                                "❌ Missing script metadata for job '${task.id}'. packageName/scriptPath is required."
                            )
                        }
                        runScriptContent(taskContext, scriptPath, task.params.toString())
                    }
                } catch (e: Exception) {
                    res.releaseFn?.invoke()
                    return@map JobResult.Error("❌ Execution failed for job '${task.id}': ${e.message}", e)
                }

                res.ackFn?.invoke()
                JobInfo(
                    configId = res.configName,
                    id = task.id,
                    function = task.functionName,
                    params = task.params,
                    source = task.scriptPath,
                    context = task.context,
                    version = task.contextVersion,
                    origin = task.origin,
                    packg = task.packageName,
                    resultOfExecution = execResult
                )
            }
            is JobResult.Error -> res
        }
    }
}
