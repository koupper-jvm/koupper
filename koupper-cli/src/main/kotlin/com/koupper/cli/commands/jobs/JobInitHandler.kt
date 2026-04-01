package com.koupper.cli.commands.jobs

import com.koupper.cli.ANSIColors.ANSI_GREEN_155
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
              "id": "local-file",
              "driver": "file",
              "queue": "default",
              "for-all-projects": true
            }
            """.trimIndent()
        )

        return """
            ${ANSI_GREEN_155}✔ jobs.json created at ${targetFile.absolutePath}.${ANSI_RESET}
            ${ANSI_YELLOW_229}Tip:${ANSI_RESET} run ${ANSI_GREEN_155}koupper job build-environment${ANSI_RESET} when you want to build worker artifacts.
        """.trimIndent()
    }
}
