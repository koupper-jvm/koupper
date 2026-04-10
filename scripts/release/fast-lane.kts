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
    val prTitle: String? = null,
    val prBody: String? = null,
    val mergeMethod: String = "merge",
    val enableAutoMerge: Boolean = true,
    val commandTimeoutSeconds: Long = 120,
    val commandRetries: Int = 1,
    val retryDelaySeconds: Long = 2
)

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
    ) { result -> done.complete(result) }

    return done.get()
}

private data class CommandResult(val command: String, val exitCode: Int, val output: String)

private fun runCommand(
    args: List<String>,
    cwd: File,
    timeoutSeconds: Long,
    retries: Int,
    retryDelaySeconds: Long
): CommandResult {
    var attempt = 0
    var last = CommandResult(args.joinToString(" "), 1, "")

    while (attempt <= retries) {
        val process = ProcessBuilder(args)
            .directory(cwd)
            .redirectErrorStream(true)
            .start()

        val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        last = if (!completed) {
            process.destroyForcibly()
            CommandResult(args.joinToString(" "), 124, "command timed out after ${timeoutSeconds}s")
        } else {
            CommandResult(args.joinToString(" "), process.exitValue(), process.inputStream.bufferedReader().readText().trim())
        }

        if (last.exitCode == 0 || attempt == retries) return last
        attempt++
        Thread.sleep(retryDelaySeconds * 1000)
    }

    return last
}

private fun ensureOk(step: String, result: Any?) {
    val map = result as? Map<*, *> ?: error("$step returned non-map result: $result")
    if (map["ok"] != true) error("$step reported failure: $result")
}

private fun ensureCommandOk(step: String, result: CommandResult) {
    if (result.exitCode != 0) error("$step failed (${result.command}): ${result.output}")
}

@Export
val setup: (Input) -> Map<String, Any?> = { input ->
    val cwd = File(context ?: ".").absoluteFile

    val preflight = runKoupperScript(
        cwd,
        "scripts/release/preflight.kts",
        mapOf(
            "baseBranch" to input.baseBranch,
            "featureBranch" to input.featureBranch
        )
    )
    ensureOk("preflight", preflight)

    val releaseFlow = runKoupperScript(
        cwd,
        "scripts/release/release-flow.kts",
        mapOf(
            "featureBranch" to input.featureBranch,
            "baseBranch" to input.baseBranch,
            "waitForCi" to false,
            "mergeAfterCi" to false,
            "prTitle" to input.prTitle,
            "prBody" to input.prBody
        )
    )
    ensureOk("release-flow", releaseFlow)

    val prNumberResult = runCommand(
        listOf("gh", "pr", "view", input.featureBranch, "--json", "number", "--jq", ".number"),
        cwd,
        input.commandTimeoutSeconds,
        input.commandRetries,
        input.retryDelaySeconds
    )
    ensureCommandOk("resolve PR number", prNumberResult)
    val prNumber = prNumberResult.output.trim().toIntOrNull() ?: error("unable to parse PR number from '${prNumberResult.output}'")

    if (input.enableAutoMerge) {
        val methodFlag = when (input.mergeMethod.lowercase()) {
            "squash" -> "--squash"
            "rebase" -> "--rebase"
            else -> "--merge"
        }
        val autoMergeResult = runCommand(
            listOf("gh", "pr", "merge", prNumber.toString(), "--auto", methodFlag),
            cwd,
            input.commandTimeoutSeconds,
            input.commandRetries,
            input.retryDelaySeconds
        )
        ensureCommandOk("enable auto-merge", autoMergeResult)
    }

    mapOf(
        "ok" to true,
        "baseBranch" to input.baseBranch,
        "featureBranch" to input.featureBranch,
        "prNumber" to prNumber,
        "autoMergeEnabled" to input.enableAutoMerge,
        "message" to "PR created without local CI wait; auto-merge is delegated to GitHub checks"
    )
}
