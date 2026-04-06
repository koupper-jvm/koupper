import com.koupper.container.context
import com.koupper.octopus.annotations.Export
import java.io.File

data class Input(
    val branch: String,
    val workflowName: String = "Full Smoke Suite",
    val pollSeconds: Long = 20,
    val timeoutSeconds: Long = 1800,
    val failOnFailure: Boolean = true
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

private fun latestRunSnapshot(cwd: File, workflow: String, branch: String): Map<String, String> {
    val query = ".[0] | \"\\(.databaseId)|\\(.status)|\\(.conclusion)|\\(.url)\""
    val cmd = runCommand(
        listOf(
            "gh", "run", "list",
            "--workflow", workflow,
            "--branch", branch,
            "--limit", "1",
            "--json", "databaseId,status,conclusion,url",
            "--jq", query
        ),
        cwd
    )
    ensureOk(cmd, "read latest workflow run")

    val line = cmd.output.lines().firstOrNull { it.isNotBlank() }
        ?: error("no workflow runs found for workflow '$workflow' on branch '$branch'")

    val parts = line.split("|", limit = 4)
    if (parts.size < 4) {
        error("unexpected workflow output format: $line")
    }

    return mapOf(
        "id" to parts[0],
        "status" to parts[1],
        "conclusion" to parts[2],
        "url" to parts[3]
    )
}

@Export
val setup: (Input) -> Map<String, Any?> = { input ->
    val cwd = File(context ?: ".").absoluteFile
    val start = System.currentTimeMillis()

    var snapshot = latestRunSnapshot(cwd, input.workflowName, input.branch)

    while (snapshot["status"] != "completed") {
        val elapsed = (System.currentTimeMillis() - start) / 1000
        if (elapsed >= input.timeoutSeconds) {
            error("timeout waiting for CI run (${input.timeoutSeconds}s). Last snapshot: $snapshot")
        }

        Thread.sleep(input.pollSeconds * 1000)
        snapshot = latestRunSnapshot(cwd, input.workflowName, input.branch)
    }

    val conclusion = snapshot["conclusion"].orEmpty()
    if (input.failOnFailure && conclusion != "success") {
        error("CI finished with conclusion '$conclusion'. Run URL: ${snapshot["url"]}")
    }

    mapOf(
        "ok" to (conclusion == "success"),
        "workflow" to input.workflowName,
        "branch" to input.branch,
        "runId" to snapshot["id"],
        "status" to snapshot["status"],
        "conclusion" to conclusion,
        "url" to snapshot["url"]
    )
}
