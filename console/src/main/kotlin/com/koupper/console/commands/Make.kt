package com.koupper.console.commands

import com.koupper.framework.ANSIColors
import com.koupper.console.commands.arguments.make.MakeController

class Make : Command() {
    init {
        super.name = "make"
        super.usage = "$name:[${ANSIColors.ANSI_GREEN_155}argument${ANSIColors.ANSI_RESET}]"
        super.description = "Create a resource"
        super.arguments = listOf(
            MakeController()
        )
    }

    override fun execute(vararg args: String) {
        super.displayDescription()

        super.displayUsage()

        super.displayArguments()
    }

    override fun name(): String {
        return super.name
    }

}
