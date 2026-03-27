package com.koupper.cli.commands.jobs

import com.koupper.cli.ANSIColors.ANSI_GREEN_155
import com.koupper.cli.ANSIColors.ANSI_RED
import com.koupper.cli.ANSIColors.ANSI_RESET
import com.koupper.cli.ANSIColors.ANSI_YELLOW_229
import java.io.File

class JobInitHandler : JobSubcommandHandler {
    override fun handle(context: String, args: Array<String>): String {
        val targetFile = File("$context/jobs.json")
        val force = args.any { it == "--force" }

        if (targetFile.exists() && !force) {
            return "\n${ANSI_YELLOW_229}jobs.json already exists. Use --force to overwrite it.${ANSI_RESET}\n"
        }

        targetFile.parentFile.mkdirs()
        targetFile.writeText(
            """
            {
              "driver": "file",
              "queue": "default",
              "queues": {
                "default": {
                  "concurrency": 1
                }
              }
            }
            """.trimIndent()
        )

        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val gradleCommand = if (isWindows) listOf("cmd", "/c", "gradlew.bat") else listOf("./gradlew")

        val process = ProcessBuilder()
            .directory(File(context))
            .command(gradleCommand + listOf("clean", "shadowJar"))
            .inheritIO()
            .start()

        val exitCode = process.waitFor()
        if (exitCode != 0) {
            return "${ANSI_RED}✖ Failed to build JAR automatically (exit code $exitCode).${ANSI_RESET}"
        }

        return """
            ${ANSI_GREEN_155}✔ jobs.json created at ${targetFile.absolutePath}.${ANSI_RESET}
        """.trimIndent()
    }
}
