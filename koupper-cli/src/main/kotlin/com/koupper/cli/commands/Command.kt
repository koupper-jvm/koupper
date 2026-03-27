package com.koupper.cli.commands

import com.koupper.cli.ANSIColors.ANSI_GREEN_155
import com.koupper.cli.ANSIColors.ANSI_RESET
import com.koupper.cli.ANSIColors.ANSI_YELLOW_229

abstract class Command {
    lateinit var name: String
    lateinit var usage: String
    lateinit var description: String
    lateinit var arguments: Map<String, String>
    lateinit var additionalInformation: String

    abstract fun execute(vararg args: String): String

    abstract fun name(): String

    open fun showUsage(): String {
        val usageHeader = " ${ANSI_YELLOW_229}- Usage examples:$ANSI_RESET"

        return "\n$usageHeader $usage"
    }

    open fun showDescription(): String {
        return "$ANSI_YELLOW_229$description$ANSI_RESET"
    }

    open fun showArguments(): String {
        val argHeader = "\n ${ANSI_YELLOW_229}- Commands:$ANSI_RESET"

        var maxLengthOfCommand = 0

        this.arguments.forEach { (commandName, _) ->
            if (commandName.length > maxLengthOfCommand) {
                maxLengthOfCommand = commandName.length
            }
        }

        var finalCommandInfo = ""

        this.arguments.forEach { (commandName, description) ->
            val message = commandName.padEnd(maxLengthOfCommand + 3)

            finalCommandInfo += "   $ANSI_GREEN_155$message$ANSI_RESET$description\n"
        }

        return "$argHeader    \n$finalCommandInfo"
    }

    open fun showAdditionalInformation(): String {
        return "$ANSI_YELLOW_229$additionalInformation"
    }
}
