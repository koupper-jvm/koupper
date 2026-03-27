package com.koupper.cli.commands

import com.koupper.cli.ANSIColors.ANSI_RESET
import com.koupper.cli.ANSIColors.ANSI_WHITE
import com.koupper.cli.ANSIColors.RED_BACKGROUND_203
import com.koupper.cli.commands.AvailableCommands.UNDEFINED

class UndefinedCommand : Command() {
    override fun name(): String {
        return UNDEFINED
    }

    override fun execute(vararg args: String): String {
        return "\n$RED_BACKGROUND_203$ANSI_WHITE The command '${args[0]}' is undefined. $ANSI_RESET \n"
    }
}
