package com.koupper.cli.commands.jobs

import com.koupper.cli.ANSIColors
import com.koupper.cli.ANSIColors.ANSI_GREEN_155
import com.koupper.cli.ANSIColors.ANSI_RESET
import com.koupper.cli.ANSIColors.ANSI_WHITE
import com.koupper.cli.commands.AvailableCommands.JOB
import com.koupper.cli.commands.Command

class JobCommand : Command() {
    init {
        super.name = JOB
        super.usage = "\n" + """
   koupper job init [--force]                                    Initializes the jobs.json configuration file.
   koupper job build-environment                                 Builds the runnable environment for the worker to execute jobs.
   koupper job run-worker [--jobId][--configId]                  Runs pending jobs from the configured source.
   koupper job list [--jobId][--configId]                        Lists pending jobs.
   koupper job status [--configId]                               Shows current job system status.
   koupper job failed [--jobId][--configId]                      Shows failed jobs.
   koupper job retry [--jobId][--configId]                       Retries a failed job.
        """

        super.description = "\n   Creates and manages background job workers\n"
        super.additionalInformation = """
   For more info: https://koupper.com/cli/commands/job
        """
        super.arguments = mapOf(
            "--force" to "Forces creation of a new jobs.json configuration file.",
            "--jobId=jobId" to "Specifies the job ID to used for the operation.",
            "--configId=configId" to "Specifies the configuration ID to use for the operation.",
        )
    }

    override fun name(): String = JOB

    override fun showArguments(): String {
        val argHeader = "\n ${ANSIColors.ANSI_YELLOW_229}  Flags:$ANSI_RESET \n"

        var finalArgInfo = ""

        this.arguments.forEach { (commandName, description) ->
            finalArgInfo += "   $ANSI_GREEN_155$commandName: $ANSI_WHITE$description$ANSI_RESET\n"
        }

        return "$argHeader$finalArgInfo"
    }

    override fun execute(vararg args: String): String {
        val validSubcommands = listOf(
            "init", "build-environment", "run-worker", "list", "status", "failed", "retry"
        )

        val validFlags = listOf(
            "--force", "--queue", "--concurrency", "--jobId", "--configId"
        )

        val context = args.firstOrNull() ?: "."
        val realArgs = args.drop(1)

        if (realArgs.isEmpty()) {
            return this.usage
        }

        val subcommand = realArgs.first()

        if (subcommand !in validSubcommands) {
            return "\n${ANSIColors.ANSI_RED}Unknown job subcommand: '$subcommand'. Try 'koupper job help'.$ANSI_RESET\n"
        }

        val usedFlags = realArgs.drop(1).filter { it.startsWith("--") }

        val unrecognized = usedFlags.filter { flag ->
            validFlags.none { valid ->
                if (flag.contains('=')) {
                    flag.substringBefore("=") == valid
                } else {
                    flag == valid
                }
            }
        }

        if (unrecognized.isNotEmpty()) {
            val formatted = unrecognized.joinToString(", ")
            return "\n${ANSIColors.ANSI_RED}Unrecognized flags: $formatted${ANSI_RESET}\n"
        }

        return when (subcommand) {
            "init" -> JobInitHandler().handle(context, arrayOf(*args))
            "run-worker" -> JobRunWorkerHandler().handle(context, arrayOf(*args))
            "list" -> JobListHandler().handle(context, arrayOf(*args))
            "build-environment" -> JobBuildWorkerHandler().handle(context, arrayOf(*args))
            "status" -> JobSystemStatusHandler().handle(context, arrayOf(*args))
            /*
            "failed" -> JobFailedHandler().handle(args)
            "retry" -> JobRetryHandler().handle(args)
            "help" -> this.usage*/
            else -> this.usage
        }
    }

}
