package io.kup.framework.console

import io.kup.framework.ANSIColors.ANSI_RESET
import io.kup.framework.ANSIColors.ANSI_WHITE
import io.kup.framework.ANSIColors.RED_BACKGROUND_203
import io.kup.framework.console.commands.AVAILABLE_COMMANDS
import io.kup.framework.console.commands.Command
import io.kup.framework.console.commands.DefaultCommand
import io.kup.framework.console.commands.UndefinedCommand
import io.kup.framework.console.commands.options.Option
import kotlin.system.exitProcess

class CommandHandler {
    fun run(args: Array<String>) {
        if (args.isEmpty()) {
            DefaultCommand().execute()

            return
        }

        if (this.isAFlag(args[0])) {
            if (args.size > 1) {
                val param = args[1]

                if (param.contains(":")) {
                    val commandName = param.substring(0, param.indexOf(":"))

                    val commandArgument = param.substring(param.indexOf(":") + 1)

                    val command = this.getCommandWithName(commandName)

                    command.arguments.forEach {
                        if (it.name.equals(commandArgument, ignoreCase = true)) {
                            it.execute()
                        }
                    }

                    return
                }

                this.getFlagWithName(args[0]).`for`(this.getCommandWithName(param))

                return
            }

            this.getFlagWithName(args[0]).execute()
        }
    }

    private fun isAFlag(value: String): Boolean {
        DefaultCommand().options.forEach {
            if (it.name.contains(value)) {
                return true
            }
        }

        return false
    }

    private fun getFlagWithName(name: String): Option {
        DefaultCommand().options.forEach {
            if (it.name.contains(name)) {
                return it
            }
        }

        println("\n$RED_BACKGROUND_203$ANSI_WHITE The option '$name' is not defined. $ANSI_RESET \n")

        exitProcess(7)
    }

    private fun getCommandWithName(name: String): Command {
        AVAILABLE_COMMANDS.forEach {
            if (it.name.equals(name, ignoreCase = true)) {
                return it
            }
        }

        return UndefinedCommand()
    }
}

fun main(args: Array<String>) {
    CommandHandler().run(args)
}
