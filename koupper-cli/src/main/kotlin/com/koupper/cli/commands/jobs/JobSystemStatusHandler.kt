package com.koupper.cli.commands.jobs

import com.koupper.cli.commands.RunCommand
import java.io.File

class JobSystemStatusHandler : JobSubcommandHandler {
    override fun handle(context: String, args: Array<String>): String {
        val configId = args.find { it.startsWith("--configId=") }?.substringAfter("=")?.takeIf { it.isNotBlank() }

        val scriptPath = "$context/job-runner.kts"
        File(scriptPath).writeText(generateJobDisplayerScript(configId))

        val statusHeader = buildString {
            appendLine()
            appendLine("   🧠 Current Job Configuration")
            appendLine("   ─────────────────────────────")

            if (configId.isNullOrBlank()) {
                appendLine("   🛠️  Using all configurations defined in jobs.json")
            } else {
                appendLine("   🛠️  Config ID: $configId")
            }
        }


        return statusHeader + RunCommand().execute(context, "job-runner.kts")
    }

    private fun generateJobDisplayerScript(configId: String?): String {
        return """
            import com.koupper.octopus.annotations.Export
            import com.koupper.container.context
            import com.koupper.orchestrator.JobDisplayer

            @Export
            val setup: (JobDisplayer) -> String = { displayer ->
                displayer.showStatus(context!!, configId = "$configId")
            }
        """.trimIndent()
    }

    private fun getJobDriverFromConfig(context: String): String? {
        val jobsJson = File("$context/jobs.json")
        if (!jobsJson.exists()) return null
        val rx = """"driver"\s*:\s*"([\w\-]+)"""".toRegex()
        return rx.find(jobsJson.readText())?.groupValues?.get(1)
    }

    private fun getJobQueueFromConfig(context: String): String? {
        val jobsJson = File("$context/jobs.json")
        if (!jobsJson.exists()) return null
        val rx = """"queue"\s*:\s*"([\w\-]+)"""".toRegex()
        return rx.find(jobsJson.readText())?.groupValues?.get(1)
    }
}
