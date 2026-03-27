package com.koupper.cli.commands.jobs

interface JobSubcommandHandler {
    fun handle(context: String, args: Array<String>): String
}
