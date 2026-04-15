import com.koupper.container.context
import com.koupper.octopus.annotations.Export
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

data class Input(
    val baseBranch: String = "develop",
    val featureBranch: String,
    val syncBase: Boolean = true,
    val requireCleanTree: Boolean = true,
    val createBranchIfMissing: Boolean = true,
    val allowedUntracked: List<String> = listOf("docs/future-providers-brief.md"),
    val commandTimeoutSeconds: Long = 120,
    val commandRetries: Int = 1,
    val retryDelaySeconds: Long = 2,
    val cleanupGeneratedScripts: Boolean = true
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
            last = CommandResult(
                command = args.joinToString(" "),
                exitCode = 124,
                output = "command timed out after ${timeoutSeconds}s"
            )
        } else {
            val output = process.inputStream.bufferedReader().readText().trim()
            last = CommandResult(command = args.joinToString(" "), exitCode = process.exitValue(), output = output)
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

private fun parseStatusLines(raw: String): List<String> {
    if (raw.isBlank()) return emptyList()
    return raw.lines().map { it.trim() }.filter { it.isNotBlank() }
}

private fun cleanupGeneratedArtifacts(input: Input, cwd: File): List<String> {
    if (!input.cleanupGeneratedScripts) return emptyList()

    val generated = listOf("job-runner.kts", "job-list.kts", "worker-builder.kts")
    val removed = mutableListOf<String>()

    generated.forEach { fileName ->
        val file = File(cwd, fileName)
        if (!file.exists()) return@forEach

        val status = runCommand(
            listOf("git", "status", "--porcelain", "--", fileName),
            cwd,
            input.commandTimeoutSeconds,
            input.commandRetries,
            input.retryDelaySeconds
        )
        if (status.exitCode != 0) return@forEach

        val isUntracked = status.output.lines().any { it.trim().startsWith("??") }
        if (isUntracked && file.delete()) {
            removed += fileName
        }
    }

    return removed
}

@Export
val setup: (Input) -> Map<String, Any?> = { input ->
    val cwd = File(context ?: ".").absoluteFile

    val cleanedArtifacts = cleanupGeneratedArtifacts(input, cwd)

    val gitCheck = CompletableFuture.supplyAsync {
        runCommand(listOf("git", "--version"), cwd, input.commandTimeoutSeconds, input.commandRetries, input.retryDelaySeconds)
    }
    val ghCheck = CompletableFuture.supplyAsync {
        runCommand(listOf("gh", "--version"), cwd, input.commandTimeoutSeconds, input.commandRetries, input.retryDelaySeconds)
    }

    val gitResult = gitCheck.get()
    val ghResult = ghCheck.get()
    ensureOk(gitResult, "git availability check")
    ensureOk(ghResult, "gh availability check")

    val inRepo = runCommand(
        listOf("git", "rev-parse", "--is-inside-work-tree"),
        cwd,
        input.commandTimeoutSeconds,
        input.commandRetries,
        input.retryDelaySeconds
    )
    ensureOk(inRepo, "git repository check")

    if (input.requireCleanTree) {
        val status = runCommand(
            listOf("git", "status", "--porcelain"),
            cwd,
            input.commandTimeoutSeconds,
            input.commandRetries,
            input.retryDelaySeconds
        )
        ensureOk(status, "git status")

        val dirtyLines = parseStatusLines(status.output).filterNot { line ->
            line.startsWith("?? ") && input.allowedUntracked.any { allowed -> line.endsWith(allowed) }
        }

        if (dirtyLines.isNotEmpty()) {
            error("working tree is dirty. Commit/stash changes first. Entries: ${dirtyLines.joinToString(" | ")}")
        }
    }

    if (input.syncBase) {
        ensureOk(
            runCommand(listOf("git", "fetch", "origin"), cwd, input.commandTimeoutSeconds, input.commandRetries, input.retryDelaySeconds),
            "git fetch"
        )
        ensureOk(
            runCommand(listOf("git", "checkout", input.baseBranch), cwd, input.commandTimeoutSeconds, input.commandRetries, input.retryDelaySeconds),
            "checkout base branch"
        )
        ensureOk(
            runCommand(
                listOf("git", "pull", "--ff-only", "origin", input.baseBranch),
                cwd,
                input.commandTimeoutSeconds,
                input.commandRetries,
                input.retryDelaySeconds
            ),
            "pull base branch"
        )
    }

    val exists = runCommand(
        listOf("git", "branch", "--list", input.featureBranch),
        cwd,
        input.commandTimeoutSeconds,
        input.commandRetries,
        input.retryDelaySeconds
    )
    ensureOk(exists, "feature branch existence check")

    if (exists.output.isBlank()) {
        if (!input.createBranchIfMissing) {
            error("feature branch '${input.featureBranch}' does not exist")
        }

        ensureOk(
            runCommand(
                listOf("git", "checkout", "-b", input.featureBranch),
                cwd,
                input.commandTimeoutSeconds,
                input.commandRetries,
                input.retryDelaySeconds
            ),
            "create feature branch"
        )
    } else {
        ensureOk(
            runCommand(
                listOf("git", "checkout", input.featureBranch),
                cwd,
                input.commandTimeoutSeconds,
                input.commandRetries,
                input.retryDelaySeconds
            ),
            "checkout feature branch"
        )
    }

    val current = runCommand(
        listOf("git", "branch", "--show-current"),
        cwd,
        input.commandTimeoutSeconds,
        input.commandRetries,
        input.retryDelaySeconds
    )
    ensureOk(current, "resolve current branch")

    mapOf(
        "ok" to true,
        "baseBranch" to input.baseBranch,
        "featureBranch" to input.featureBranch,
        "currentBranch" to current.output,
        "gitVersion" to gitResult.output,
        "ghVersion" to ghResult.output,
        "cleanedArtifacts" to cleanedArtifacts
    )
}
