package io.kup.console.commands

import io.kup.framework.ANSIColors.ANSI_GREEN_155
import io.kup.framework.ANSIColors.ANSI_RESET
import io.kup.framework.ANSIColors.ANSI_WHITE
import io.kup.framework.ANSIColors.ANSI_YELLOW_229
import io.kup.console.commands.options.default.HelpOption

class DefaultCommand : Command() {
    init {
        super.name = "default"
        super.description = "A command executor"
        super.usage = "[options] [${ANSI_GREEN_155}command$ANSI_RESET | ${ANSI_GREEN_155}command$ANSI_RESET${ANSI_WHITE}:argument$ANSI_RESET]"
        super.options = listOf(
            HelpOption()
        )
    }

    override fun execute(vararg args: String) {
        super.displayDescription()

        super.displayUsage()

        super.showOptions()

        this.displayAvailableCommands()
    }

    private fun displayAvailableCommands() {
        println(" ${ANSI_YELLOW_229}• Available commands:$ANSI_RESET")

        var maxLengthOfCommand = 0

        AVAILABLE_COMMANDS.forEach {
            it.arguments.forEach { argument ->
                if (argument.name.length > maxLengthOfCommand) {
                    maxLengthOfCommand = argument.name.length
                }
            }
        }

        AVAILABLE_COMMANDS.forEach {
            println("   $ANSI_GREEN_155${it.name}$ANSI_RESET")

            it.arguments.forEach { argument ->
                val message = argument.name.padEnd(maxLengthOfCommand + 3)

                println("    $ANSI_WHITE:$message$ANSI_RESET${argument.description}")
            }
        }

        println()
    }

    override fun name(): String {
        return super.name
    }

}
