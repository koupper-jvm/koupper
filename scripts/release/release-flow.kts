import com.koupper.container.app
import com.koupper.container.context
import com.koupper.octopus.ScriptExecutor
import com.koupper.octopus.annotations.Export
import java.io.File
import java.util.concurrent.CompletableFuture

data class Input(
    val featureBranch: String,
    val baseBranch: String = "develop",
    val syncBase: Boolean = true,
    val requireCleanTree: Boolean = true,
    val prTitle: String? = null,
    val prBody: String? = null,
    val workflowName: String = "Full Smoke Suite",
    val waitForCi: Boolean = true,
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

private fun runCommand(args: List<String>, cwd: File): CommandResult {
    val process = ProcessBuilder(args)
        .directory(cwd)
        .redirectErrorStream(true)
        .start()

    val output = process.inputStream.bufferedReader().readText().trim()
    val exitCode = process.waitFor()
    return CommandResult(command = args.joinToString(" "), exitCode = exitCode, output = output)
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
            "allowedUntracked" to listOf("docs/future-providers-brief.md")
        )
    )
    steps += mapOf("name" to "preflight", "result" to preflight)

    val gitStatusFuture = CompletableFuture.supplyAsync {
        runCommand(listOf("git", "status", "--short", "--branch"), cwd).output
    }
    val ghAuthFuture = CompletableFuture.supplyAsync {
        runCommand(listOf("gh", "auth", "status"), cwd).output
    }
    steps += mapOf(
        "name" to "parallel-checks",
        "result" to mapOf(
            "gitStatus" to gitStatusFuture.get(),
            "ghAuth" to ghAuthFuture.get()
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
                "draft" to false
            )
        )
        steps += mapOf("name" to "pr-create", "result" to prCreate)

        if (input.waitForCi) {
            val ciWatch = runKoupperScript(
                cwd,
                "scripts/release/ci-watch.kts",
                mapOf(
                    "branch" to input.featureBranch,
                    "workflowName" to input.workflowName,
                    "pollSeconds" to 20,
                    "timeoutSeconds" to 2400,
                    "failOnFailure" to true
                )
            )
            steps += mapOf("name" to "ci-watch", "result" to ciWatch)
        }

        if (input.mergeAfterCi) {
            val prNumber = runCommand(
                listOf("gh", "pr", "view", input.featureBranch, "--json", "number", "--jq", ".number"),
                cwd
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
                    "syncBaseLocally" to true
                )
            )
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
