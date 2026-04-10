package com.koupper.cli.commands.infra

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

data class InfraCliOptions(
    val dir: String,
    val varFiles: List<String> = emptyList(),
    val backendConfigs: List<String> = emptyList(),
    val autoApprove: Boolean = false,
    val timeoutSeconds: Long = 300,
    val retries: Int = 0,
    val retryDelayMs: Long = 250,
    val json: Boolean = false,
    val specPath: String? = null,
    val observedPath: String? = null,
    val stages: List<String> = listOf("infra", "preflight", "deploy", "smoke", "rollback"),
    val policy: String = "strict",
    val deployCommand: String? = null,
    val smokeCommand: String? = null,
    val rollbackCommand: String? = null,
    val awsTimeoutSeconds: Long? = null,
    val awsRetryCount: Int? = null,
    val awsRetryBackoffMs: Long? = null,
    val frontendBackupMode: String? = null
)

data class CliCommandResult(
    val ok: Boolean,
    val stage: String,
    val exitCode: Int,
    val durationMs: Long,
    val warnings: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
    val artifacts: Map<String, Any?> = emptyMap(),
    val nextAction: String? = null
)

data class ExecResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean
)

typealias InfraExecutor = (args: List<String>, workdir: String, timeoutSeconds: Long) -> ExecResult

object InfraSupport {
    val mapper = jacksonObjectMapper()
    private val allowedPolicies = setOf("strict", "continue_on_error", "abort_on_failure")
    private val allowedStages = setOf("infra", "preflight", "deploy", "smoke", "rollback")
    private val knownFlags = setOf(
        "--dir", "--var-file", "--backend-config", "--auto-approve", "--timeout", "--json", "--retry", "--retry-delay-ms",
        "--spec", "--observed-file", "--stages", "--policy", "--deploy-command", "--smoke-command", "--rollback-command",
        "--aws-timeout-seconds", "--aws-retry-count", "--aws-retry-backoff-ms", "--frontend-backup-mode"
    )

    fun parseOptions(context: String, args: List<String>): Pair<InfraCliOptions?, List<String>> {
        val errors = mutableListOf<String>()
        val values = mutableMapOf<String, MutableList<String>>()
        var autoApprove = false
        var json = false

        args.forEach { arg ->
            if (!arg.startsWith("--")) return@forEach

            if (arg == "--auto-approve") {
                autoApprove = true
                return@forEach
            }
            if (arg == "--json") {
                json = true
                return@forEach
            }

            val key = arg.substringBefore("=")
            if (key !in knownFlags) {
                errors += "Unknown flag: $key"
                return@forEach
            }
            val value = arg.substringAfter("=", "")
            if (value.isBlank()) {
                errors += "Flag $key requires a value"
                return@forEach
            }
            values.getOrPut(key) { mutableListOf() }.add(value)
        }

        val timeout = values["--timeout"]?.lastOrNull()?.toLongOrNull() ?: 300L
        if (timeout <= 0) errors += "--timeout must be greater than zero"

        val retries = values["--retry"]?.lastOrNull()?.toIntOrNull() ?: 0
        if (retries < 0) errors += "--retry must be zero or greater"

        val retryDelayMs = values["--retry-delay-ms"]?.lastOrNull()?.toLongOrNull() ?: 250L
        if (retryDelayMs < 0) errors += "--retry-delay-ms must be zero or greater"

        val awsTimeoutSeconds = values["--aws-timeout-seconds"]?.lastOrNull()?.toLongOrNull()
        if (values["--aws-timeout-seconds"] != null && (awsTimeoutSeconds == null || awsTimeoutSeconds <= 0)) {
            errors += "--aws-timeout-seconds must be greater than zero"
        }

        val awsRetryCount = values["--aws-retry-count"]?.lastOrNull()?.toIntOrNull()
        if (values["--aws-retry-count"] != null && (awsRetryCount == null || awsRetryCount < 0)) {
            errors += "--aws-retry-count must be zero or greater"
        }

        val awsRetryBackoffMs = values["--aws-retry-backoff-ms"]?.lastOrNull()?.toLongOrNull()
        if (values["--aws-retry-backoff-ms"] != null && (awsRetryBackoffMs == null || awsRetryBackoffMs < 0)) {
            errors += "--aws-retry-backoff-ms must be zero or greater"
        }

        val frontendBackupMode = values["--frontend-backup-mode"]?.lastOrNull()?.lowercase(Locale.getDefault())
        if (frontendBackupMode != null && frontendBackupMode !in setOf("full", "incremental", "disabled")) {
            errors += "--frontend-backup-mode must be one of: full, incremental, disabled"
        }

        val policy = values["--policy"]?.lastOrNull() ?: "strict"
        if (policy !in allowedPolicies) {
            errors += "--policy must be one of: strict, continue_on_error, abort_on_failure"
        }

        val stages = (values["--stages"]?.lastOrNull()
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: listOf("infra", "preflight", "deploy", "smoke", "rollback"))

        val invalidStages = stages.filter { it !in allowedStages }
        if (invalidStages.isNotEmpty()) {
            errors += "Invalid stages: ${invalidStages.joinToString(", ")}"
        }

        if (errors.isNotEmpty()) return null to errors

        return InfraCliOptions(
            dir = values["--dir"]?.lastOrNull() ?: context,
            varFiles = values["--var-file"].orEmpty(),
            backendConfigs = values["--backend-config"].orEmpty(),
            autoApprove = autoApprove,
            timeoutSeconds = timeout,
            retries = retries,
            retryDelayMs = retryDelayMs,
            json = json,
            specPath = values["--spec"]?.lastOrNull(),
            observedPath = values["--observed-file"]?.lastOrNull(),
            stages = stages,
            policy = policy,
            deployCommand = values["--deploy-command"]?.lastOrNull(),
            smokeCommand = values["--smoke-command"]?.lastOrNull(),
            rollbackCommand = values["--rollback-command"]?.lastOrNull(),
            awsTimeoutSeconds = awsTimeoutSeconds,
            awsRetryCount = awsRetryCount,
            awsRetryBackoffMs = awsRetryBackoffMs,
            frontendBackupMode = frontendBackupMode
        ) to emptyList()
    }

    fun defaultExecutor(args: List<String>, workdir: String, timeoutSeconds: Long): ExecResult {
        val process = try {
            ProcessBuilder(args)
                .directory(File(workdir))
                .start()
        } catch (error: Throwable) {
            return ExecResult(127, "", error.message ?: "failed to start process", false)
        }

        val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            return ExecResult(124, "", "timeout", true)
        }

        return ExecResult(
            exitCode = process.exitValue(),
            stdout = process.inputStream.bufferedReader().readText().trim(),
            stderr = process.errorStream.bufferedReader().readText().trim(),
            timedOut = false
        )
    }

    fun executeWithRetry(
        stage: String,
        options: InfraCliOptions,
        command: List<String>,
        executor: InfraExecutor
    ): CliCommandResult {
        val started = System.currentTimeMillis()
        var attempts = 0
        var last = ExecResult(127, "", "execution failed", false)
        while (attempts <= options.retries.coerceAtLeast(0)) {
            attempts += 1
            last = executor(command, options.dir, options.timeoutSeconds)
            if (last.exitCode == 0 && !last.timedOut) break
            if (attempts <= options.retries.coerceAtLeast(0)) {
                Thread.sleep(options.retryDelayMs)
            }
        }

        val duration = System.currentTimeMillis() - started
        val warnings = mutableListOf<String>()
        val errors = mutableListOf<String>()

        if (last.exitCode != 0) {
            errors += last.stderr.ifBlank { "$stage failed with exitCode=${last.exitCode}" }
        }
        if (last.timedOut) {
            errors += "Command timed out after ${options.timeoutSeconds}s"
        }
        if (stage == "drift" && last.exitCode == 2) {
            warnings += "Drift detected by terraform detailed-exitcode"
        }

        return CliCommandResult(
            ok = last.exitCode == 0,
            stage = stage,
            exitCode = last.exitCode,
            durationMs = duration,
            warnings = warnings,
            errors = errors,
            artifacts = mapOf(
                "command" to redact(command.joinToString(" ")),
                "stdout" to redact(last.stdout),
                "stderr" to redact(last.stderr),
                "attempts" to attempts
            ),
            nextAction = when {
                last.exitCode == 0 -> null
                stage == "drift" && last.exitCode == 2 -> "Inspect drift and run reconcile/apply"
                else -> "Fix errors and rerun $stage"
            }
        )
    }

    fun evaluateDriftSpec(specJson: String, observedJson: String): Pair<List<String>, List<String>> {
        val spec = mapper.readTree(specJson)
        val observed = mapper.readTree(observedJson)
        val mode = spec.path("mode").asText("required_only").lowercase(Locale.getDefault())

        val expectedItems = collectItems(spec.path("checks"))
        val observedItems = collectItems(observed.path("checks"))

        val missing = (expectedItems - observedItems).toList().sorted()
        val extras = if (mode == "exact_match") (observedItems - expectedItems).toList().sorted() else emptyList()
        return missing to extras
    }

    fun render(result: CliCommandResult, asJson: Boolean): String {
        return if (asJson) {
            mapper.writeValueAsString(result)
        } else {
            mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result)
        }
    }

    private fun collectItems(root: JsonNode): Set<String> {
        if (root.isMissingNode || root.isNull) return emptySet()
        val items = mutableSetOf<String>()

        root.path("dynamo").path("tables").forEach { table ->
            val name = table.path("name").asText("")
            if (name.isNotBlank()) items += "dynamo.table:$name"
            table.path("gsis").forEach { gsi ->
                val gsiName = gsi.asText("")
                if (name.isNotBlank() && gsiName.isNotBlank()) items += "dynamo.gsi:$name:$gsiName"
            }
        }

        root.path("api").path("routes").forEach { route ->
            val path = route.path("path").asText("")
            val method = route.path("method").asText("").uppercase(Locale.getDefault())
            val stage = route.path("stage").asText("")
            if (path.isNotBlank() && method.isNotBlank()) items += "api.route:$stage:$method:$path"
        }

        root.path("lambda").path("aliases").forEach { alias ->
            val function = alias.path("function").asText("")
            val name = alias.path("name").asText("")
            if (function.isNotBlank() && name.isNotBlank()) items += "lambda.alias:$function:$name"
            alias.path("env").fields().forEach { (k, v) ->
                items += "lambda.env:$function:$k=${v.asText("")}"
            }
        }

        root.path("sqs").path("queues").forEach { queue ->
            val name = queue.path("name").asText("")
            if (name.isBlank()) return@forEach
            items += "sqs.queue:$name"
            val dlq = queue.path("dlq").asText("")
            if (dlq.isNotBlank()) items += "sqs.dlq:$name:$dlq"
            val redrive = queue.path("redrive").asText("")
            if (redrive.isNotBlank()) items += "sqs.redrive:$name:$redrive"
            val policy = queue.path("policy").asText("")
            if (policy.isNotBlank()) items += "sqs.policy:$name:$policy"
        }

        root.path("workers").path("health").forEach { worker ->
            val name = worker.path("name").asText("")
            val url = worker.path("url").asText("")
            val mapping = worker.path("eventSourceMapping").asText("")
            if (name.isNotBlank()) items += "worker.name:$name"
            if (name.isNotBlank() && url.isNotBlank()) items += "worker.url:$name:$url"
            if (name.isNotBlank() && mapping.isNotBlank()) items += "worker.event-source:$name:$mapping"
        }

        return items
    }

    fun redact(value: String): String {
        if (value.isBlank()) return value
        var output = value
        val patterns = listOf(
            Regex("(?i)(aws_secret_access_key\\s*[=:]\\s*)([^\\s]+)"),
            Regex("(?i)(token\\s*[=:]\\s*)([^\\s]+)"),
            Regex("(?i)(password\\s*[=:]\\s*)([^\\s]+)"),
            Regex("(?i)(secret\\s*[=:]\\s*)([^\\s]+)")
        )
        patterns.forEach { pattern ->
            output = pattern.replace(output) { match -> "${match.groupValues[1]}***" }
        }
        return output
    }
}
