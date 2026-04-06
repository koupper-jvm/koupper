import com.koupper.container.context
import com.koupper.octopus.annotations.Export
import java.io.File

data class Input(
    val prNumber: Int? = null,
    val mergeMethod: String = "merge",
    val admin: Boolean = true,
    val deleteBranch: Boolean = false,
    val baseBranch: String = "develop",
    val syncBaseLocally: Boolean = true
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

private fun resolvePrNumber(cwd: File): Int {
    val pr = runCommand(listOf("gh", "pr", "view", "--json", "number", "--jq", ".number"), cwd)
    ensureOk(pr, "resolve current PR number")
    return pr.output.trim().toIntOrNull() ?: error("invalid PR number output: ${pr.output}")
}

@Export
val setup: (Input) -> Map<String, Any?> = { input ->
    val cwd = File(context ?: ".").absoluteFile
    val pr = input.prNumber ?: resolvePrNumber(cwd)

    val methodFlag = when (input.mergeMethod.lowercase()) {
        "merge" -> "--merge"
        "squash" -> "--squash"
        "rebase" -> "--rebase"
        else -> error("unsupported merge method '${input.mergeMethod}'")
    }

    val mergeArgs = mutableListOf("gh", "pr", "merge", pr.toString(), methodFlag)
    if (input.admin) mergeArgs += "--admin"
    if (input.deleteBranch) mergeArgs += "--delete-branch"

    ensureOk(runCommand(mergeArgs, cwd), "merge PR")

    if (input.syncBaseLocally) {
        ensureOk(runCommand(listOf("git", "checkout", input.baseBranch), cwd), "checkout base branch")
        ensureOk(
            runCommand(listOf("git", "pull", "--ff-only", "origin", input.baseBranch), cwd),
            "sync base branch"
        )
    }

    val head = runCommand(listOf("git", "branch", "--show-current"), cwd)
    ensureOk(head, "read current branch")

    mapOf(
        "ok" to true,
        "prNumber" to pr,
        "mergeMethod" to input.mergeMethod,
        "baseBranch" to input.baseBranch,
        "currentBranch" to head.output
    )
}
