package com.koupper.cli.commands

import com.koupper.cli.ANSIColors
import com.koupper.cli.ANSIColors.ANSI_GREEN_155
import com.koupper.cli.ANSIColors.ANSI_RESET
import com.koupper.cli.CommandManager
import com.koupper.cli.commands.AvailableCommands.HELP
import com.koupper.cli.commands.AvailableCommands.commands

class HelpCommand : Command() {
    override fun name(): String {
        return HELP
    }

    init {
        super.name = HELP
        super.usage = "\n   koupper ${ANSI_GREEN_155}$name$ANSI_RESET [${ANSI_GREEN_155}command$ANSI_RESET]\n"
        super.description = "\n   Shows help for commands\n"
        super.arguments = commands()
    }

    override fun execute(vararg args: String): String {
        return when {
            args.size == 1 -> {
                val description = super.showDescription()
                val usage = super.showUsage()
                val arguments = this.showArguments()
                "$description$usage$arguments"
            }

            else -> {
                when (val command = CommandManager().getCommandByName(args[1])) {
                    is HelpCommand -> {
                        val description = super.showDescription()
                        val usage = super.showUsage()
                        val arguments = this.showArguments()
                        "$description$usage$arguments"
                    }

                    is UndefinedCommand -> {
                        command.execute(args[1])
                    }

                    else -> {
                        val description = command.showDescription()
                        val usage = command.showUsage()
                        val arguments = command.showArguments()
                        val additionalInformation = command.showAdditionalInformation()
                        "$description$usage$arguments$additionalInformation"
                    }
                }
            }
        }
    }

    override fun showArguments(): String {
        val argHeader = "\n ${ANSIColors.ANSI_YELLOW_229}* Arguments:$ANSI_RESET \n"

        var finalArgInfo = ""

        this.arguments.forEach { (commandName, _) ->
            finalArgInfo += "   $ANSI_GREEN_155$commandName$ANSI_RESET \n"
        }

        return "$argHeader$finalArgInfo\n"
    }
}
