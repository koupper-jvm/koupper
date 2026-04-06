package com.koupper.cli.commands.jobs

import com.koupper.cli.commands.RunCommand
import java.io.File

class JobRunWorkerHandler : JobSubcommandHandler {
    override fun handle(context: String, args: Array<String>): String {
        val jobIdArg = args.find { it.startsWith("--jobId=") }?.substringAfter("=")
        val configId = args.find { it.startsWith("--configId=") }?.substringAfter("=")?.takeIf { it.isNotBlank() }

        val scriptPath = "$context/job-runner.kts"
        File(scriptPath).writeText(generateJobRunnerScript(configId, jobIdArg))

        val output = RunCommand().execute(context, "job-runner.kts")
        return if (output.endsWith("\n")) output else "$output\n"
    }

    /**
     * Genera el script donde se define el objeto JobConfiguration dentro del archivo
     */
    private fun generateJobRunnerScript(configId: String?, jobId: String?): String {
        val jobIdLiteral = jobId?.let { "\"$it\"" } ?: "null"
        val configIdLiteral = configId?.let { "\"$it\"" } ?: "null"

        return """
import com.koupper.container.app
import com.koupper.container.context
import com.koupper.octopus.ScriptExecutor
import com.koupper.octopus.annotations.Export
import com.koupper.orchestrator.JobInfo
import com.koupper.orchestrator.JobResult
import com.koupper.orchestrator.JobRunner
import java.util.concurrent.CompletableFuture

@Export
val setup: (JobRunner) -> String = { runner ->
    val sb = StringBuilder()

    runner.runPendingJobs(
        runScriptContent = { ctx, scriptPath, argsString: String ->
            val se = app.getInstance(ScriptExecutor::class)
            val future = CompletableFuture<Any?>()

            se.runFromScriptFile<Any?>(
                ctx,
                scriptPath,
                argsString
            ) { value: Any? ->
                future.complete(value)
            }

            future.get()
        },
        context!!,
        jobId = $jobIdLiteral,
        configId = $configIdLiteral
    ) { res ->
        res.forEach {
            when (it) {
                is JobInfo -> {
                    if (sb.isNotEmpty()) sb.appendLine()
                    sb.appendLine("From config with id: ${'$'}{it.configId}")
                    sb.appendLine("📦 Job ID: ${'$'}{it.id}")
                    sb.appendLine(" - Function: ${'$'}{it.function}")
                    sb.appendLine(" - Params: ${'$'}{it.params}")
                    sb.appendLine(" - Result of execution: ${'$'}{it.resultOfExecution}")
                    sb.appendLine(" - Source: ${'$'}{it.source}")
                }
                is JobResult.Error -> {
                    if (sb.isNotEmpty()) sb.appendLine()
                    sb.appendLine("${'$'}{it.message}")
                }
            }
        }
    }

    sb.toString()
}
""".trimIndent()
    }
}
