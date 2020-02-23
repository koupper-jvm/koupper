package io.kup.framework.console.commands.arguments.make

import io.kup.framework.ANSIColors
import io.kup.framework.console.commands.arguments.Argument

class MakeController : Argument() {
    init {
        super.name = "controller"
        super.usage = "make:controller [${ANSIColors.ANSI_GREEN_155}argument${ANSIColors.ANSI_RESET}]"
        super.description = "Create a controller class"
    }

    override fun execute(vararg args: String) {
        super.displayDescription()

        super.displayUsage()

        this.displayArguments()
    }

    override fun displayArguments() {
        println(" ${ANSIColors.ANSI_YELLOW_229}• Arguments:${ANSIColors.ANSI_RESET}")

        println("   ${ANSIColors.ANSI_GREEN_155}name${ANSIColors.ANSI_RESET} The controller name")

        println()
    }

    override fun name(): String {
        return super.name
    }
}
