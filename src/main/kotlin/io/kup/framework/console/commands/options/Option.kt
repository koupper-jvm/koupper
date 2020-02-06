package io.kup.framework.console.commands.options

import io.kup.framework.console.commands.Command

abstract class Option : Command() {
    abstract fun `for`(command: Command)
}
