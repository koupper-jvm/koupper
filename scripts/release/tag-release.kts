import com.koupper.container.context
import com.koupper.octopus.annotations.Export
import java.io.File
import java.util.concurrent.TimeUnit

data class Input(
    val version: String,
    val push: Boolean = false,
    val dryRun: Boolean = true,
    val remote: String = "origin",
    val commandTimeoutSeconds: Long = 120
)

data class CommandResult(
    val command: String,
    val exitCode: Int,
    val output: String
)

private fun runCommand(args: List<String>, cwd: File, timeoutSeconds: Long): CommandResult {
    val process = ProcessBuilder(args)
        .directory(cwd)
        .redirectErrorStream(true)
        .start()
    val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
    if (!completed) {
        process.destroyForcibly()
        return CommandResult(args.joinToString(" "), 124, "command timed out after ${timeoutSeconds}s")
    }
    return CommandResult(
        command = args.joinToString(" "),
        exitCode = process.exitValue(),
        output = process.inputStream.bufferedReader().readText().trim()
    )
}

private fun ensureOk(result: CommandResult, step: String) {
    if (result.exitCode != 0) error("$step failed (${result.command}): ${result.output}")
}

@Export
val setup: (Input) -> Map<String, Any?> = { input ->
    val cwd = File(context ?: ".").absoluteFile
    val tag = if (input.version.startsWith("v")) input.version else "v${input.version}"

    if (!input.dryRun) {
        ensureOk(
            runCommand(listOf("git", "tag", "-a", tag, "-m", "release $tag"), cwd, input.commandTimeoutSeconds),
            "create git tag"
        )
        if (input.push) {
            ensureOk(
                runCommand(listOf("git", "push", input.remote, tag), cwd, input.commandTimeoutSeconds),
                "push git tag"
            )
        }
    }

    mapOf(
        "ok" to true,
        "tag" to tag,
        "push" to input.push,
        "dryRun" to input.dryRun
    )
}
