package com.koupper.console.commands.options

import com.koupper.console.commands.Command

abstract class Option : Command() {
    abstract fun `for`(command: Command)
}
