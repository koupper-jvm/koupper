package com.koupper.console.commands.options.default

import com.koupper.framework.ANSIColors
import com.koupper.console.commands.Command
import com.koupper.console.commands.options.Option

class HelpOption : Option() {
    init {
        super.name = "-h | --help"
        super.description = "Display the help for a command"
        super.usage = "${ANSIColors.ANSI_GREEN_155}$name${ANSIColors.ANSI_RESET} [name]"
    }

    override fun `for`(command: Command) {
        command.execute()
    }

    override fun execute(vararg args: String) {
        super.displayDescription()

        super.displayUsage()

        this.displayArguments()
    }

    override fun displayArguments() {
        println(" ${ANSIColors.ANSI_YELLOW_229}• Arguments:${ANSIColors.ANSI_RESET}")

        println("   ${ANSIColors.ANSI_GREEN_155}name${ANSIColors.ANSI_RESET} The name of the item to show the help.")

        println()
    }

    override fun name(): String {
        return super.name
    }
}
