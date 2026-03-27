package com.koupper.cli.commands.jobs

import com.koupper.cli.commands.RunCommand
import java.io.File

class JobBuildWorkerHandler : JobSubcommandHandler {
    override fun handle(context: String, args: Array<String>): String {
        val scriptPath = "$context/worker-builder.kts"
        File(scriptPath).writeText(generateBuildWorkerScript())
        return RunCommand().execute(context, "worker-builder.kts")
    }

    private fun generateBuildWorkerScript(): String {
        return """
            import com.koupper.octopus.annotations.Export
            import com.koupper.orchestrator.JobBuilder
            import com.koupper.container.context

            @Export
            val setup: (JobBuilder) -> String = { runner ->
                runner.build(context!!)
            }
        """.trimIndent()
    }

    private fun getJobDriverFromConfig(context: String): String? {
        val jobsJson = File("$context/jobs.json")
        if (!jobsJson.exists()) return null
        val rx = """"driver"\s*:\s*"([\w\-]+)"""".toRegex()
        return rx.find(jobsJson.readText())?.groupValues?.get(1)
    }
}
