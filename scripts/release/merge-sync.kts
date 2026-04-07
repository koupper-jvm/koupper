import com.koupper.container.context
import com.koupper.octopus.annotations.Export
import java.io.File
import java.util.concurrent.TimeUnit

data class Input(
    val prNumber: Int? = null,
    val mergeMethod: String = "merge",
    val admin: Boolean = true,
    val deleteBranch: Boolean = false,
    val baseBranch: String = "develop",
    val syncBaseLocally: Boolean = true,
    val commandTimeoutSeconds: Long = 120,
    val commandRetries: Int = 1,
    val retryDelaySeconds: Long = 2
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

        if (last.exitCode == 0 || attempt == retries) return last

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

private fun resolvePrNumber(cwd: File, input: Input): Int {
    val pr = runCommand(
        listOf("gh", "pr", "view", "--json", "number", "--jq", ".number"),
        cwd,
        input.commandTimeoutSeconds,
        input.commandRetries,
        input.retryDelaySeconds
    )
    ensureOk(pr, "resolve current PR number")
    return pr.output.trim().toIntOrNull() ?: error("invalid PR number output: ${pr.output}")
}

@Export
val setup: (Input) -> Map<String, Any?> = { input ->
    val cwd = File(context ?: ".").absoluteFile
    val pr = input.prNumber ?: resolvePrNumber(cwd, input)

    val methodFlag = when (input.mergeMethod.lowercase()) {
        "merge" -> "--merge"
        "squash" -> "--squash"
        "rebase" -> "--rebase"
        else -> error("unsupported merge method '${input.mergeMethod}'")
    }

    val mergeArgs = mutableListOf("gh", "pr", "merge", pr.toString(), methodFlag)
    if (input.admin) mergeArgs += "--admin"
    if (input.deleteBranch) mergeArgs += "--delete-branch"

    ensureOk(
        runCommand(mergeArgs, cwd, input.commandTimeoutSeconds, input.commandRetries, input.retryDelaySeconds),
        "merge PR"
    )

    if (input.syncBaseLocally) {
        ensureOk(
            runCommand(
                listOf("git", "checkout", input.baseBranch),
                cwd,
                input.commandTimeoutSeconds,
                input.commandRetries,
                input.retryDelaySeconds
            ),
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
            "sync base branch"
        )
    }

    val head = runCommand(
        listOf("git", "branch", "--show-current"),
        cwd,
        input.commandTimeoutSeconds,
        input.commandRetries,
        input.retryDelaySeconds
    )
    ensureOk(head, "read current branch")

    mapOf(
        "ok" to true,
        "prNumber" to pr,
        "mergeMethod" to input.mergeMethod,
        "baseBranch" to input.baseBranch,
        "currentBranch" to head.output
    )
}
