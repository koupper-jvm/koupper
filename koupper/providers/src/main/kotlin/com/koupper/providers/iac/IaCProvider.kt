package com.koupper.providers.iac

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

data class InfraExecutionOptions(
    val dir: String,
    val varFiles: List<String> = emptyList(),
    val backendConfigs: List<String> = emptyList(),
    val vars: Map<String, String> = emptyMap(),
    val autoApprove: Boolean = false,
    val timeoutSeconds: Long = 300,
    val retries: Int = 0,
    val retryDelayMs: Long = 250,
    val json: Boolean = true
)

data class InfraExecutionResult(
    val ok: Boolean,
    val stage: String,
    val exitCode: Int,
    val durationMs: Long,
    val warnings: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
    val artifacts: Map<String, Any?> = emptyMap(),
    val nextAction: String? = null
)

data class DriftSpecResult(
    val mode: String,
    val missing: List<String>,
    val extras: List<String>
)

interface IaCProvider {
    fun init(options: InfraExecutionOptions): InfraExecutionResult
    fun validate(options: InfraExecutionOptions): InfraExecutionResult
    fun plan(options: InfraExecutionOptions): InfraExecutionResult
    fun apply(options: InfraExecutionOptions): InfraExecutionResult
    fun drift(options: InfraExecutionOptions): InfraExecutionResult
    fun output(options: InfraExecutionOptions): InfraExecutionResult
    fun evaluateDriftSpec(specJson: String, observedJson: String): DriftSpecResult

    @Deprecated("Use plan(options)")
    fun terraformPlan(path: String, vars: Map<String, String> = emptyMap()): IaCResult {
        val result = plan(InfraExecutionOptions(dir = path, vars = vars, json = false))
        return IaCResult(
            command = "terraform plan",
            exitCode = result.exitCode,
            stdout = result.artifacts["stdout"]?.toString().orEmpty(),
            stderr = result.artifacts["stderr"]?.toString().orEmpty()
        )
    }

    @Deprecated("Use apply(options)")
    fun terraformApply(path: String, vars: Map<String, String> = emptyMap(), approved: Boolean = false): IaCResult {
        val result = apply(InfraExecutionOptions(dir = path, vars = vars, autoApprove = approved, json = false))
        return IaCResult(
            command = "terraform apply",
            exitCode = result.exitCode,
            stdout = result.artifacts["stdout"]?.toString().orEmpty(),
            stderr = result.artifacts["stderr"]?.toString().orEmpty()
        )
    }

    @Deprecated("Use output(options)")
    fun terraformOutput(path: String): IaCResult {
        val result = output(InfraExecutionOptions(dir = path, json = true))
        return IaCResult(
            command = "terraform output -json",
            exitCode = result.exitCode,
            stdout = result.artifacts["stdout"]?.toString().orEmpty(),
            stderr = result.artifacts["stderr"]?.toString().orEmpty()
        )
    }

    @Deprecated("Use drift(options)")
    fun driftCheck(path: String): IaCResult {
        val result = drift(InfraExecutionOptions(dir = path, json = false))
        return IaCResult(
            command = "terraform plan -detailed-exitcode",
            exitCode = result.exitCode,
            stdout = result.artifacts["stdout"]?.toString().orEmpty(),
            stderr = result.artifacts["stderr"]?.toString().orEmpty()
        )
    }
}

data class IaCResult(
    val command: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String
)

class TerraformIaCProvider(
    private val terraformCommand: String = "terraform",
    private val timeoutSeconds: Long = 300,
    private val commandRunner: ((List<String>, String, Long) -> RunnerResult)? = null
) : IaCProvider {
    private val mapper = jacksonObjectMapper()

    override fun init(options: InfraExecutionOptions): InfraExecutionResult {
        return executeStage("init", options) {
            listOf(terraformCommand, "init", "-input=false") + backendArgs(options.backendConfigs)
        }
    }

    override fun validate(options: InfraExecutionOptions): InfraExecutionResult {
        val initialized = init(options)
        if (!initialized.ok) {
            return initialized.copy(stage = "validate", nextAction = "Fix init errors and rerun validate")
        }
        return executeStage("validate", options) {
            val args = mutableListOf(terraformCommand, "validate")
            if (options.json) args += "-json"
            args
        }
    }

    override fun plan(options: InfraExecutionOptions): InfraExecutionResult {
        val initialized = init(options)
        if (!initialized.ok) {
            return initialized.copy(stage = "plan", nextAction = "Fix init errors and rerun plan")
        }
        return executeStage("plan", options) {
            mutableListOf(terraformCommand, "plan", "-input=false")
                .apply {
                    addAll(varFileArgs(options.varFiles))
                    addAll(varArgs(options.vars))
                }
        }
    }

    override fun apply(options: InfraExecutionOptions): InfraExecutionResult {
        if (!options.autoApprove) {
            return InfraExecutionResult(
                ok = false,
                stage = "apply",
                exitCode = 2,
                durationMs = 0,
                errors = listOf("apply requires autoApprove=true for non-interactive execution"),
                nextAction = "Rerun with autoApprove=true"
            )
        }
        val initialized = init(options)
        if (!initialized.ok) {
            return initialized.copy(stage = "apply", nextAction = "Fix init errors and rerun apply")
        }
        return executeStage("apply", options) {
            mutableListOf(terraformCommand, "apply", "-input=false", "-auto-approve")
                .apply {
                    addAll(varFileArgs(options.varFiles))
                    addAll(varArgs(options.vars))
                }
        }
    }

    override fun drift(options: InfraExecutionOptions): InfraExecutionResult {
        val initialized = init(options)
        if (!initialized.ok) {
            return initialized.copy(stage = "drift", nextAction = "Fix init errors and rerun drift")
        }
        val result = executeStage("drift", options) {
            mutableListOf(terraformCommand, "plan", "-input=false", "-detailed-exitcode")
                .apply {
                    addAll(varFileArgs(options.varFiles))
                    addAll(varArgs(options.vars))
                }
        }
        val warning = when (result.exitCode) {
            2 -> listOf("Drift detected by terraform detailed-exitcode")
            else -> emptyList()
        }
        return result.copy(warnings = result.warnings + warning)
    }

    override fun output(options: InfraExecutionOptions): InfraExecutionResult {
        return executeStage("output", options) {
            listOf(terraformCommand, "output", "-json")
        }.let { base ->
            val stdout = base.artifacts["stdout"]?.toString().orEmpty()
            val parsed = runCatching { mapper.readTree(stdout) }.getOrNull()
            base.copy(artifacts = base.artifacts + mapOf("outputJson" to parsed))
        }
    }

    override fun evaluateDriftSpec(specJson: String, observedJson: String): DriftSpecResult {
        val spec = mapper.readTree(specJson)
        val observed = mapper.readTree(observedJson)
        val version = spec.path("version").asText("1")
        require(version == "1") { "Unsupported drift spec version: $version" }

        val mode = spec.path("mode").asText("required_only").lowercase(Locale.getDefault())
        val expectedItems = collectDriftItems(spec.path("checks"))
        val observedItems = collectDriftItems(observed.path("checks"))

        val missing = (expectedItems - observedItems).toList().sorted()
        val extras = if (mode == "exact_match") (observedItems - expectedItems).toList().sorted() else emptyList()

        return DriftSpecResult(mode = mode, missing = missing, extras = extras)
    }

    private fun executeStage(
        stage: String,
        options: InfraExecutionOptions,
        commandFactory: () -> List<String>
    ): InfraExecutionResult {
        val timeout = if (options.timeoutSeconds > 0) options.timeoutSeconds else timeoutSeconds
        val started = System.currentTimeMillis()
        var attempt = 0
        var latest = RunnerResult(127, "", "failed to execute", false)

        while (attempt <= options.retries.coerceAtLeast(0)) {
            attempt += 1
            latest = run(commandFactory(), options.dir, timeout)
            if (!latest.timedOut && latest.exitCode == 0) break
            if (attempt <= options.retries.coerceAtLeast(0)) {
                Thread.sleep(options.retryDelayMs.coerceAtLeast(0))
            }
        }

        val duration = System.currentTimeMillis() - started
        val command = commandFactory().joinToString(" ")
        val errors = mutableListOf<String>()
        if (latest.exitCode != 0) {
            errors += latest.stderr.ifBlank { "$stage failed with exitCode=${latest.exitCode}" }
        }
        if (latest.timedOut) {
            errors += "Command timed out after ${timeout}s"
        }

        return InfraExecutionResult(
            ok = latest.exitCode == 0,
            stage = stage,
            exitCode = latest.exitCode,
            durationMs = duration,
            errors = errors,
            artifacts = mapOf(
                "command" to redact(command),
                "stdout" to redact(latest.stdout),
                "stderr" to redact(latest.stderr),
                "attempts" to attempt
            ),
            nextAction = when {
                latest.exitCode == 0 -> null
                stage == "drift" && latest.exitCode == 2 -> "Review terraform plan and reconcile differences"
                else -> "Inspect errors and rerun $stage"
            }
        )
    }

    private fun run(args: List<String>, path: String, timeout: Long): RunnerResult {
        commandRunner?.let { return it.invoke(args, path, timeout) }

        val process = try {
            ProcessBuilder(args)
                .directory(File(path))
                .start()
        } catch (error: Throwable) {
            return RunnerResult(127, "", error.message ?: "failed to start terraform process", false)
        }

        val completed = process.waitFor(timeout, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            return RunnerResult(124, "", "timeout", true)
        }

        return RunnerResult(
            exitCode = process.exitValue(),
            stdout = process.inputStream.bufferedReader().readText().trim(),
            stderr = process.errorStream.bufferedReader().readText().trim(),
            timedOut = false
        )
    }

    private fun backendArgs(configs: List<String>): List<String> =
        configs.filter { it.isNotBlank() }.flatMap { listOf("-backend-config=$it") }

    private fun varFileArgs(files: List<String>): List<String> =
        files.filter { it.isNotBlank() }.flatMap { listOf("-var-file=$it") }

    private fun varArgs(vars: Map<String, String>): List<String> =
        vars.entries.sortedBy { it.key }.flatMap { listOf("-var", "${it.key}=${it.value}") }

    private fun collectDriftItems(root: JsonNode): Set<String> {
        if (root.isMissingNode || root.isNull) return emptySet()
        val items = mutableSetOf<String>()

        root.path("dynamo").path("tables").forEach { table ->
            val tableName = table.path("name").asText("")
            if (tableName.isNotBlank()) items += "dynamo.table:$tableName"
            table.path("gsis").forEach { gsi ->
                val gsiName = gsi.asText("")
                if (tableName.isNotBlank() && gsiName.isNotBlank()) items += "dynamo.gsi:$tableName:$gsiName"
            }
        }

        root.path("api").path("routes").forEach { route ->
            val path = route.path("path").asText("")
            val method = route.path("method").asText("").uppercase(Locale.getDefault())
            val stage = route.path("stage").asText("")
            if (path.isNotBlank() && method.isNotBlank()) {
                items += "api.route:$stage:$method:$path"
            }
        }

        root.path("lambda").path("aliases").forEach { alias ->
            val fn = alias.path("function").asText("")
            val name = alias.path("name").asText("")
            if (fn.isNotBlank() && name.isNotBlank()) items += "lambda.alias:$fn:$name"
            alias.path("env").fields().forEach { (k, v) ->
                items += "lambda.env:$fn:$k=${v.asText("")}"
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

    private fun redact(value: String): String {
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

data class RunnerResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean
)
