package io.kup.console.commands

import io.kup.framework.ANSIColors.ANSI_GREEN_155
import io.kup.framework.ANSIColors.ANSI_RESET
import io.kup.framework.ANSIColors.ANSI_YELLOW_229
import io.kup.console.commands.arguments.Argument
import io.kup.console.commands.options.Option

val AVAILABLE_COMMANDS = arrayOf(
    Make()
)

abstract class Command {
    lateinit var name: String
    lateinit var options: List<Option>
    lateinit var usage: String
    lateinit var description: String
    lateinit var arguments: List<Argument>

    abstract fun execute(vararg args: String)

    abstract fun name(): String

    open fun showOptions() {
        println(" ${ANSI_YELLOW_229}• Options:$ANSI_RESET")

        var maxLengthOfOption = 0

        this.options.forEach {
            if (it.name.length > maxLengthOfOption) {
                maxLengthOfOption = it.name.length
            }
        }

        this.options.forEach {
            val message = it.name.padEnd(maxLengthOfOption + 3)

            println("   $ANSI_GREEN_155$message$ANSI_RESET${it.description}")
        }

        println()
    }

    open fun displayUsage() {
        println(" ${ANSI_YELLOW_229}• Usage:$ANSI_RESET")

        println("   $usage")

        println()
    }

    open fun displayDescription() {
        println("\n $ANSI_GREEN_155>>$ANSI_RESET$ANSI_YELLOW_229 $description \n")
    }

    open fun displayArguments() {
        println(" ${ANSI_YELLOW_229}• Arguments:$ANSI_RESET")

        var maxLengthOfCommand = 0

        this.arguments.forEach {
            if (it.name.length > maxLengthOfCommand) {
                maxLengthOfCommand = it.name.length
            }
        }

        this.arguments.forEach {
            val message = it.name.padEnd(maxLengthOfCommand + 3)

            println("   $ANSI_GREEN_155$message$ANSI_RESET${it.description}")
        }

        println()
    }
}
