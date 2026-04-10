import com.koupper.container.app
import com.koupper.container.context
import com.koupper.octopus.ScriptExecutor
import com.koupper.octopus.annotations.Export
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

data class Input(
    val featureBranch: String,
    val baseBranch: String = "develop",
    val syncBase: Boolean = true,
    val requireCleanTree: Boolean = true,
    val commandTimeoutSeconds: Long = 120,
    val commandRetries: Int = 1,
    val retryDelaySeconds: Long = 2,
    val cleanupGeneratedScripts: Boolean = true,
    val prTitle: String? = null,
    val prBody: String? = null,
    val workflowName: String = "PR Fast Checks",
    val waitForCi: Boolean = false,
    val mergeAfterCi: Boolean = false,
    val adminMerge: Boolean = true,
    val mergeMethod: String = "merge",
    val dryRun: Boolean = false
)

data class CommandResult(
    val command: String,
    val exitCode: Int,
    val output: String
)

private fun runCommand(
    args: List<String>,
    cwd: File,
    timeoutSeconds: Long,
    retries: Int,
    retryDelaySeconds: Long
): CommandResult {
    var attempt = 0
    var last = CommandResult(command = args.joinToString(" "), exitCode = 1, output = "")

    while (attempt <= retries) {
        val process = ProcessBuilder(args)
            .directory(cwd)
            .redirectErrorStream(true)
            .start()

        val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            last = CommandResult(args.joinToString(" "), 124, "command timed out after ${timeoutSeconds}s")
        } else {
            val output = process.inputStream.bufferedReader().readText().trim()
            last = CommandResult(args.joinToString(" "), process.exitValue(), output)
        }

        if (last.exitCode == 0 || attempt == retries) {
            return last
        }

        Thread.sleep(retryDelaySeconds * 1000)
        attempt++
    }

    return last
}

private fun toJson(value: Any?): String {
    return when (value) {
        null -> "null"
        is String -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        is Number, is Boolean -> value.toString()
        is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}") {
            toJson(it.key.toString()) + ":" + toJson(it.value)
        }
        is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { toJson(it) }
        else -> toJson(value.toString())
    }
}

private fun resolveScriptFile(cwd: File, relativeScriptPath: String): File {
    val direct = File(cwd, relativeScriptPath)
    if (direct.exists()) return direct

    var cursor: File? = cwd
    repeat(5) {
        cursor = cursor?.parentFile
        val candidate = cursor?.let { File(it, relativeScriptPath) }
        if (candidate != null && candidate.exists()) {
            return candidate
        }
    }

    error("script not found from '$cwd': $relativeScriptPath")
}

private fun runKoupperScript(cwd: File, relativeScriptPath: String, params: Map<String, Any?>): Any? {
    val scriptExecutor = app.getInstance(ScriptExecutor::class)
    val scriptFile = resolveScriptFile(cwd, relativeScriptPath)

    val payload = toJson(params)
    val done = CompletableFuture<Any?>()

    scriptExecutor.runFromScriptFile<Any?>(
        cwd.absolutePath,
        scriptFile.absolutePath,
        payload
    ) { result ->
        done.complete(result)
    }

    return done.get()
}

private fun ensureStepOk(stepName: String, result: Any?) {
    val map = result as? Map<*, *>
        ?: error("$stepName returned non-map result: $result")

    val ok = map["ok"] as? Boolean
    if (ok != true) {
        error("$stepName reported failure: $result")
    }
}

@Export
val setup: (Input) -> Map<String, Any?> = { input ->
    val cwd = File(context ?: ".").absoluteFile
    val steps = mutableListOf<Map<String, Any?>>()

    val preflight = runKoupperScript(
        cwd,
        "scripts/release/preflight.kts",
        mapOf(
            "baseBranch" to input.baseBranch,
            "featureBranch" to input.featureBranch,
            "syncBase" to input.syncBase,
            "requireCleanTree" to input.requireCleanTree,
            "createBranchIfMissing" to true,
            "allowedUntracked" to listOf("docs/future-providers-brief.md"),
            "commandTimeoutSeconds" to input.commandTimeoutSeconds,
            "commandRetries" to input.commandRetries,
            "retryDelaySeconds" to input.retryDelaySeconds,
            "cleanupGeneratedScripts" to input.cleanupGeneratedScripts
        )
    )
    ensureStepOk("preflight", preflight)
    steps += mapOf("name" to "preflight", "result" to preflight)

    val gitStatusFuture = CompletableFuture.supplyAsync {
        runCommand(
            listOf("git", "status", "--short", "--branch"),
            cwd,
            input.commandTimeoutSeconds,
            input.commandRetries,
            input.retryDelaySeconds
        )
    }
    val ghAuthFuture = CompletableFuture.supplyAsync {
        runCommand(
            listOf("gh", "auth", "status"),
            cwd,
            input.commandTimeoutSeconds,
            input.commandRetries,
            input.retryDelaySeconds
        )
    }

    val headShaResult = runCommand(
        listOf("git", "rev-parse", "HEAD"),
        cwd,
        input.commandTimeoutSeconds,
        input.commandRetries,
        input.retryDelaySeconds
    )
    val expectedHeadSha = headShaResult.output.lines().firstOrNull()?.trim().orEmpty()

    steps += mapOf(
        "name" to "parallel-checks",
        "result" to mapOf(
            "gitStatus" to gitStatusFuture.get().output,
            "ghAuth" to ghAuthFuture.get().output,
            "expectedHeadSha" to expectedHeadSha
        )
    )

    if (input.dryRun) {
        steps += mapOf("name" to "dry-run", "result" to "skipped PR/CI/merge")
        mapOf(
            "ok" to true,
            "dryRun" to true,
            "baseBranch" to input.baseBranch,
            "featureBranch" to input.featureBranch,
            "steps" to steps
        )
    } else {
        val prCreate = runKoupperScript(
            cwd,
            "scripts/release/pr-create.kts",
            mapOf(
                "baseBranch" to input.baseBranch,
                "headBranch" to input.featureBranch,
                "title" to input.prTitle,
                "body" to input.prBody,
                "pushBranch" to true,
                "draft" to false,
                "commandTimeoutSeconds" to input.commandTimeoutSeconds,
                "commandRetries" to input.commandRetries,
                "retryDelaySeconds" to input.retryDelaySeconds
            )
        )
        ensureStepOk("pr-create", prCreate)
        steps += mapOf("name" to "pr-create", "result" to prCreate)

        if (input.waitForCi) {
            val ciWatch = runKoupperScript(
                cwd,
                "scripts/release/ci-watch.kts",
                mapOf(
                    "branch" to input.featureBranch,
                    "workflowName" to input.workflowName,
                    "pollSeconds" to 20,
                    "timeoutSeconds" to 900,
                    "failOnFailure" to true,
                    "expectedHeadSha" to expectedHeadSha,
                    "commandTimeoutSeconds" to input.commandTimeoutSeconds,
                    "commandRetries" to input.commandRetries,
                    "retryDelaySeconds" to input.retryDelaySeconds
                )
            )
            ensureStepOk("ci-watch", ciWatch)
            steps += mapOf("name" to "ci-watch", "result" to ciWatch)
        }

        if (input.mergeAfterCi) {
            val prNumber = runCommand(
                listOf("gh", "pr", "view", input.featureBranch, "--json", "number", "--jq", ".number"),
                cwd,
                input.commandTimeoutSeconds,
                input.commandRetries,
                input.retryDelaySeconds
            ).output.trim().toIntOrNull() ?: error("unable to resolve PR number for ${input.featureBranch}")

            val merged = runKoupperScript(
                cwd,
                "scripts/release/merge-sync.kts",
                mapOf(
                    "prNumber" to prNumber,
                    "mergeMethod" to input.mergeMethod,
                    "admin" to input.adminMerge,
                    "deleteBranch" to false,
                    "baseBranch" to input.baseBranch,
                    "syncBaseLocally" to true,
                    "commandTimeoutSeconds" to input.commandTimeoutSeconds,
                    "commandRetries" to input.commandRetries,
                    "retryDelaySeconds" to input.retryDelaySeconds
                )
            )
            ensureStepOk("merge-sync", merged)
            steps += mapOf("name" to "merge-sync", "result" to merged)
        }

        mapOf(
            "ok" to true,
            "dryRun" to false,
            "baseBranch" to input.baseBranch,
            "featureBranch" to input.featureBranch,
            "steps" to steps
        )
    }
}
