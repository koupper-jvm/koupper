package com.koupper.cli.commands

import com.koupper.cli.commands.AvailableCommands.RECONCILE
import com.koupper.cli.commands.infra.CliCommandResult
import com.koupper.cli.commands.infra.InfraExecutor
import com.koupper.cli.commands.infra.InfraSupport

class ReconcileCommand(
    private val executor: InfraExecutor = InfraSupport::defaultExecutor
) : Command() {
    private val infraCommand = InfraCommand(executor)

    init {
        super.name = RECONCILE
        super.usage = "\n" + """
   koupper reconcile run [--dir=.] [--stages=infra,preflight,deploy,smoke,rollback] [--policy=strict]
                         [--var-file=...] [--backend-config=...] [--auto-approve]
                         [--deploy-command="..."] [--smoke-command="..."] [--rollback-command="..."]
                         [--aws-timeout-seconds=900] [--aws-retry-count=4] [--aws-retry-backoff-ms=800]
                         [--frontend-backup-mode=incremental] [--json]
        """
        super.description = "\n   Runs end-to-end reconcile stages with policy-driven failure handling\n"
        super.arguments = mapOf(
            "run" to "Executes the reconcile pipeline.",
            "--stages=<csv>" to "Subset/order of stages (infra,preflight,deploy,smoke,rollback).",
            "--policy=<mode>" to "strict | continue_on_error | abort_on_failure.",
            "--deploy-command=<shell>" to "Agnostic deploy stage command.",
            "--smoke-command=<shell>" to "Agnostic smoke verification command.",
            "--rollback-command=<shell>" to "Agnostic rollback command used when needed.",
            "--aws-timeout-seconds=<n>" to "Exports AWS_DEPLOY_TIMEOUT_SECONDS to stage commands.",
            "--aws-retry-count=<n>" to "Exports AWS_DEPLOY_RETRY_COUNT to stage commands.",
            "--aws-retry-backoff-ms=<n>" to "Exports AWS_DEPLOY_RETRY_BACKOFF_MS to stage commands.",
            "--frontend-backup-mode=<mode>" to "Exports AWS_FRONTEND_BACKUP_MODE (full|incremental|disabled)."
        )
        super.additionalInformation = "\n   For more info: https://koupper.com/docs/commands/reconcile"
    }

    override fun execute(vararg args: String): String {
        val context = args.firstOrNull() ?: "."
        val realArgs = args.drop(1)
        if (realArgs.isEmpty()) return showUsage()

        val subcommand = realArgs.first().lowercase()
        if (subcommand != "run") {
            val result = CliCommandResult(
                ok = false,
                stage = "reconcile",
                exitCode = 2,
                durationMs = 0,
                errors = listOf("Unknown reconcile subcommand: '$subcommand'"),
                nextAction = "Use: koupper reconcile run"
            )
            return InfraSupport.render(result, true)
        }

        val (options, parseErrors) = InfraSupport.parseOptions(context, realArgs.drop(1))
        if (options == null || parseErrors.isNotEmpty()) {
            val result = CliCommandResult(
                ok = false,
                stage = "reconcile",
                exitCode = 2,
                durationMs = 0,
                errors = parseErrors.ifEmpty { listOf("Invalid reconcile flags") },
                nextAction = "Fix flags and rerun"
            )
            return InfraSupport.render(result, true)
        }

        val started = System.currentTimeMillis()
        val stageResults = mutableListOf<CliCommandResult>()
        var overallExit = 0

        fun runStage(stage: String): CliCommandResult {
            return when (stage) {
                "infra" -> {
                    val output = infraCommand.execute(context, "apply", "--dir=${options.dir}", *options.varFiles.map { "--var-file=$it" }.toTypedArray(), *options.backendConfigs.map { "--backend-config=$it" }.toTypedArray(), if (options.autoApprove) "--auto-approve" else "", "--timeout=${options.timeoutSeconds}", "--retry=${options.retries}", "--retry-delay-ms=${options.retryDelayMs}", "--json")
                    InfraSupport.mapper.readValue(output, CliCommandResult::class.java)
                }

                "preflight" -> {
                    val driftArgs = mutableListOf(
                        context,
                        "drift",
                        "--dir=${options.dir}",
                        "--timeout=${options.timeoutSeconds}",
                        "--retry=${options.retries}",
                        "--retry-delay-ms=${options.retryDelayMs}",
                        "--json"
                    )
                    options.varFiles.forEach { driftArgs += "--var-file=$it" }
                    options.backendConfigs.forEach { driftArgs += "--backend-config=$it" }
                    options.specPath?.let { driftArgs += "--spec=$it" }
                    options.observedPath?.let { driftArgs += "--observed-file=$it" }
                    val output = infraCommand.execute(*driftArgs.toTypedArray())
                    InfraSupport.mapper.readValue(output, CliCommandResult::class.java)
                }

                "deploy" -> runGenericStage("deploy", options.deployCommand, options)
                "smoke" -> runGenericStage("smoke", options.smokeCommand, options)
                "rollback" -> runGenericStage("rollback", options.rollbackCommand, options)
                else -> CliCommandResult(
                    ok = false,
                    stage = stage,
                    exitCode = 2,
                    durationMs = 0,
                    errors = listOf("Unknown stage: $stage"),
                    nextAction = "Use valid stage names"
                )
            }
        }

        for (stage in options.stages) {
            val result = runStage(stage)
            stageResults += result
            if (result.exitCode != 0) {
                overallExit = result.exitCode
                val shouldStop = options.policy == "strict" || options.policy == "abort_on_failure"
                if (shouldStop) {
                    val hasRollbackStage = options.stages.contains("rollback")
                    val alreadyRolledBack = stage == "rollback"
                    if (!alreadyRolledBack && hasRollbackStage) {
                        stageResults += runStage("rollback")
                    }
                    break
                }
            }
        }

        val duration = System.currentTimeMillis() - started
        val result = CliCommandResult(
            ok = stageResults.all { it.exitCode == 0 },
            stage = "reconcile",
            exitCode = if (stageResults.all { it.exitCode == 0 }) 0 else overallExit,
            durationMs = duration,
            warnings = if (options.policy == "continue_on_error") listOf("Pipeline configured to continue on errors") else emptyList(),
            errors = stageResults.flatMap { it.errors },
            artifacts = mapOf(
                "policy" to options.policy,
                "stages" to stageResults,
                "requestedStages" to options.stages,
                "awsControls" to mapOf(
                    "awsTimeoutSeconds" to options.awsTimeoutSeconds,
                    "awsRetryCount" to options.awsRetryCount,
                    "awsRetryBackoffMs" to options.awsRetryBackoffMs,
                    "frontendBackupMode" to options.frontendBackupMode
                )
            ),
            nextAction = if (stageResults.all { it.exitCode == 0 }) null else "Inspect failed stage artifacts and rerun reconcile"
        )

        return InfraSupport.render(result, options.json || result.exitCode != 0)
    }

    private fun runGenericStage(stage: String, command: String?, options: com.koupper.cli.commands.infra.InfraCliOptions): CliCommandResult {
        if (command.isNullOrBlank()) {
            return CliCommandResult(
                ok = true,
                stage = stage,
                exitCode = 0,
                durationMs = 0,
                warnings = listOf("No command configured for stage '$stage'; skipped"),
                artifacts = mapOf("skipped" to true)
            )
        }
        val commandWithEnv = withAwsControlEnv(command, options)
        val shellCommand = if (isWindows()) {
            listOf("pwsh", "-NoProfile", "-Command", commandWithEnv)
        } else {
            listOf("bash", "-lc", commandWithEnv)
        }
        return InfraSupport.executeWithRetry(stage, options, shellCommand, executor)
    }

    private fun withAwsControlEnv(command: String, options: com.koupper.cli.commands.infra.InfraCliOptions): String {
        val assignments = linkedMapOf<String, String>()
        options.awsTimeoutSeconds?.let { assignments["AWS_DEPLOY_TIMEOUT_SECONDS"] = it.toString() }
        options.awsRetryCount?.let { assignments["AWS_DEPLOY_RETRY_COUNT"] = it.toString() }
        options.awsRetryBackoffMs?.let { assignments["AWS_DEPLOY_RETRY_BACKOFF_MS"] = it.toString() }
        options.frontendBackupMode?.let { assignments["AWS_FRONTEND_BACKUP_MODE"] = it }

        if (assignments.isEmpty()) {
            return command
        }

        return if (isWindows()) {
            val exports = assignments.entries.joinToString("; ") { (k, v) -> "\$env:$k='${v.replace("'", "''")}'" }
            "$exports; $command"
        } else {
            val exports = assignments.entries.joinToString(" ") { (k, v) -> "$k='${v.replace("'", "'\\''")}'" }
            "$exports $command"
        }
    }

    override fun name(): String = RECONCILE

    private fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("win")
}
