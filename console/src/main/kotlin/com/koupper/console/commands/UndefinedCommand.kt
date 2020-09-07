package com.koupper.console.commands

import com.koupper.framework.ANSIColors.ANSI_RESET
import com.koupper.framework.ANSIColors.ANSI_WHITE
import com.koupper.framework.ANSIColors.RED_BACKGROUND_203

class UndefinedCommand : Command() {
    override fun name(): String {
        return "undefined"
    }

    override fun execute(vararg args: String) {
        println("\n$RED_BACKGROUND_203$ANSI_WHITE The command '${args[0]}' is undefined. $ANSI_RESET \n")
    }
}
