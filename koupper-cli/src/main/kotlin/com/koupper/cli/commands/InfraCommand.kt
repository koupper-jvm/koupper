package com.koupper.cli.commands

import com.koupper.cli.commands.AvailableCommands.INFRA
import com.koupper.cli.commands.infra.InfraExecutor
import com.koupper.cli.commands.infra.InfraSupport

class InfraCommand(
    private val executor: InfraExecutor = InfraSupport::defaultExecutor
) : Command() {
    init {
        super.name = INFRA
        super.usage = "\n" + """
   koupper infra init [--dir=.] [--backend-config=...] [--timeout=300] [--retry=0] [--json]
   koupper infra validate [--dir=.] [--timeout=300] [--json]
   koupper infra plan [--dir=.] [--var-file=...] [--timeout=300] [--json]
   koupper infra apply [--dir=.] [--var-file=...] --auto-approve [--timeout=300] [--json]
   koupper infra drift [--dir=.] [--var-file=...] [--spec=drift-spec.json] [--observed-file=observed.json] [--json]
   koupper infra output [--dir=.] [--json]
        """
        super.description = "\n   Terraform-first infrastructure lifecycle with stable JSON output\n"
        super.arguments = mapOf(
            "--dir=<path>" to "Terraform working directory.",
            "--var-file=<path>" to "Terraform variable file (repeatable).",
            "--backend-config=<kv|path>" to "Terraform backend config (repeatable).",
            "--auto-approve" to "Required for infra apply.",
            "--timeout=<seconds>" to "Command timeout in seconds.",
            "--retry=<n>" to "Retries for transient failures.",
            "--retry-delay-ms=<ms>" to "Delay between retries.",
            "--json" to "Emit machine-readable JSON response.",
            "--spec=<path>" to "Drift spec v1 file for drift validation.",
            "--observed-file=<path>" to "Observed checks file for drift spec evaluation."
        )
        super.additionalInformation = "\n   For more info: https://koupper.com/docs/commands/infra"
    }

    override fun execute(vararg args: String): String {
        val context = args.firstOrNull() ?: "."
        val realArgs = args.drop(1)
        if (realArgs.isEmpty()) return showUsage()

        val subcommand = realArgs.first().lowercase()
        val (options, optionErrors) = InfraSupport.parseOptions(context, realArgs.drop(1))
        if (optionErrors.isNotEmpty() || options == null) {
            val result = com.koupper.cli.commands.infra.CliCommandResult(
                ok = false,
                stage = subcommand,
                exitCode = 2,
                durationMs = 0,
                errors = optionErrors.ifEmpty { listOf("Invalid command options") },
                nextAction = "Fix CLI flags and retry"
            )
            return InfraSupport.render(result, true)
        }

        val result = when (subcommand) {
            "init" -> InfraSupport.executeWithRetry(
                stage = "init",
                options = options,
                command = listOf("terraform", "init", "-input=false") + options.backendConfigs.map { "-backend-config=$it" },
                executor = executor
            )

            "validate" -> {
                val initResult = InfraSupport.executeWithRetry(
                    stage = "init",
                    options = options,
                    command = listOf("terraform", "init", "-input=false") + options.backendConfigs.map { "-backend-config=$it" },
                    executor = executor
                )
                if (!initResult.ok) {
                    initResult.copy(stage = "validate", nextAction = "Fix init failures and rerun validate")
                } else {
                    val cmd = mutableListOf("terraform", "validate")
                    if (options.json) cmd += "-json"
                    InfraSupport.executeWithRetry("validate", options, cmd, executor)
                }
            }

            "plan" -> {
                val initResult = InfraSupport.executeWithRetry(
                    stage = "init",
                    options = options,
                    command = listOf("terraform", "init", "-input=false") + options.backendConfigs.map { "-backend-config=$it" },
                    executor = executor
                )
                if (!initResult.ok) {
                    initResult.copy(stage = "plan", nextAction = "Fix init failures and rerun plan")
                } else {
                    val cmd = mutableListOf("terraform", "plan", "-input=false")
                    options.varFiles.forEach { cmd += "-var-file=$it" }
                    InfraSupport.executeWithRetry("plan", options, cmd, executor)
                }
            }

            "apply" -> {
                if (!options.autoApprove) {
                    com.koupper.cli.commands.infra.CliCommandResult(
                        ok = false,
                        stage = "apply",
                        exitCode = 2,
                        durationMs = 0,
                        errors = listOf("infra apply requires --auto-approve"),
                        nextAction = "Rerun apply with --auto-approve"
                    )
                } else {
                    val initResult = InfraSupport.executeWithRetry(
                        stage = "init",
                        options = options,
                        command = listOf("terraform", "init", "-input=false") + options.backendConfigs.map { "-backend-config=$it" },
                        executor = executor
                    )
                    if (!initResult.ok) {
                        initResult.copy(stage = "apply", nextAction = "Fix init failures and rerun apply")
                    } else {
                        val cmd = mutableListOf("terraform", "apply", "-input=false", "-auto-approve")
                        options.varFiles.forEach { cmd += "-var-file=$it" }
                        InfraSupport.executeWithRetry("apply", options, cmd, executor)
                    }
                }
            }

            "drift" -> {
                val initResult = InfraSupport.executeWithRetry(
                    stage = "init",
                    options = options,
                    command = listOf("terraform", "init", "-input=false") + options.backendConfigs.map { "-backend-config=$it" },
                    executor = executor
                )
                if (!initResult.ok) {
                    initResult.copy(stage = "drift", nextAction = "Fix init failures and rerun drift")
                } else {
                    val cmd = mutableListOf("terraform", "plan", "-input=false", "-detailed-exitcode")
                    options.varFiles.forEach { cmd += "-var-file=$it" }
                    val driftResult = InfraSupport.executeWithRetry("drift", options, cmd, executor)

                    val withSpec = if (!options.specPath.isNullOrBlank() && !options.observedPath.isNullOrBlank()) {
                        val specFile = java.io.File(options.specPath)
                        val observedFile = java.io.File(options.observedPath)
                        if (!specFile.exists() || !observedFile.exists()) {
                            driftResult.copy(
                                ok = false,
                                exitCode = 2,
                                errors = driftResult.errors + listOf("spec/observed file not found"),
                                nextAction = "Provide valid --spec and --observed-file paths"
                            )
                        } else {
                            val (missing, extras) = InfraSupport.evaluateDriftSpec(specFile.readText(), observedFile.readText())
                            val hasMismatch = missing.isNotEmpty() || extras.isNotEmpty()
                            driftResult.copy(
                                ok = driftResult.exitCode == 0 && !hasMismatch,
                                exitCode = if (hasMismatch) 2 else driftResult.exitCode,
                                warnings = driftResult.warnings + if (hasMismatch) listOf("Drift spec mismatch detected") else emptyList(),
                                artifacts = driftResult.artifacts + mapOf(
                                    "driftSpec" to mapOf("version" to "1", "missing" to missing, "extras" to extras)
                                )
                            )
                        }
                    } else {
                        driftResult
                    }

                    withSpec
                }
            }

            "output" -> {
                val result = InfraSupport.executeWithRetry("output", options, listOf("terraform", "output", "-json"), executor)
                val parsed = runCatching {
                    InfraSupport.mapper.readTree(result.artifacts["stdout"]?.toString().orEmpty())
                }.getOrNull()
                result.copy(artifacts = result.artifacts + mapOf("outputJson" to parsed))
            }

            else -> {
                com.koupper.cli.commands.infra.CliCommandResult(
                    ok = false,
                    stage = subcommand,
                    exitCode = 2,
                    durationMs = 0,
                    errors = listOf("Unknown infra subcommand: '$subcommand'"),
                    nextAction = "Use: koupper infra init|validate|plan|apply|drift|output"
                )
            }
        }

        return InfraSupport.render(result, options.json || result.exitCode != 0)
    }

    override fun name(): String = INFRA

    override fun showArguments(): String = super.showArguments()
}
