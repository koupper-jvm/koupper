package com.koupper.cli.commands

import com.koupper.cli.ANSIColors.ANSI_GREEN_155
import com.koupper.cli.ANSIColors.ANSI_RESET
import com.koupper.cli.ANSIColors.ANSI_WHITE
import com.koupper.cli.ANSIColors.ANSI_YELLOW_229
import com.koupper.cli.commands.AvailableCommands.DEFAULT
import com.koupper.cli.commands.AvailableCommands.commands

class DefaultCommand : Command() {
    override fun name(): String {
        return DEFAULT
    }

    init {
        super.name = "koupper"
        super.usage = "\n   $name [${ANSI_GREEN_155}command$ANSI_RESET]\n"
        super.description = "\n   koupper cli ${ANSI_GREEN_155}4.0.0$ANSI_RESET\n"
        super.arguments = commands()
        super.additionalInformation = ""
    }

    override fun execute(vararg args: String): String {
        val description = super.showDescription()

        val usage = super.showUsage()

        val additionalInformation = super.showAdditionalInformation()

        val arguments = super.showArguments()

        return "$description$usage$additionalInformation$arguments\n"
    }
}
