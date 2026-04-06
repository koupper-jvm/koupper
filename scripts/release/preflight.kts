import com.koupper.container.context
import com.koupper.octopus.annotations.Export
import java.io.File
import java.util.concurrent.CompletableFuture

data class Input(
    val baseBranch: String = "develop",
    val featureBranch: String,
    val syncBase: Boolean = true,
    val requireCleanTree: Boolean = true,
    val createBranchIfMissing: Boolean = true,
    val allowedUntracked: List<String> = listOf("docs/future-providers-brief.md")
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

private fun ensureOk(result: CommandResult, step: String) {
    if (result.exitCode != 0) {
        error("$step failed (${result.command}): ${result.output}")
    }
}

private fun parseStatusLines(raw: String): List<String> {
    if (raw.isBlank()) return emptyList()
    return raw.lines().map { it.trim() }.filter { it.isNotBlank() }
}

@Export
val setup: (Input) -> Map<String, Any?> = { input ->
    val cwd = File(context ?: ".").absoluteFile

    val gitCheck = CompletableFuture.supplyAsync {
        runCommand(listOf("git", "--version"), cwd)
    }
    val ghCheck = CompletableFuture.supplyAsync {
        runCommand(listOf("gh", "--version"), cwd)
    }

    val gitResult = gitCheck.get()
    val ghResult = ghCheck.get()
    ensureOk(gitResult, "git availability check")
    ensureOk(ghResult, "gh availability check")

    val inRepo = runCommand(listOf("git", "rev-parse", "--is-inside-work-tree"), cwd)
    ensureOk(inRepo, "git repository check")

    if (input.requireCleanTree) {
        val status = runCommand(listOf("git", "status", "--porcelain"), cwd)
        ensureOk(status, "git status")

        val dirtyLines = parseStatusLines(status.output).filterNot { line ->
            line.startsWith("?? ") && input.allowedUntracked.any { allowed -> line.endsWith(allowed) }
        }

        if (dirtyLines.isNotEmpty()) {
            error("working tree is dirty. Commit/stash changes first. Entries: ${dirtyLines.joinToString(" | ")}")
        }
    }

    if (input.syncBase) {
        ensureOk(runCommand(listOf("git", "fetch", "origin"), cwd), "git fetch")
        ensureOk(runCommand(listOf("git", "checkout", input.baseBranch), cwd), "checkout base branch")
        ensureOk(
            runCommand(listOf("git", "pull", "--ff-only", "origin", input.baseBranch), cwd),
            "pull base branch"
        )
    }

    val exists = runCommand(listOf("git", "branch", "--list", input.featureBranch), cwd)
    ensureOk(exists, "feature branch existence check")

    if (exists.output.isBlank()) {
        if (!input.createBranchIfMissing) {
            error("feature branch '${input.featureBranch}' does not exist")
        }

        ensureOk(
            runCommand(listOf("git", "checkout", "-b", input.featureBranch), cwd),
            "create feature branch"
        )
    } else {
        ensureOk(
            runCommand(listOf("git", "checkout", input.featureBranch), cwd),
            "checkout feature branch"
        )
    }

    val current = runCommand(listOf("git", "branch", "--show-current"), cwd)
    ensureOk(current, "resolve current branch")

    mapOf(
        "ok" to true,
        "baseBranch" to input.baseBranch,
        "featureBranch" to input.featureBranch,
        "currentBranch" to current.output,
        "gitVersion" to gitResult.output,
        "ghVersion" to ghResult.output
    )
}
