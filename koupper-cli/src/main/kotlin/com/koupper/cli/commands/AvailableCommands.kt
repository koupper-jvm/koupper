package com.koupper.cli.commands

import com.koupper.cli.ANSIColors.ANSI_RESET

object AvailableCommands {
    const val NEW = "new"
    const val HELP = "help"
    const val BUILD = "build"
    const val RUN = "run"
    const val DEPLOY = "deploy"
    const val UNDEFINED = "undefined"
    const val DEFAULT = "default"
    const val MODULE = "module"
    const val JOB = "job"
    const val PROVIDER = "provider"
    const val INFRA = "infra"
    const val RECONCILE = "reconcile"

    fun commands(): Map<String, String> = mapOf(
        NEW to "Creates a module or script",
        RUN to "Runs a kotlin script",
        DEPLOY to "Deploys a .kts script to a remote Octopus daemon",
        HELP to "Displays information about a command",
        MODULE to "Analyzes and inspects existing modules and their structure.",
        JOB to "Creates and manages background job workers",
        PROVIDER to "Lists available service providers and environment requirements",
        INFRA to "Runs Terraform-based infrastructure lifecycle commands",
        RECONCILE to "Orchestrates infra/preflight/deploy/smoke/rollback pipelines"
    )
}
