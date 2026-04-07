import com.koupper.container.context
import com.koupper.octopus.annotations.Export
import java.io.File
import java.util.concurrent.TimeUnit

data class Input(
    val branch: String,
    val workflowName: String = "Full Smoke Suite",
    val pollSeconds: Long = 20,
    val timeoutSeconds: Long = 1800,
    val failOnFailure: Boolean = true,
    val expectedHeadSha: String? = null,
    val commandTimeoutSeconds: Long = 60,
    val commandRetries: Int = 1,
    val retryDelaySeconds: Long = 2,
    val allowNoRuns: Boolean = false
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

private fun ensureOk(result: CommandResult, step: String) {
    if (result.exitCode != 0) {
        error("$step failed (${result.command}): ${result.output}")
    }
}

private fun latestRunSnapshot(cwd: File, input: Input): Map<String, String>? {
    val query = "map(select(.headSha == \"${input.expectedHeadSha ?: ""}\")) | .[0] | [.databaseId,.status,.conclusion,.url,.headSha] | @tsv"
    val fallbackQuery = ".[0] | [.databaseId,.status,.conclusion,.url,.headSha] | @tsv"
    val baseArgs = listOf(
        "gh", "run", "list",
        "--workflow", input.workflowName,
        "--branch", input.branch,
        "--limit", "10",
        "--json", "databaseId,status,conclusion,url,headSha"
    )

    val cmd = runCommand(
        if (!input.expectedHeadSha.isNullOrBlank()) baseArgs + listOf("--jq", query) else baseArgs + listOf("--jq", fallbackQuery),
        cwd,
        input.commandTimeoutSeconds,
        input.commandRetries,
        input.retryDelaySeconds
    )
    ensureOk(cmd, "read latest workflow run")

    val line = cmd.output.lines().firstOrNull { it.isNotBlank() }
    val resolvedLine = if (line.isNullOrBlank() && !input.expectedHeadSha.isNullOrBlank()) {
        val fallback = runCommand(
            baseArgs + listOf("--jq", fallbackQuery),
            cwd,
            input.commandTimeoutSeconds,
            input.commandRetries,
            input.retryDelaySeconds
        )
        ensureOk(fallback, "fallback workflow query")
        fallback.output.lines().firstOrNull { it.isNotBlank() }
    } else {
        line
    }

    val resolved = resolvedLine
        ?: return null

    val parts = resolved.split("\t", limit = 5)
    if (parts.size < 4) {
        error("unexpected workflow output format: $resolved")
    }

    return mapOf(
        "id" to parts[0],
        "status" to parts[1],
        "conclusion" to parts[2],
        "url" to parts[3],
        "headSha" to (parts.getOrNull(4) ?: "")
    )
}

@Export
val setup: (Input) -> Map<String, Any?> = { input ->
    val cwd = File(context ?: ".").absoluteFile
    val start = System.currentTimeMillis()
    val noRunsResult = mapOf(
        "ok" to false,
        "workflow" to input.workflowName,
        "branch" to input.branch,
        "status" to "not-found",
        "conclusion" to "no-runs"
    )

    val firstSnapshot = latestRunSnapshot(cwd, input)

    if (firstSnapshot == null) {
        if (input.allowNoRuns) {
            noRunsResult
        } else {
            error("no workflow runs found for workflow '${input.workflowName}' on branch '${input.branch}'")
        }
    } else {
        var snapshot: Map<String, String> = firstSnapshot

        while (snapshot["status"] != "completed") {
            val elapsed = (System.currentTimeMillis() - start) / 1000
            if (elapsed >= input.timeoutSeconds) {
                error("timeout waiting for CI run (${input.timeoutSeconds}s). Last snapshot: $snapshot")
            }

            Thread.sleep(input.pollSeconds * 1000)
            val nextSnapshot = latestRunSnapshot(cwd, input)
            if (nextSnapshot == null) {
                if (input.allowNoRuns) {
                    snapshot = mapOf(
                        "id" to "",
                        "status" to "completed",
                        "conclusion" to "no-runs",
                        "url" to "",
                        "headSha" to ""
                    )
                    break
                }
                error("workflow run disappeared while polling for '${input.workflowName}' on branch '${input.branch}'")
            }
            snapshot = nextSnapshot
        }

        val conclusion = snapshot["conclusion"].orEmpty()
        if (input.failOnFailure && conclusion != "success" && conclusion != "no-runs") {
            error("CI finished with conclusion '$conclusion'. Run URL: ${snapshot["url"]}")
        }

        mapOf(
            "ok" to (conclusion == "success"),
            "workflow" to input.workflowName,
            "branch" to input.branch,
            "runId" to snapshot["id"],
            "headSha" to snapshot["headSha"],
            "status" to snapshot["status"],
            "conclusion" to conclusion,
            "url" to snapshot["url"]
        )
    }
}
