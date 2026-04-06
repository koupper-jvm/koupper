package com.koupper.cli.commands.jobs

import com.koupper.cli.commands.RunCommand
import java.io.File

class JobListHandler : JobSubcommandHandler {
    override fun handle(context: String, args: Array<String>): String {
        val jobIdArg = args.find { it.startsWith("--jobId=") }?.substringAfter("=")?.takeIf { it.isNotBlank() }
        val configId = args.find { it.startsWith("--configId=") }?.substringAfter("=")?.takeIf { it.isNotBlank() }

        val scriptPath = "$context/job-list.kts"

        File(scriptPath).writeText(generateJobListerScript(configId, jobIdArg))

        val output = RunCommand().execute(context, "job-list.kts")
        return if (output.endsWith("\n")) output else "$output\n"
    }

    private fun generateJobListerScript(configId: String?, jobId: String?): String {
        val jobIdLiteral = jobId?.let { "\"$it\"" } ?: "null"
        val configIdLiteral = configId?.let { "\"$it\"" } ?: "null"

        return """
        import com.koupper.octopus.annotations.Export
        import com.koupper.orchestrator.JobLister
        import com.koupper.orchestrator.JobResult
        import com.koupper.container.context
        import com.koupper.orchestrator.JobInfo

        @Export
        val setup: (JobLister) -> String = { runner ->
            val sb = StringBuilder()

            runner.list(context!!, jobId = $jobIdLiteral, configId = $configIdLiteral) { res ->
                res.forEach {
                    when (it) {
                        is JobInfo -> {
                            if (sb.isNotEmpty()) sb.appendLine()
                            sb.appendLine("From config with id: ${'$'}{it.configId}")
                            sb.appendLine("📦 Job ID: ${'$'}{it.id}")
                            sb.appendLine(" - Function: ${'$'}{it.function}")
                            sb.appendLine(" - Params: ${'$'}{it.params}")
                            sb.appendLine(" - Source: ${'$'}{it.source}")
                            sb.appendLine(" - Context: ${'$'}{it.context}")
                            sb.appendLine(" - Version: ${'$'}{it.version}")
                            sb.appendLine(" - Origin: ${'$'}{it.origin}")
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
