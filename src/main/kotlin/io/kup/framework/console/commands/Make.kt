package io.kup.framework.console.commands

import io.kup.framework.console.commands.arguments.make.MakeController

class Make : Command() {
    init {
        super.name = "make"
        super.usage = "$name:[argument]"
        super.description = "Create a resource"
        super.arguments = listOf(
            MakeController()
        )
    }

    override fun execute(vararg args: String) {
        super.displayDescription()

        super.displayUsage()

        super.displayArguments()
    }

    override fun name(): String {
        return super.name
    }

}
