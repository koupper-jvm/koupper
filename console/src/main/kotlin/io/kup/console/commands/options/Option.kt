package io.kup.console.commands.options

import io.kup.console.commands.Command

abstract class Option : Command() {
    abstract fun `for`(command: Command)
}
