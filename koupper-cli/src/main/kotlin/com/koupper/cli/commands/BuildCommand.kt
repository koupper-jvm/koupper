package com.koupper.cli.commands

import com.koupper.cli.ANSIColors.ANSI_GREEN_155
import com.koupper.cli.ANSIColors.ANSI_RESET
import com.koupper.cli.ANSIColors.ANSI_WHITE
import com.koupper.cli.ANSIColors.ANSI_YELLOW_229
import com.koupper.cli.commands.AvailableCommands.BUILD
import com.koupper.cli.commands.AvailableCommands.HELP
import com.koupper.cli.commands.AvailableCommands.commands
import java.io.File

class BuildCommand : Command() {
    override fun name(): String {
        return HELP
    }

    init {
        super.name = BUILD
        super.usage = "koupper ${ANSI_GREEN_155}$name${ANSI_RESET} [${ANSI_GREEN_155}command${ANSI_RESET}]"
        super.description = commands()[super.name].toString()
        super.arguments = emptyMap()
    }

    override fun execute(vararg args: String): String {
        return when {
            args.isEmpty() -> {
                val currentDirectory = System.getProperty("user.dir")
                if (!File("$currentDirectory/init.kts").exists()) {
                    println("\n ${ANSI_WHITE}'init.kts' not found. Create one using: ${ANSI_YELLOW_229}koupper new file:init${ANSI_RESET}\n")
                    ""
                } else {
                    RunCommand().execute("$currentDirectory/init.kts")
                    ""
                }
            }

            else -> ""
        }
    }

    override fun showArguments(): String {
        val argHeader = " ${ANSI_YELLOW_229}• Arguments:${ANSI_RESET} \n"

        var finalArgInfo = ""

        this.arguments.forEach { (commandName, _) ->
            finalArgInfo += "   ${ANSI_GREEN_155}$commandName${ANSI_RESET} \n"
        }

        return "$argHeader$finalArgInfo"
    }
}
