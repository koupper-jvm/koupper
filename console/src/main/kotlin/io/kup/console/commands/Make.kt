package io.kup.console.commands

import io.kup.framework.ANSIColors
import io.kup.console.commands.arguments.make.MakeController

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
